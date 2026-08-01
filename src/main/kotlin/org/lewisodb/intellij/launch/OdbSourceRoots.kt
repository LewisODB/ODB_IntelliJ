package org.lewisodb.intellij.launch

import com.intellij.execution.ExecutionException
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal object OdbSourceRoots {
    const val FILE_NAME = "source-roots.txt"

    fun write(configuration: ApplicationConfiguration, sessionDirectory: Path) {
        val roots = linkedSetOf<Path>()
        configuration.mainClass?.containingFile?.virtualFile
            ?.let { ProjectRootManager.getInstance(configuration.project).fileIndex.getSourceRootForFile(it) }
            ?.localDirectory()
            ?.let(roots::add)
        configuration.configurationModule.module?.let { module ->
            ModuleRootManager.getInstance(module).sourceRoots
                .mapNotNull { it.localDirectory() }
                .forEach(roots::add)
        }
        if (roots.isEmpty()) return

        try {
            Files.write(
                sessionDirectory.resolve(FILE_NAME),
                roots.map(Path::toString),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
        } catch (error: Exception) {
            throw ExecutionException("Run with ODB could not prepare IntelliJ source roots.", error)
        }
    }

    private fun VirtualFile.localDirectory(): Path? {
        if (fileSystem.protocol != StandardFileSystems.FILE_PROTOCOL) return null
        return runCatching { toNioPath().toAbsolutePath().normalize() }
            .getOrNull()
            ?.takeIf { Files.isDirectory(it) && '\n' !in it.toString() && '\r' !in it.toString() }
    }
}
