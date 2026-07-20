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

    companion object {
        private const val TOKEN = "0123456789abcdef0123456789abcdef"
    }
}
