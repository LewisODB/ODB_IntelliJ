package org.lewisodb.intellij.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OdbStaleSessionSweeperTest {
    @Test
    fun `sweep deletes a session only when owner and child are both gone`() {
        val root = Files.createTempDirectory("odb-sweep-stale-root").toRealPath()
        val session = Files.createDirectory(root.resolve("session-11111111-1111-1111-1111-111111111111"))
        Files.writeString(session.resolve("odb-runtime.jar"), "runtime")
        Files.writeString(
            session.resolve("session.json"),
            """{"version":1,"sessionId":"11111111-1111-1111-1111-111111111111","ideOwner":{"pid":10,"startedAt":"2026-07-20T12:00:00Z"},"odbChild":{"pid":11,"startedAt":"2026-07-20T12:00:01Z"}}""",
        )
        val sweeper = OdbStaleSessionSweeper(root) { OdbProcessObservation.Missing }

        val report = sweeper.sweep()

        assertEquals(OdbSweepReport(deleted = 1, preserved = 0, failed = 0), report)
        assertFalse(Files.exists(session))
    }

    @Test
    fun `live owner or live orphan preserves the session`() {
        val root = Files.createTempDirectory("odb-sweep-live-root").toRealPath()
        val ownerLive = createSession(root, "11111111-1111-1111-1111-111111111111", 10, 11)
        val childLive = createSession(root, "22222222-2222-2222-2222-222222222222", 20, 21)
        val liveOwner = OdbProcessIdentity(10, OWNER_START)
        val liveChild = OdbProcessIdentity(21, CHILD_START)
        val sweeper = OdbStaleSessionSweeper(root) { pid ->
            when (pid) {
                10L -> OdbProcessObservation.Running(liveOwner)
                21L -> OdbProcessObservation.Running(liveChild)
                else -> OdbProcessObservation.Missing
            }
        }

        val report = sweeper.sweep()

        assertEquals(OdbSweepReport(deleted = 0, preserved = 2, failed = 0), report)
        assertTrue(Files.exists(ownerLive))
        assertTrue(Files.exists(childLive))
    }

    @Test
    fun `PID reuse preserves the session`() {
        val root = Files.createTempDirectory("odb-sweep-reuse-root").toRealPath()
        val session = createSession(root, "33333333-3333-3333-3333-333333333333", 30, 31)
        val sweeper = OdbStaleSessionSweeper(root) { pid ->
            OdbProcessObservation.Running(
                OdbProcessIdentity(pid, Instant.parse("2026-07-20T13:00:00Z")),
            )
        }

        val report = sweeper.sweep()

        assertEquals(
            OdbRecordedIdentityStatus.REUSED,
            classifyRecordedIdentity(
                OdbProcessIdentity(30, OWNER_START),
                OdbProcessObservation.Running(OdbProcessIdentity(30, Instant.parse("2026-07-20T13:00:00Z"))),
            ),
        )
        assertEquals(OdbSweepReport(deleted = 0, preserved = 1, failed = 0), report)
        assertTrue(Files.exists(session))
    }

    @Test
    fun `matching PID and start time classify as the same live process`() {
        val recorded = OdbProcessIdentity(30, OWNER_START)

        assertEquals(
            OdbRecordedIdentityStatus.LIVE,
            classifyRecordedIdentity(recorded, OdbProcessObservation.Running(recorded)),
        )
    }

    @Test
    fun `incomplete corrupt missing and uninspectable sessions are preserved`() {
        val root = Files.createTempDirectory("odb-sweep-unknown-root").toRealPath()
        val incompleteId = "44444444-4444-4444-4444-444444444444"
        val incomplete = Files.createDirectory(root.resolve("session-$incompleteId"))
        Files.writeString(
            incomplete.resolve("session.json"),
            """{"version":1,"sessionId":"$incompleteId","ideOwner":{"pid":40,"startedAt":"$OWNER_START"},"odbChild":null}""",
        )
        val corrupt = Files.createDirectory(root.resolve("session-55555555-5555-5555-5555-555555555555"))
        Files.writeString(corrupt.resolve("session.json"), "{bad json")
        val uninspectable = createSession(root, "66666666-6666-6666-6666-666666666666", 60, 61)
        val missing = Files.createDirectory(root.resolve("session-88888888-8888-8888-8888-888888888888"))
        val unrelated = Files.writeString(root.resolve("keep-me.txt"), "not a session")
        val sweeper = OdbStaleSessionSweeper(root) { pid ->
            if (pid == 60L) OdbProcessObservation.Unknown else OdbProcessObservation.Missing
        }

        val report = sweeper.sweep()

        assertEquals(OdbSweepReport(deleted = 0, preserved = 5, failed = 0), report)
        assertTrue(Files.exists(incomplete))
        assertTrue(Files.exists(corrupt))
        assertTrue(Files.exists(uninspectable))
        assertTrue(Files.exists(missing))
        assertTrue(Files.exists(unrelated))
    }

    @Test
    fun `concurrent normal cleanup makes startup sweep a harmless no-op`() {
        val root = Files.createTempDirectory("odb-sweep-race-root").toRealPath()
        val session = OdbSessionState.createManaged(
            root,
            OdbProcessIdentity(80, OWNER_START),
            sessionIdFactory = { "99999999-9999-9999-9999-999999999999" },
        )
        session.recordChild(OdbProcessIdentity(81, CHILD_START))
        val inspecting = CountDownLatch(1)
        val continueSweep = CountDownLatch(1)
        val sweeper = OdbStaleSessionSweeper(root) {
            inspecting.countDown()
            assertTrue(continueSweep.await(5, TimeUnit.SECONDS))
            OdbProcessObservation.Missing
        }
        val executor = Executors.newSingleThreadExecutor()

        val sweep = executor.submit<OdbSweepReport> { sweeper.sweep() }
        assertTrue(inspecting.await(5, TimeUnit.SECONDS))
        assertSame(OdbCleanupResult.Cleaned, session.cleanup())
        continueSweep.countDown()
        val report = sweep.get(5, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertEquals(OdbSweepReport(0, 0, 0), report)
        assertFalse(Files.exists(session.directory))
    }

    @Test
    fun `macOS live orphan is preserved then removed after exit`() {
        assumeTrue(System.getProperty("os.name") == "Mac OS X")
        val process = ProcessBuilder("/bin/sleep", "2").start()
        try {
            val child = requireNotNull(JvmOdbProcessInspector.identify(process.toHandle()))
            val root = Files.createTempDirectory("odb-sweep-macos-root").toRealPath()
            val sessionId = "77777777-7777-7777-7777-777777777777"
            val session = Files.createDirectory(root.resolve("session-$sessionId"))
            Files.writeString(
                session.resolve("session.json"),
                metadata(sessionId, child.pid, Instant.EPOCH, child.pid, child.startedAt),
            )
            val sweeper = OdbStaleSessionSweeper(root)

            assertEquals(OdbSweepReport(0, 1, 0), sweeper.sweep())
            assertTrue(Files.exists(session))
            assertTrue("orphan process timed out", process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(OdbSweepReport(1, 0, 0), sweeper.sweep())
            assertFalse(Files.exists(session))
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun createSession(root: Path, sessionId: String, ownerPid: Long, childPid: Long): Path =
        Files.createDirectory(root.resolve("session-$sessionId")).also { session ->
            Files.writeString(
                session.resolve("session.json"),
                metadata(sessionId, ownerPid, OWNER_START, childPid, CHILD_START),
            )
        }

    private fun metadata(
        sessionId: String,
        ownerPid: Long,
        ownerStart: Instant,
        childPid: Long,
        childStart: Instant,
    ): String =
        """{"version":1,"sessionId":"$sessionId","ideOwner":{"pid":$ownerPid,"startedAt":"$ownerStart"},"odbChild":{"pid":$childPid,"startedAt":"$childStart"}}"""

    companion object {
        private val OWNER_START = Instant.parse("2026-07-20T12:00:00Z")
        private val CHILD_START = Instant.parse("2026-07-20T12:00:01Z")
    }
}
