package org.lewisodb.intellij.lifecycle

import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import org.lewisodb.intellij.protocol.OdbSessionReporter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class OdbSessionOwner(
    private val childIdentityFactory: (ProcessHandler) -> OdbProcessIdentity? = { handler ->
        (handler as? BaseProcessHandler<*>)?.process?.toHandle()?.let(JvmOdbProcessInspector::identify)
    },
) : Disposable {
    private val sessions = ConcurrentHashMap<ProcessHandler, OdbSessionState>()
    private val disposed = AtomicBoolean()

    fun supervise(
        handler: ProcessHandler,
        session: OdbSessionState,
        reporter: OdbSessionReporter,
    ): ProcessHandler {
        sessions.putIfAbsent(handler, session)?.let { existing ->
            if (existing !== session) cleanup(session)
            return handler
        }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.STDERR) reporter.onStderr(event.text)
            }

            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                if (willBeDestroyed) reporter.onStopRequested()
            }

            override fun processTerminated(event: ProcessEvent) {
                reporter.onTerminated(event.exitCode)
                complete(handler)
            }

            override fun processNotStarted() {
                complete(handler)
            }
        })
        if (session.recordsProcessIdentity) {
            try {
                val childIdentity = childIdentityFactory(handler)
                if (childIdentity != null) {
                    session.recordChild(childIdentity)
                } else if (!handler.isProcessTerminated && !handler.isProcessTerminating) {
                    throw IllegalStateException("Could not identify the ODB child process.")
                }
            } catch (error: Exception) {
                requestStop(handler)
                throw error
            }
        }
        if (handler.isProcessTerminated) {
            reporter.onTerminated(handler.exitCode ?: UNKNOWN_EXIT_CODE)
            complete(handler)
        }
        if (disposed.get()) requestStop(handler)
        return handler
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        sessions.keys.forEach(::requestStop)
    }

    internal fun cleanupBeforeStart(session: OdbSessionState) {
        cleanup(session)
    }

    internal fun requestStop(handler: ProcessHandler) {
        if (!handler.isProcessTerminated && !handler.isProcessTerminating) {
            if (!handler.isStartNotified) handler.startNotify()
            handler.destroyProcess()
        }
    }

    private fun complete(handler: ProcessHandler) {
        val session = sessions.remove(handler) ?: return
        cleanup(session)
    }

    private fun cleanup(session: OdbSessionState) {
        val result = session.cleanup()
        if (result is OdbCleanupResult.Failed) {
            LOG.warn("Could not delete ODB session state at ${result.directory}.", result.cause)
        }
    }

    companion object {
        private const val UNKNOWN_EXIT_CODE = -1
        private val LOG = Logger.getInstance(OdbSessionOwner::class.java)
    }
}
