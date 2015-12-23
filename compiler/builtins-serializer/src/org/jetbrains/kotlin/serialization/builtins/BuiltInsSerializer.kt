/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.serialization.builtins

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analyzer.ModuleContent
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.builtins.BuiltInsSerializedResourcePaths
import org.jetbrains.kotlin.builtins.BuiltinsPackageFragment
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.JvmPackagePartProvider
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.addKotlinSourceRoots
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.CompilerEnvironment
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.resolve.jvm.JvmAnalyzerFacade
import org.jetbrains.kotlin.resolve.jvm.JvmPlatformParameters
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.serialization.DescriptorSerializer
import org.jetbrains.kotlin.serialization.ProtoBuf
import org.jetbrains.kotlin.utils.recursePostOrder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

public class BuiltInsSerializer(private val dependOnOldBuiltIns: Boolean) {
    private var totalSize = 0
    private var totalFiles = 0

    public fun serialize(
            destDir: File,
            srcDirs: List<File>,
            extraClassPath: List<File>,
            onComplete: (totalSize: Int, totalFiles: Int) -> Unit
    ) {
        val rootDisposable = Disposer.newDisposable()
        try {
            serialize(rootDisposable, destDir, srcDirs, extraClassPath)
            onComplete(totalSize, totalFiles)
        }
        finally {
            Disposer.dispose(rootDisposable)
        }
    }

    private inner class BuiltinsSourcesModule : ModuleInfo {
        override val name = Name.special("<module for resolving builtin source files>")
        override fun dependencies() = listOf(this)
        override fun dependencyOnBuiltins(): ModuleInfo.DependencyOnBuiltins =
                if (dependOnOldBuiltIns) ModuleInfo.DependenciesOnBuiltins.LAST else ModuleInfo.DependenciesOnBuiltins.NONE
    }

    private fun serialize(disposable: Disposable, destDir: File, srcDirs: List<File>, extraClassPath: List<File>) {
        val configuration = CompilerConfiguration()
        configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)

        configuration.addKotlinSourceRoots(srcDirs.map { it.path })
        configuration.addJvmClasspathRoots(extraClassPath)

        val environment = KotlinCoreEnvironment.createForTests(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)

        val files = environment.getSourceFiles()

        val builtInModule = BuiltinsSourcesModule()
        val resolver = JvmAnalyzerFacade.setupResolverForProject(
                "builtIns source",
                ProjectContext(environment.project), listOf(builtInModule),
                { ModuleContent(files, GlobalSearchScope.EMPTY_SCOPE) },
                JvmPlatformParameters { throw IllegalStateException() },
                CompilerEnvironment,
                packagePartProviderFactory = { module, content -> JvmPackagePartProvider(environment) }
        )

        val moduleDescriptor = resolver.descriptorForModule(builtInModule)

        // We don't use FileUtil because it spawns JNA initialization, which fails because we don't have (and don't want to have) its
        // native libraries in the compiler jar (libjnidispatch.so / jnidispatch.dll / ...)
        destDir.recursePostOrder { it.delete() }

        if (!destDir.mkdirs()) {
            System.err.println("Could not make directories: " + destDir)
        }

        files.map { it.packageFqName }.toSet().forEach {
            fqName ->
            serializePackage(moduleDescriptor, fqName, destDir)
        }
    }

    private fun serializePackage(module: ModuleDescriptor, fqName: FqName, destDir: File) {
        val packageView = module.getPackage(fqName)

        // TODO: perform some kind of validation? At the moment not possible because DescriptorValidator is in compiler-tests
        // DescriptorValidator.validate(packageView)

        val classifierDescriptors = DescriptorSerializer.sort(packageView.memberScope.getContributedDescriptors(DescriptorKindFilter.CLASSIFIERS))

        val message = BuiltInsProtoBuf.BuiltIns.newBuilder()

        val extension = BuiltInsSerializerExtension()
        serializeClasses(classifierDescriptors, extension) {
            classDescriptor, classProto ->
            val stream = ByteArrayOutputStream()
            classProto.writeTo(stream)
            write(destDir, getFileName(classDescriptor), stream)
            message.addClass(classProto)
        }

        val packageStream = ByteArrayOutputStream()
        val fragments = packageView.fragments
        val packageProto = DescriptorSerializer.createTopLevel(extension).packageProto(fragments).build()
        packageProto.writeTo(packageStream)
        write(destDir, BuiltInsSerializedResourcePaths.getPackageFilePath(fqName), packageStream)
        message.setPackage(packageProto)

        val nameStream = ByteArrayOutputStream()
        val (strings, qualifiedNames) = extension.stringTable.buildProto()
        strings.writeDelimitedTo(nameStream)
        qualifiedNames.writeDelimitedTo(nameStream)
        write(destDir, BuiltInsSerializedResourcePaths.getStringTableFilePath(fqName), nameStream)
        message.setStrings(strings)
        message.setQualifiedNames(qualifiedNames)

        val builtinsFileStream = ByteArrayOutputStream()
        with(DataOutputStream(builtinsFileStream)) {
            val version = BuiltinsPackageFragment.VERSION.toArray()
            writeInt(version.size)
            version.forEach { writeInt(it) }
        }
        message.build().writeTo(builtinsFileStream)
        write(destDir, BuiltInsSerializedResourcePaths.getBuiltInsFilePath(fqName), builtinsFileStream)
    }

    private fun write(destDir: File, fileName: String, stream: ByteArrayOutputStream) {
        totalSize += stream.size()
        totalFiles++
        File(destDir, fileName).parentFile.mkdirs()
        File(destDir, fileName).writeBytes(stream.toByteArray())
    }

    private fun serializeClass(
            classDescriptor: ClassDescriptor,
            serializer: BuiltInsSerializerExtension,
            writeClass: (ClassDescriptor, ProtoBuf.Class) -> Unit
    ) {
        val classProto = DescriptorSerializer.createTopLevel(serializer).classProto(classDescriptor).build()
        writeClass(classDescriptor, classProto)

        serializeClasses(classDescriptor.unsubstitutedInnerClassesScope.getContributedDescriptors(), serializer, writeClass)
    }

    private fun serializeClasses(
            descriptors: Collection<DeclarationDescriptor>,
            serializer: BuiltInsSerializerExtension,
            writeClass: (ClassDescriptor, ProtoBuf.Class) -> Unit
    ) {
        for (descriptor in descriptors) {
            if (descriptor is ClassDescriptor && descriptor.kind != ClassKind.ENUM_ENTRY) {
                serializeClass(descriptor, serializer, writeClass)
            }
        }
    }

    private fun getFileName(classDescriptor: ClassDescriptor): String {
        return BuiltInsSerializedResourcePaths.getClassMetadataPath(classDescriptor.classId)
    }
}
