package org.lewisodb.intellij.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class OdbSessionStateTest {
    @Test
    fun `managed session records owner then child identity`() {
        val root = Files.createTempDirectory("odb-metadata-root").toRealPath()
        val owner = OdbProcessIdentity(101, Instant.parse("2026-07-20T12:00:00Z"))
        val child = OdbProcessIdentity(202, Instant.parse("2026-07-20T12:00:01Z"))
        val session = OdbSessionState.createManaged(
            root,
            owner,
            sessionIdFactory = { "11111111-1111-1111-1111-111111111111" },
        )

        session.recordChild(child)

        assertEquals(
            """{"version":1,"sessionId":"11111111-1111-1111-1111-111111111111","ideOwner":{"pid":101,"startedAt":"2026-07-20T12:00:00Z"},"odbChild":{"pid":202,"startedAt":"2026-07-20T12:00:01Z"}}""",
            Files.readString(session.directory.resolve("session.json")),
        )
    }

    @Test
    fun `failed child metadata replace keeps last complete metadata`() {
        val root = Files.createTempDirectory("odb-metadata-failure-root").toRealPath()
        val moves = AtomicInteger()
        val session = OdbSessionState.createManaged(
            root,
            OdbProcessIdentity(101, Instant.parse("2026-07-20T12:00:00Z")),
            sessionIdFactory = { "22222222-2222-2222-2222-222222222222" },
            metadataMover = OdbMetadataMover { source, target ->
                if (moves.getAndIncrement() > 0) throw IOException("replace failed")
                Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            },
        )

        assertThrows(IOException::class.java) {
            session.recordChild(OdbProcessIdentity(202, Instant.parse("2026-07-20T12:00:01Z")))
        }

        val metadata = Files.readString(session.directory.resolve("session.json"))
        assertTrue(metadata.contains("\"odbChild\":null"))
        assertEquals(1, Files.list(session.directory).use { it.count() })
    }

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
