package org.lewisodb.intellij.lifecycle

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.atomic.AtomicBoolean

class OdbRecoveryStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        recovery.runOnce()
    }

    companion object {
        private val log = Logger.getInstance(OdbRecoveryStartupActivity::class.java)
        private val recovery = OdbRecoveryGate {
            val report = OdbStaleSessionSweeper(OdbSessionPaths.managedRoot()).sweep()
            if (report.failed > 0) {
                log.warn("ODB startup recovery could not inspect or remove ${report.failed} session entries.")
            }
        }
    }
}

internal class OdbRecoveryGate(
    private val recover: () -> Unit,
) {
    private val started = AtomicBoolean()

    fun runOnce() {
        if (started.compareAndSet(false, true)) recover()
    }
}
