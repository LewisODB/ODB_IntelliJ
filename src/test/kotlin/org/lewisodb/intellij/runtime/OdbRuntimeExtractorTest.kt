package org.lewisodb.intellij.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class OdbRuntimeExtractorTest {
    @Test
    fun `copies verified bytes into a private session using atomic move`() {
        val runtime = probeBytes()
        val root = Files.createTempDirectory("odb-runtime-root")
        var atomicMoveCalled = false
        val extractor = OdbRuntimeExtractor(root, bundle(runtime), OdbAtomicMover { source, target ->
            atomicMoveCalled = true
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        })

        val prepared = extractor.prepare()

        assertTrue(atomicMoveCalled)
        assertTrue(prepared.sessionDirectory.startsWith(root.toRealPath()))
        assertTrue(Files.isRegularFile(prepared.runtimeJar))
        assertArrayEquals(runtime, Files.readAllBytes(prepared.runtimeJar))
        assertTrue(prepared.token.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `concurrent preparations share neither directory nor runtime file`() {
        val root = Files.createTempDirectory("odb-runtime-concurrent")
        val extractor = OdbRuntimeExtractor(root, bundle(probeBytes()))
        val executor = Executors.newFixedThreadPool(2)
        val prepared = executor.invokeAll(listOf(Callable { extractor.prepare() }, Callable { extractor.prepare() }))
            .map { it.get() }
        executor.shutdownNow()

        assertNotEquals(prepared[0].sessionDirectory, prepared[1].sessionDirectory)
        assertNotEquals(prepared[0].runtimeJar, prepared[1].runtimeJar)
        assertNotEquals(prepared[0].token, prepared[1].token)
    }

    @Test
    fun `missing malformed mismatched interrupted and failed moves expose no runtime`() {
        val runtime = probeBytes()
        val cases = listOf<OdbRuntimeBundle>(
            object : OdbRuntimeBundle {
                override fun openManifest(): InputStream? = null
                override fun openRuntime(): InputStream? = ByteArrayInputStream(runtime)
            },
            object : OdbRuntimeBundle {
                override fun openManifest(): InputStream = throw IOException("unreadable")
                override fun openRuntime(): InputStream = ByteArrayInputStream(runtime)
            },
            object : OdbRuntimeBundle {
                override fun openManifest(): InputStream = ByteArrayInputStream(
                    OdbRuntimeManifestTest.validManifest(sha256(runtime)).toByteArray(),
                )
                override fun openRuntime(): InputStream? = null
            },
            bundle(runtime, manifest = "not-json"),
            bundle(runtime, manifest = OdbRuntimeManifestTest.validManifest("c".repeat(64))),
            bundle(runtime, runtimeFactory = { ThrowingInputStream(runtime, runtime.size / 2) }),
        )

        for (bundle in cases) {
            val root = Files.createTempDirectory("odb-runtime-failure")
            assertThrows(OdbRuntimeException::class.java) { OdbRuntimeExtractor(root, bundle).prepare() }
            assertFalse(Files.walk(root).use { paths -> paths.anyMatch { it.fileName.toString() == "odb-runtime.jar" } })
        }

        val root = Files.createTempDirectory("odb-runtime-move-failure")
        assertThrows(OdbRuntimeException::class.java) {
            OdbRuntimeExtractor(root, bundle(runtime), OdbAtomicMover { _, _ -> throw IOException("move failed") }).prepare()
        }
        assertFalse(Files.walk(root).use { paths -> paths.anyMatch { it.fileName.toString() == "odb-runtime.jar" } })
    }

    @Test
    fun `rejects a symlinked managed root`() {
        val parent = Files.createTempDirectory("odb-runtime-link")
        val target = Files.createDirectory(parent.resolve("target"))
        val link = parent.resolve("sessions")
        try {
            Files.createSymbolicLink(link, target)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false)
        }

        assertThrows(OdbRuntimeException::class.java) { OdbRuntimeExtractor(link, bundle(probeBytes())).prepare() }
        assertFalse(Files.exists(target.resolve("odb-runtime.jar")))
    }

    @Test
    fun `retry after a failed atomic move gets fresh private state`() {
        val root = Files.createTempDirectory("odb-runtime-retry")
        val moves = AtomicInteger()
        val extractor = OdbRuntimeExtractor(root, bundle(probeBytes()), OdbAtomicMover { source, target ->
            if (moves.getAndIncrement() == 0) throw IOException("first move failed")
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        })

        assertThrows(OdbRuntimeException::class.java) { extractor.prepare() }
        val prepared = extractor.prepare()

        assertTrue(Files.isRegularFile(prepared.runtimeJar))
        assertEquals(1, Files.list(root.toRealPath()).use { it.count() })
    }

    private fun probeBytes(): ByteArray = Files.readAllBytes(Path.of(System.getProperty("org.lewisodb.intellij.testProbe")))

    private fun bundle(
        runtime: ByteArray,
        manifest: String = OdbRuntimeManifestTest.validManifest(sha256(runtime)),
        runtimeFactory: () -> InputStream = { ByteArrayInputStream(runtime) },
    ): OdbRuntimeBundle = object : OdbRuntimeBundle {
        override fun openManifest(): InputStream = ByteArrayInputStream(manifest.toByteArray())
        override fun openRuntime(): InputStream = runtimeFactory()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class ThrowingInputStream(private val bytes: ByteArray, private val stop: Int) : InputStream() {
        private var offset = 0
        override fun read(): Int {
            if (offset == stop) throw IOException("interrupted")
            return if (offset < bytes.size) bytes[offset++].toInt() and 0xff else -1
        }
    }
}
