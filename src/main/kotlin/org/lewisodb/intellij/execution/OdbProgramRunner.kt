package org.lewisodb.intellij.execution

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.JavaCommandLineState
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import org.lewisodb.intellij.lifecycle.OdbSessionOwner
import org.lewisodb.intellij.lifecycle.OdbSessionPaths
import org.lewisodb.intellij.launch.OdbLaunchPlan
import org.lewisodb.intellij.launch.OdbPreflight
import org.lewisodb.intellij.launch.OdbSourceRoots
import org.lewisodb.intellij.protocol.OdbSessionReporter
import org.lewisodb.intellij.runtime.ClasspathOdbRuntimeBundle
import org.lewisodb.intellij.runtime.FileOdbRuntimeBundle
import org.lewisodb.intellij.runtime.OdbPreparedRuntime
import org.lewisodb.intellij.runtime.OdbRuntimeBundle
import org.lewisodb.intellij.runtime.OdbRuntimeException
import org.lewisodb.intellij.runtime.OdbRuntimeExtractor
import java.nio.file.Path

open class OdbProgramRunner internal constructor(
    private val preflight: OdbPreflight = OdbPreflight(),
    private val prepareRuntime: () -> OdbPreparedRuntime = ::prepareDefaultRuntime,
    private val sessionOwner: (Project) -> OdbSessionOwner = { it.service() },
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
        val runtime = try {
            prepareRuntime()
        } catch (error: OdbRuntimeException) {
            throw ExecutionException(RUNTIME_MISSING_MESSAGE, error)
        }
        environment.putUserData(PREPARED_RUNTIME_KEY, runtime)
        return try {
            OdbSourceRoots.write(environment.runProfile as ApplicationConfiguration, runtime.sessionDirectory)
            OdbLaunchPlan(runtime.runtimeJar, runtime.sessionDirectory, runtime.token).applyTo(parameters)
            super.doExecute(javaState, environment)
        } catch (error: Throwable) {
            environment.getUserData(PREPARED_RUNTIME_KEY)?.let { prepared ->
                sessionOwner(environment.project).cleanupBeforeStart(prepared.session)
                environment.putUserData(PREPARED_RUNTIME_KEY, null)
            }
            throw error
        }
    }

    override fun doExecute(
        project: Project,
        state: com.intellij.execution.configurations.RunProfileState,
        contentToReuse: RunContentDescriptor?,
        environment: ExecutionEnvironment,
    ): RunContentDescriptor {
        val result = state.execute(environment.executor, this)
            ?: throw ExecutionException("Run with ODB did not start a process.")
        val owner = observeSession(project, environment, result)
        return try {
            onProcessStarted(environment.runnerSettings, result)
            RunContentBuilder(result, environment).showRunContent(contentToReuse)
        } catch (error: Throwable) {
            owner.requestStop(result.processHandler)
            throw error
        }
    }

    private fun observeSession(
        project: Project,
        environment: ExecutionEnvironment,
        result: ExecutionResult,
    ): OdbSessionOwner {
        val prepared = environment.getUserData(PREPARED_RUNTIME_KEY)
            ?: throw ExecutionException("Run with ODB lost its prepared runtime state.")
        val console = result.executionConsole as? ConsoleView
        val reporter = OdbSessionReporter(
            prepared.token,
            printStatus = { console?.print(it, ConsoleViewContentType.SYSTEM_OUTPUT) },
            reportFailure = { message ->
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification("Run with ODB failed", message, NotificationType.ERROR)
                    .notify(project)
            },
        )
        val owner = sessionOwner(project)
        environment.putUserData(PREPARED_RUNTIME_KEY, null)
        try {
            owner.supervise(result.processHandler, prepared.session, reporter)
        } catch (error: Exception) {
            throw ExecutionException("Run with ODB could not record its child process identity.", error)
        }
        console?.print("Bundled ODB runtime prepared.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        return owner
    }

    companion object {
        const val RUNNER_ID = "LewisOdbProgramRunner"
        const val RUNTIME_PATH_PROPERTY = "org.lewisodb.intellij.runtime"
        const val RUNTIME_MANIFEST_PATH_PROPERTY = "org.lewisodb.intellij.runtimeManifest"
        const val RUNTIME_MISSING_MESSAGE =
            "Run with ODB is incomplete: the bundled ODB runtime is missing or unreadable. Reinstall the plugin."
        private const val NOTIFICATION_GROUP_ID = "Lewis ODB"
        private val PREPARED_RUNTIME_KEY = Key.create<OdbPreparedRuntime>("lewis.odb.prepared.runtime")

        private fun prepareDefaultRuntime(): OdbPreparedRuntime {
            return OdbRuntimeExtractor(OdbSessionPaths.managedRoot(), defaultBundle()).prepare()
        }

        private fun defaultBundle(): OdbRuntimeBundle {
            val runtime = System.getProperty(RUNTIME_PATH_PROPERTY)
            val manifest = System.getProperty(RUNTIME_MANIFEST_PATH_PROPERTY)
            return when {
                runtime == null && manifest == null -> ClasspathOdbRuntimeBundle()
                runtime != null && manifest != null -> FileOdbRuntimeBundle(Path.of(manifest), Path.of(runtime))
                else -> throw OdbRuntimeException("ODB runtime and manifest overrides must be provided together.")
            }
        }
    }
}
