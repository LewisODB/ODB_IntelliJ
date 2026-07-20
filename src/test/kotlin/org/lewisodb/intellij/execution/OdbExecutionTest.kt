package org.lewisodb.intellij.execution

import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.JavaCommandLineState
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.lewisodb.intellij.launch.OdbLaunchPlan
import org.lewisodb.intellij.launch.OdbPreflight
import org.lewisodb.intellij.lifecycle.OdbSessionOwner
import org.lewisodb.intellij.runtime.FileOdbRuntimeBundle
import org.lewisodb.intellij.runtime.OdbPreparedRuntime
import org.lewisodb.intellij.runtime.OdbRuntimeExtractor
import java.nio.file.Path
import java.nio.file.Files

class OdbExecutionTest : BasePlatformTestCase() {
    fun testExecutorAndRunnerLoadFromPluginDescriptor() {
        val executor = requireNotNull(ExecutorRegistry.getInstance().getExecutorById(OdbExecutor.ID))
        assertTrue(executor is OdbExecutor)
        assertEquals("Run with ODB", executor.actionName)

        val application = ApplicationConfiguration("fixture", project)
        assertTrue(ProgramRunner.getRunner(OdbExecutor.ID, application) is OdbProgramRunner)
    }

    fun testRunnerAcceptsOnlyOdbJavaApplications() {
        val runner = OdbProgramRunner()
        val application = ApplicationConfiguration("fixture", project)

        assertTrue(runner.canRun(OdbExecutor.ID, application))
        assertFalse(runner.canRun("Run", application))
        assertFalse(runner.canRun(OdbExecutor.ID, object : RunProfile {
            override fun getName() = "unsupported"
            override fun getIcon() = null
            override fun getState(executor: com.intellij.execution.Executor, environment: com.intellij.execution.runners.ExecutionEnvironment) = null
        }))
    }

    fun testUiFixtureLoadsRedirectedInput() {
        val xml = JDOMUtil.load(Path.of("src/test/fixtures/ide-project/.idea/runConfigurations/ODB_Fixture.xml"))
        val fixture = requireNotNull(xml.getChild("configuration"))
        val configuration = ApplicationConfiguration("fixture", project)
        configuration.readExternal(fixture)

        assertTrue(configuration.inputRedirectOptions.isRedirectInput)
        assertEquals("\$PROJECT_DIR\$/input.txt", configuration.inputRedirectOptions.redirectInputPath)
        val make = fixture.getChild("method").getChildren("option").single { it.getAttributeValue("name") == "Make" }
        assertEquals("true", make.getAttributeValue("enabled"))

        val module = JDOMUtil.load(Path.of("src/test/fixtures/ide-project/odb-fixture.iml"))
            .getChild("component")
        assertEquals("false", module.getAttributeValue("inherit-compiler-output"))
        assertEquals(
            "file://\$MODULE_DIR\$/out/production/odb-fixture",
            module.getChild("output").getAttributeValue("url"),
        )
    }

    fun testLaunchPlanPreservesInputsAndDoesNotSerializeChanges() {
        val configuration = ApplicationConfiguration("fixture", project).apply {
            mainClassName = "org.lewisodb.fixture.FixtureMain"
            programParameters = "one \"two words\""
            vmParameters = "-Dfixture.property=kept"
            workingDirectory = "/tmp/fixture work"
            envs = mapOf("FIXTURE_ENV" to "kept")
        }
        val before = serialize(configuration)
        val parameters = JavaParameters().apply {
            mainClass = configuration.mainClassName
            programParametersList.addAll("one", "two words")
            vmParametersList.add("-Dfixture.property=kept")
            env = configuration.envs
            workingDirectory = configuration.workingDirectory
            classPath.add("/target/classes")
        }

        OdbLaunchPlan(Path.of("/probe/odb-probe.jar"), Path.of("/managed/session"), TOKEN).applyTo(parameters)

        assertEquals("com.lambda.Debugger.IntegrationLauncher", parameters.mainClass)
        assertArrayEquals(
            arrayOf("org.lewisodb.fixture.FixtureMain", "one", "two words"),
            parameters.programParametersList.array,
        )
        assertEquals(listOf("/probe/odb-probe.jar", "/target/classes"), parameters.classPath.pathList)
        assertEquals("kept", parameters.vmParametersList.getPropertyValue("fixture.property"))
        assertEquals(mapOf("FIXTURE_ENV" to "kept"), parameters.env)
        assertEquals("/tmp/fixture work", parameters.workingDirectory)
        assertEquals("/managed/session", parameters.vmParametersList.getPropertyValue(OdbLaunchPlan.STATE_DIRECTORY_PROPERTY))
        assertEquals(TOKEN, parameters.vmParametersList.getPropertyValue(OdbLaunchPlan.TOKEN_PROPERTY))
        assertEquals(before, serialize(configuration))
    }

    fun testFiniteJava8ProbePreservesResolvedLaunchAndOutput() {
        val workingDirectory = Files.createTempDirectory("odb fixture ")
        val adapterJar = Path.of(System.getProperty(OdbProgramRunner.PROBE_PATH_PROPERTY))
        val applicationJar = Path.of(System.getProperty("org.lewisodb.intellij.testApplication"))
        val jdk8 = System.getProperty("org.lewisodb.intellij.testJdk8")
        val parameters = JavaParameters().apply {
            jdk = JavaSdk.getInstance().createJdk("odb-test-jdk8", jdk8, false)
            mainClass = "org.lewisodb.fixture.FixtureMain"
            classPath.add(applicationJar.toString())
            programParametersList.addAll("one", "two words", "السلام", "--read-stdin")
            vmParametersList.add("-Dfixture.property=kept")
            env = mapOf("FIXTURE_ENV" to "kept")
            this.workingDirectory = workingDirectory.toString()
        }

        val session = Files.createTempDirectory("odb-probe-session")
        OdbLaunchPlan(adapterJar, session, TOKEN).applyTo(parameters)
        val stdin = Files.createTempFile("odb-stdin", ".txt").also { Files.writeString(it, "from-stdin\n") }
        val commandLine = parameters.toCommandLine().withInput(stdin.toFile())
        val output = CapturingProcessHandler(commandLine).runProcess(10_000)

        assertEquals(0, output.exitCode)
        assertTrue(output.stdout.contains("property=kept"))
        assertTrue(output.stdout, output.stdout.contains("java=1.8."))
        assertTrue(output.stdout.contains("env=kept"))
        assertTrue(output.stdout, output.stdout.contains("cwd=${workingDirectory.toRealPath()}"))
        assertTrue(output.stdout.contains("arg0=one"))
        assertTrue(output.stdout.contains("arg1=two words"))
        assertTrue(output.stdout.contains("arg2=السلام"))
        assertTrue(output.stdout.contains("stdin=from-stdin"))
        assertTrue(output.stdout.contains("integration-token-cleared=true"))
        assertTrue(output.stdout.contains("integration-state-cleared=true"))
        assertTrue(output.stderr.contains("fixture-stderr"))
        assertEquals(listOf(adapterJar.toString(), applicationJar.toString()), parameters.classPath.pathList)
    }

    fun testProbeEventsUseCapturedStderrAfterTargetReplacesSystemErr() {
        val adapterJar = Path.of(System.getProperty(OdbProgramRunner.PROBE_PATH_PROPERTY))
        val applicationJar = Path.of(System.getProperty("org.lewisodb.intellij.testApplication"))
        val session = Files.createTempDirectory("odb-probe-stderr-session")
        val parameters = JavaParameters().apply {
            jdk = JavaSdk.getInstance().createJdk(
                "odb-stderr-jdk8",
                System.getProperty("org.lewisodb.intellij.testJdk8"),
                false,
            )
            mainClass = "org.lewisodb.fixture.FixtureMain"
            classPath.add(applicationJar.toString())
            programParametersList.add("--replace-stderr")
        }
        OdbLaunchPlan(adapterJar, session, TOKEN).applyTo(parameters)

        val output = CapturingProcessHandler(parameters.toCommandLine()).runProcess(10_000)

        assertEquals(0, output.exitCode)
        assertTrue(output.stderr, output.stderr.contains("\"type\":\"recording-started\""))
        assertTrue(output.stderr, output.stderr.contains("\"type\":\"debugger-ready\""))
    }

    fun testRealJavaApplicationDelegatesThroughIntellijRunnerAndConsole() {
        val jdk8 = System.getProperty("org.lewisodb.intellij.testJdk8")
        val applicationJar = Path.of(System.getProperty("org.lewisodb.intellij.testApplication"))
        val workingDirectory = Files.createTempDirectory("odb real fixture ")
        val stdin = Files.createTempFile("odb-real-stdin", ".txt").also { Files.writeString(it, "from-stdin\n") }
        val sdk = JavaSdk.getInstance().createJdk("odb-real-jdk8", jdk8, false)
        WriteAction.run<RuntimeException> {
            ProjectJdkTable.getInstance().addJdk(sdk, testRootDisposable)
        }
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        PsiTestUtil.addLibrary(module, "odb-fixture", applicationJar.parent.toString(), applicationJar.fileName.toString())

        val configuration = ApplicationConfiguration("fixture", project).apply {
            setModule(module)
            mainClassName = "org.lewisodb.fixture.FixtureMain"
            programParameters = "one \"two words\" --read-stdin"
            vmParameters = "-Dfixture.property=kept"
            this.workingDirectory = workingDirectory.toString()
            envs = mapOf("FIXTURE_ENV" to "kept")
            inputRedirectOptions.isRedirectInput = true
            inputRedirectOptions.redirectInputPath = stdin.toString()
        }
        val before = serialize(configuration)
        val executor = OdbExecutor()
        val root = Files.createTempDirectory("odb-success-root").toRealPath()
        lateinit var prepared: OdbPreparedRuntime
        val runner = TestableOdbProgramRunner(
            prepareRuntime = { preparedRuntime(root).also { prepared = it } },
        )
        val environment = ExecutionEnvironmentBuilder(project, executor)
            .runProfile(configuration)
            .runner(runner)
            .build()
        val state = configuration.getState(executor, environment)
        assertTrue(state is JavaCommandLineState)

        val descriptor = runner.executeState(requireNotNull(state), environment)
        Disposer.register(testRootDisposable, descriptor)
        val handler = requireNotNull(descriptor.processHandler)
        val console = descriptor.executionConsole as ConsoleViewImpl
        console.component
        handler.startNotify()
        assertTrue("probe timed out", handler.waitFor(10_000))
        console.waitAllRequests()

        assertEquals(0, handler.exitCode)
        assertTrue(console.text, console.text.contains("property=kept"))
        assertTrue(console.text, console.text.contains("env=kept"))
        assertTrue(console.text, console.text.contains("arg1=two words"))
        assertTrue(console.text, console.text.contains("stdin=from-stdin"))
        assertTrue(console.text, console.text.contains("fixture-stderr"))
        assertTrue(console.text, console.text.contains("Bundled ODB runtime prepared."))
        assertTrue(console.text, console.text.contains("Loading org.lewisodb.fixture.FixtureMain with ODB..."))
        assertTrue(console.text, console.text.contains("ODB recording started."))
        assertTrue(console.text, console.text.contains("ODB debugger ready."))
        assertFalse(Files.exists(prepared.sessionDirectory))
        assertEquals(before, serialize(configuration))

        val normalExecutor = DefaultRunExecutor.getRunExecutorInstance()
        val normalRunner = requireNotNull(ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration))
        val normalEnvironment = ExecutionEnvironmentBuilder(project, normalExecutor)
            .runProfile(configuration)
            .runner(normalRunner)
            .build()
        val normalState = configuration.getState(normalExecutor, normalEnvironment) as JavaCommandLineState
        val normalParameters = normalState.javaParameters
        assertEquals("org.lewisodb.fixture.FixtureMain", normalParameters.mainClass)
        assertFalse(normalParameters.classPath.pathList.contains(System.getProperty(OdbProgramRunner.PROBE_PATH_PROPERTY)))
        assertArrayEquals(
            arrayOf("one", "two words", "--read-stdin"),
            normalParameters.programParametersList.array,
        )
    }

    fun testStandardStopKeepsDelegatedProcessAndCleansStateAfterTermination() {
        configureLifecycleFixture("odb-stop-jdk8", "odb-stop-fixture")
        val root = Files.createTempDirectory("odb-stop-root").toRealPath()
        lateinit var prepared: OdbPreparedRuntime
        val runner = TestableOdbProgramRunner(
            prepareRuntime = { preparedRuntime(root).also { prepared = it } },
        )
        val running = startLifecycleProbe("wait-for-stop", runner)
        waitUntil(10_000) {
            running.console.waitAllRequests()
            running.console.text.contains("ODB debugger ready.")
        }

        assertSame(runner.delegatedHandler, running.handler)
        assertFalse(running.handler.isProcessTerminated)
        assertTrue(Files.exists(prepared.sessionDirectory))

        running.handler.destroyProcess()

        assertTrue("Stop timed out\n${running.console.text}", running.handler.waitFor(10_000))
        assertFalse(Files.exists(prepared.sessionDirectory))
    }

    fun testCrashAndFatalExitCleanStateAfterTermination() {
        configureLifecycleFixture("odb-exit-jdk8", "odb-exit-fixture")

        listOf("crash" to 7, "fatal" to 1).forEach { (mode, expectedExit) ->
            val root = Files.createTempDirectory("odb-$mode-root").toRealPath()
            lateinit var prepared: OdbPreparedRuntime
            val runner = TestableOdbProgramRunner(
                prepareRuntime = { preparedRuntime(root).also { prepared = it } },
            )
            val running = startLifecycleProbe(mode, runner)

            assertTrue("$mode timed out\n${running.console.text}", running.handler.waitFor(10_000))
            running.console.waitAllRequests()

            assertEquals(expectedExit, running.handler.exitCode)
            assertFalse(Files.exists(prepared.sessionDirectory))
        }
    }

    fun testDisposalRequestsStopWithoutWaitingAndCleansAfterTermination() {
        configureLifecycleFixture("odb-disposal-jdk8", "odb-disposal-fixture")
        val root = Files.createTempDirectory("odb-disposal-root").toRealPath()
        lateinit var prepared: OdbPreparedRuntime
        val owner = OdbSessionOwner()
        val runner = TestableOdbProgramRunner(
            prepareRuntime = { preparedRuntime(root).also { prepared = it } },
            owner = owner,
        )
        val running = startLifecycleProbe("wait-for-stop", runner)
        waitUntil(10_000) {
            running.console.waitAllRequests()
            running.console.text.contains("ODB debugger ready.")
        }

        owner.dispose()
        owner.dispose()

        assertTrue("disposal timed out\n${running.console.text}", running.handler.waitFor(10_000))
        assertFalse(Files.exists(prepared.sessionDirectory))
    }

    fun testProcessStartFailureRequestsStopAndCleansAfterTermination() {
        configureLifecycleFixture("odb-start-failure-jdk8", "odb-start-failure-fixture")
        val root = Files.createTempDirectory("odb-start-failure-root").toRealPath()
        lateinit var prepared: OdbPreparedRuntime
        val runner = TestableOdbProgramRunner(
            prepareRuntime = { preparedRuntime(root).also { prepared = it } },
            failOnProcessStarted = true,
        )

        val error = runCatching { startLifecycleProbe("wait-for-stop", runner) }.exceptionOrNull()
        val handler = requireNotNull(runner.delegatedHandler)

        assertTrue(error is AssertionError)
        assertEquals("simulated process-start failure", error?.message)
        assertTrue("failed start did not stop", handler.waitFor(10_000))
        assertFalse(Files.exists(prepared.sessionDirectory))
    }

    private data class RunningProbe(
        val handler: ProcessHandler,
        val console: ConsoleViewImpl,
    )

    private fun configureLifecycleFixture(sdkName: String, libraryName: String) {
        val applicationJar = Path.of(System.getProperty("org.lewisodb.intellij.testApplication"))
        val sdk = JavaSdk.getInstance().createJdk(
            sdkName,
            System.getProperty("org.lewisodb.intellij.testJdk8"),
            false,
        )
        WriteAction.run<RuntimeException> {
            ProjectJdkTable.getInstance().addJdk(sdk, testRootDisposable)
        }
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        PsiTestUtil.addLibrary(module, libraryName, applicationJar.parent.toString(), applicationJar.fileName.toString())
    }

    private fun startLifecycleProbe(mode: String, runner: TestableOdbProgramRunner): RunningProbe {
        val configuration = ApplicationConfiguration("ODB $mode", project).apply {
            setModule(module)
            mainClassName = "org.lewisodb.fixture.FixtureMain"
            programParameters = "--odb-probe-mode=$mode"
            workingDirectory = Files.createTempDirectory("odb-$mode-work").toString()
        }
        val executor = OdbExecutor()
        val environment = ExecutionEnvironmentBuilder(project, executor)
            .runProfile(configuration)
            .runner(runner)
            .build()
        val state = requireNotNull(configuration.getState(executor, environment))
        val descriptor = runner.executeState(state, environment)
        Disposer.register(testRootDisposable, descriptor)
        val handler = requireNotNull(descriptor.processHandler)
        val console = descriptor.executionConsole as ConsoleViewImpl
        console.component
        handler.startNotify()
        return RunningProbe(handler, console)
    }

    private fun preparedRuntime(root: Path): OdbPreparedRuntime {
        val runtime = Path.of(System.getProperty(OdbProgramRunner.PROBE_PATH_PROPERTY))
        val manifest = Path.of(System.getProperty(OdbProgramRunner.PROBE_MANIFEST_PATH_PROPERTY))
        return OdbRuntimeExtractor(root, FileOdbRuntimeBundle(manifest, runtime)).prepare()
    }

    private class TestableOdbProgramRunner(
        prepareRuntime: (() -> OdbPreparedRuntime)? = null,
        owner: OdbSessionOwner? = null,
        private val failOnProcessStarted: Boolean = false,
    ) : OdbProgramRunner(
        OdbPreflight(isIntelliJ = true, productName = "IntelliJ IDEA", desktopAvailable = { true }),
        prepareRuntime ?: { defaultPreparedRuntime() },
        { project -> owner ?: project.getService(OdbSessionOwner::class.java) },
    ) {
        var delegatedHandler: ProcessHandler? = null

        override fun onProcessStarted(settings: RunnerSettings?, result: ExecutionResult) {
            delegatedHandler = result.processHandler
            if (failOnProcessStarted) throw AssertionError("simulated process-start failure")
            super.onProcessStarted(settings, result)
        }

        fun executeState(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor =
            requireNotNull(doExecute(state, environment))

        companion object {
            private fun defaultPreparedRuntime(): OdbPreparedRuntime {
                val runtime = Path.of(System.getProperty(OdbProgramRunner.PROBE_PATH_PROPERTY))
                val manifest = Path.of(System.getProperty(OdbProgramRunner.PROBE_MANIFEST_PATH_PROPERTY))
                val root = Files.createTempDirectory("odb-test-runner-root").toRealPath()
                return OdbRuntimeExtractor(root, FileOdbRuntimeBundle(manifest, runtime)).prepare()
            }
        }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() >= deadline) throw AssertionError("Timed out waiting for process output")
            Thread.sleep(25)
        }
    }

    private fun serialize(configuration: ApplicationConfiguration): String =
        JDOMUtil.writeElement(Element("configuration").also(configuration::writeExternal))

    companion object {
        private const val TOKEN = "0123456789abcdef0123456789abcdef"
    }
}
