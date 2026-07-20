package org.lewisodb.intellij.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class OdbSessionStateTest {
    @Test
    fun `cleanup deletes contained session state once`() {
        val root = Files.createTempDirectory("odb-managed-root").toRealPath()
        val directory = Files.createTempDirectory(root, "session-").toRealPath()
        Files.writeString(directory.resolve("odb-runtime.jar"), "runtime")
        val session = OdbSessionState.create(root, directory)

        val first = session.cleanup()
        val second = session.cleanup()

        assertSame(OdbCleanupResult.Cleaned, first)
        assertSame(first, second)
        assertFalse(Files.exists(directory))
    }

    @Test
    fun `session state rejects deletion outside its managed root`() {
        val root = Files.createTempDirectory("odb-managed-root").toRealPath()
        val outside = Files.createTempDirectory("odb-outside-session").toRealPath()

        val error = assertThrows(IllegalArgumentException::class.java) {
            OdbSessionState.create(root, outside)
        }

        assertEquals("ODB session directory must be a direct child of plugin-managed state.", error.message)
    }

    @Test
    fun `concurrent cleanup requests delete once`() {
        val root = Files.createTempDirectory("odb-managed-root").toRealPath()
        val directory = Files.createTempDirectory(root, "session-").toRealPath()
        val calls = AtomicInteger()
        val session = OdbSessionState.create(root, directory) {
            calls.incrementAndGet()
            Files.delete(it)
        }
        val executor = Executors.newFixedThreadPool(4)

        val results = executor.invokeAll(List(8) { Callable { session.cleanup() } }).map { it.get() }
        executor.shutdownNow()

        assertEquals(1, calls.get())
        results.forEach { assertSame(OdbCleanupResult.Cleaned, it) }
    }

    @Test
    fun `cleanup failure is retained for diagnostics and not retried`() {
        val root = Files.createTempDirectory("odb-managed-root").toRealPath()
        val directory = Files.createTempDirectory(root, "session-").toRealPath()
        val failure = IOException("locked")
        val calls = AtomicInteger()
        val session = OdbSessionState.create(root, directory) {
            calls.incrementAndGet()
            throw failure
        }

        val first = session.cleanup()
        val second = session.cleanup()

        assertTrue(first is OdbCleanupResult.Failed)
        assertSame(first, second)
        assertSame(failure, (first as OdbCleanupResult.Failed).cause)
        assertEquals(1, calls.get())
        assertTrue(Files.exists(directory))
    }
}
