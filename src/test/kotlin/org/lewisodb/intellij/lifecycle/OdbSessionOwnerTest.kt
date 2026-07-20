package org.lewisodb.intellij.lifecycle

import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lewisodb.intellij.protocol.OdbSessionReporter
import java.nio.file.Files

class OdbSessionOwnerTest {
    @Test
    fun `disposal destroys the delegated handler and termination cleans once`() {
        val root = Files.createTempDirectory("odb-owner-root").toRealPath()
        val directory = Files.createTempDirectory(root, "session-").toRealPath()
        val session = OdbSessionState.create(root, directory)
        val handler = NopProcessHandler()
        val destroyFlags = mutableListOf<Boolean>()
        handler.addProcessListener(object : ProcessListener {
            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                destroyFlags += willBeDestroyed
            }
        })
        handler.startNotify()
        val owner = OdbSessionOwner()
        val reporter = OdbSessionReporter(TOKEN, {}, {})

        val supervised = owner.supervise(handler, session, reporter)
        owner.dispose()
        owner.dispose()

        assertSame(handler, supervised)
        assertEquals(listOf(true), destroyFlags)
        assertTrue(handler.isProcessTerminated)
        assertFalse(Files.exists(directory))
        assertSame(OdbCleanupResult.Cleaned, session.cleanup())
    }

    @Test
    fun `session registered during disposal is stopped and cleaned`() {
        val root = Files.createTempDirectory("odb-owner-race-root").toRealPath()
        val directory = Files.createTempDirectory(root, "session-").toRealPath()
        val session = OdbSessionState.create(root, directory)
        val handler = NopProcessHandler()
        val owner = OdbSessionOwner()

        owner.dispose()
        owner.supervise(handler, session, OdbSessionReporter(TOKEN, {}, {}))

        assertTrue(handler.isProcessTerminated)
        assertFalse(Files.exists(directory))
    }

    @Test
    fun `concurrent handlers retain separate lifecycle ownership`() {
        val root = Files.createTempDirectory("odb-owner-concurrent-root").toRealPath()
        val firstDirectory = Files.createTempDirectory(root, "session-").toRealPath()
        val secondDirectory = Files.createTempDirectory(root, "session-").toRealPath()
        val first = NopProcessHandler().also { it.startNotify() }
        val second = NopProcessHandler().also { it.startNotify() }
        val owner = OdbSessionOwner()

        owner.supervise(first, OdbSessionState.create(root, firstDirectory), OdbSessionReporter(TOKEN, {}, {}))
        owner.supervise(second, OdbSessionState.create(root, secondDirectory), OdbSessionReporter(TOKEN, {}, {}))
        first.destroyProcess()

        assertFalse(Files.exists(firstDirectory))
        assertTrue(Files.exists(secondDirectory))
        assertFalse(second.isProcessTerminated)

        second.destroyProcess()
        assertFalse(Files.exists(secondDirectory))
    }

    @Test
    fun `process that terminates before identity capture cleans without failure`() {
        val root = Files.createTempDirectory("odb-owner-fast-exit-root").toRealPath()
        val session = OdbSessionState.createManaged(
            root,
            OdbProcessIdentity(101, java.time.Instant.parse("2026-07-20T12:00:00Z")),
        )
        val handler = NopProcessHandler().also {
            it.startNotify()
            it.destroyProcess()
        }
        val owner = OdbSessionOwner { null }

        owner.supervise(handler, session, OdbSessionReporter(TOKEN, {}, {}))

        assertFalse(Files.exists(session.directory))
    }

    companion object {
        private const val TOKEN = "0123456789abcdef0123456789abcdef"
    }
}
