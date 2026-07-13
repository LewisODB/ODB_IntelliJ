package org.lewisodb.intellij.execution

import com.intellij.execution.ExecutionException
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.JavaCommandLineState
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.openapi.project.Project
import org.lewisodb.intellij.launch.OdbLaunchPlan
import java.nio.file.Files
import java.nio.file.Path

open class OdbProgramRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == OdbExecutor.ID && profile is ApplicationConfiguration

    override fun doExecute(
        state: com.intellij.execution.configurations.RunProfileState,
        environment: ExecutionEnvironment,
    ): RunContentDescriptor? {
        val javaState = state as? JavaCommandLineState
            ?: throw ExecutionException("Run with ODB supports Java Application command lines only.")
        val probe = probePath()
        OdbLaunchPlan(probe).applyTo(javaState.javaParameters)
        return super.doExecute(javaState, environment)
    }

    override fun doExecute(
        project: Project,
        state: com.intellij.execution.configurations.RunProfileState,
        contentToReuse: RunContentDescriptor?,
        environment: ExecutionEnvironment,
    ): RunContentDescriptor {
        val result = state.execute(environment.executor, this)
            ?: throw ExecutionException("Run with ODB did not start a process.")
        onProcessStarted(environment.runnerSettings, result)
        return RunContentBuilder(result, environment).showRunContent(contentToReuse)
    }

    private fun probePath(): Path {
        val configured = System.getProperty(PROBE_PATH_PROPERTY)
            ?: throw ExecutionException("The Lewis ODB test probe is unavailable.")
        val path = Path.of(configured).toAbsolutePath().normalize()
        if (!Files.isRegularFile(path)) {
            throw ExecutionException("The Lewis ODB test probe is unavailable: $path")
        }
        return path
    }

    companion object {
        const val RUNNER_ID = "LewisOdbProgramRunner"
        const val PROBE_PATH_PROPERTY = "org.lewisodb.intellij.testProbe"
    }
}
