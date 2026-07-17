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
import org.lewisodb.intellij.launch.OdbPreflight
import java.nio.file.Files
import java.nio.file.Path

open class OdbProgramRunner internal constructor(
    private val preflight: OdbPreflight = OdbPreflight(),
) : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == OdbExecutor.ID && profile is ApplicationConfiguration

    override fun doExecute(
        state: com.intellij.execution.configurations.RunProfileState,
        environment: ExecutionEnvironment,
    ): RunContentDescriptor? {
        val parameters = preflight.resolve(environment.runProfile, state, environment.targetEnvironmentRequest)
        val javaState = state as JavaCommandLineState
        val probe = probePath()
        OdbLaunchPlan(probe).applyTo(parameters)
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
            ?: throw ExecutionException(RUNTIME_MISSING_MESSAGE)
        val path = try {
            Path.of(configured).toAbsolutePath().normalize()
        } catch (_: RuntimeException) {
            throw ExecutionException(RUNTIME_MISSING_MESSAGE)
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw ExecutionException(RUNTIME_MISSING_MESSAGE)
        }
        return path
    }

    companion object {
        const val RUNNER_ID = "LewisOdbProgramRunner"
        const val PROBE_PATH_PROPERTY = "org.lewisodb.intellij.testProbe"
        const val RUNTIME_MISSING_MESSAGE =
            "Run with ODB is incomplete: the bundled ODB runtime is missing or unreadable. Reinstall the plugin."
    }
}
