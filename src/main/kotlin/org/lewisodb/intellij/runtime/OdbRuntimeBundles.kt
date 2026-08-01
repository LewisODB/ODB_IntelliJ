package org.lewisodb.intellij.runtime

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

class ClasspathOdbRuntimeBundle(
    private val classLoader: ClassLoader = ClasspathOdbRuntimeBundle::class.java.classLoader,
) : OdbRuntimeBundle {
    override fun openManifest(): InputStream? = classLoader.getResourceAsStream("odb/runtime.json")
    override fun openRuntime(): InputStream? = classLoader.getResourceAsStream("odb/odb-runtime.jar")
}

class FileOdbRuntimeBundle(
    private val manifest: Path,
    private val runtime: Path,
) : OdbRuntimeBundle {
    override fun openManifest(): InputStream? = manifest.takeIf(Files::isRegularFile)?.let(Files::newInputStream)
    override fun openRuntime(): InputStream? = runtime.takeIf(Files::isRegularFile)?.let(Files::newInputStream)
}
