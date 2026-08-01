package org.lewisodb.intellij.launch

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.JavaCommandLineState
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.target.TargetEnvironmentRequest
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.JavaSdkType
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.lewisodb.intellij.execution.OdbExecutor
import org.lewisodb.intellij.execution.OdbProgramRunner
import org.lewisodb.intellij.lifecycle.OdbSessionState
import org.lewisodb.intellij.runtime.OdbPreparedRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Proxy

class OdbPreflightTest : BasePlatformTestCase() {
    private lateinit var jdk8: Sdk

    override fun setUp() {
        super.setUp()
        VfsRootAccess.allowRootAccess(testRootDisposable, System.getProperty("org.lewisodb.intellij.testJdk8"))
        jdk8 = JavaSdk.getInstance().createJdk(
            "odb-preflight-jdk8",
            System.getProperty("org.lewisodb.intellij.testJdk8"),
            false,
        )
        WriteAction.run<RuntimeException> {
            ProjectJdkTable.getInstance().addJdk(jdk8, testRootDisposable)
        }
        ModuleRootModificationUtil.setModuleSdk(module, jdk8)
    }

    fun testAcceptsResolvedLocalJdk8ClasspathApplicationWithConventionalMain() {
        val launch = launch()

        assertSame(launch.parameters, supportedPreflight().resolve(launch.profile, launch.state, null))
    }

    fun testRejectsWrongProductFirst() {
        val launch = launch(parameters = JavaParameters())
        val error = failure {
            OdbPreflight(isIntelliJ = false, productName = "Android Studio", desktopAvailable = { false })
                .resolve(launch.profile, launch.state, null)
        }

        assertEquals(
            "Run with ODB version 1 supports IntelliJ IDEA only. Current product: Android Studio.",
            error.message,
        )
    }

    fun testRejectsUnsupportedProfileType() {
        val profile = UnsupportedProfile()
        val environment = environment(profile)
        val state = StaticJavaState(environment, JavaParameters())

        val error = failure {
            supportedPreflight().resolve(profile, state, null)
        }

        assertEquals(
            "Run with ODB supports Java Application configurations only. Selected: UnsupportedProfile.",
            error.message,
        )
    }

    fun testRejectsNonJavaAndRemoteStatesWithOneLocalProcessMessage() {
        val profile = application()
        val nonJavaState = RunProfileState { _, _ -> null }
        assertEquals(LOCAL_ONLY_MESSAGE, rejection(profile, nonJavaState))

        val remote = launch(application().apply { defaultTargetName = "docker" })
        assertEquals(LOCAL_ONLY_MESSAGE, rejection(remote))

        val customProfile = application()
        val parameters = supportedParameters()
        val environment = environment(customProfile)
        val request = nonLocalTargetRequest()
        val customTargetState = object : StaticJavaState(environment, parameters) {
            override fun createCustomTargetEnvironmentRequest(): TargetEnvironmentRequest = request
        }
        assertEquals(LOCAL_ONLY_MESSAGE, rejection(customProfile, customTargetState))
        assertEquals(LOCAL_ONLY_MESSAGE, rejection(customProfile, customTargetState, targetRequest = request))

        val brokenTargetState = object : StaticJavaState(environment, parameters) {
            override fun createCustomTargetEnvironmentRequest(): TargetEnvironmentRequest =
                throw IllegalStateException("target unavailable")
        }
        assertEquals(LOCAL_ONLY_MESSAGE, rejection(customProfile, brokenTargetState))
    }

    fun testRejectsMissingJdkBeforeLaterFailures() {
        val parameters = JavaParameters().apply {
            moduleName = "sample.module"
            mainClass = null
        }
        val launch = launch(parameters = parameters)

        assertEquals(
            "Run with ODB cannot start because this configuration has no resolved JDK.",
            rejection(launch),
        )
    }

    fun testRejectsMissingLocalJavaExecutable() {
        val missingHome = Path.of(project.basePath ?: ".", "missing-jdk").toAbsolutePath().toString()
        Files.createDirectories(Path.of(missingHome))
        val sdk = JavaSdk.getInstance().createJdk("missing-jdk", missingHome, false)
        val launch = launch(parameters = supportedParameters().apply { jdk = sdk })

        val executable = (sdk.sdkType as JavaSdkType).getVMExecutablePath(sdk)
        assertEquals(
            "Run with ODB cannot use the resolved Java executable: $executable. Select a local JDK 8.",
            rejection(launch),
        )
    }

    fun testRejectsNonLocalJdkWithExecutableMessage() {
        val remoteHome = "wsl://Ubuntu/usr/lib/jvm/java-8"
        val sdk = Proxy.newProxyInstance(
            Sdk::class.java.classLoader,
            arrayOf(Sdk::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getName" -> "remote-jdk8"
                "getSdkType" -> JavaSdk.getInstance()
                "getVersionString" -> "1.8"
                "getHomePath" -> remoteHome
                "getHomeDirectory" -> LightVirtualFile("remote-jdk")
                "getUserData" -> null
                "putUserData" -> null
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "remote-jdk8"
                else -> null
            }
        } as Sdk
        val parameters = supportedParameters().apply { jdk = sdk }
        val executable = (sdk.sdkType as JavaSdkType).getVMExecutablePath(sdk)
        val profile = application()
        val state = object : StaticJavaState(environment(profile), parameters) {
            override fun createCustomTargetEnvironmentRequest(): TargetEnvironmentRequest? = null
        }

        assertEquals(
            "Run with ODB cannot use the resolved Java executable: $executable. Select a local JDK 8.",
            rejection(profile, state),
        )
    }

    fun testRejectsNonJdk8RuntimeWithoutBlamingIdeRuntime() {
        val sdk = JavaSdk.getInstance().createJdk("unsupported-jdk", System.getProperty("java.home"), false)
        val version = JavaSdk.getInstance().getVersion(sdk)?.description ?: sdk.versionString ?: "unknown"
        val home = requireNotNull(sdk.homePath)
        val launch = launch(parameters = supportedParameters().apply { jdk = sdk })

        assertEquals(
            "Run with ODB version 1 supports target JDK 8 only. Selected: $version ($home). IntelliJ's own runtime is unrelated.",
            rejection(launch),
        )
    }

    fun testRejectsModulePathLaunch() {
        val parameters = supportedParameters().apply { modulePath.add("/tmp/sample-module") }

        assertEquals(
            "Run with ODB version 1 does not support Java module-path applications. Use a classpath-based Java Application configuration.",
            rejection(launch(parameters = parameters)),
        )

        parameters.modulePath.clear()
        parameters.moduleName = "sample.module"
        assertEquals(
            "Run with ODB version 1 does not support Java module-path applications. Use a classpath-based Java Application configuration.",
            rejection(launch(parameters = parameters)),
        )
    }

    fun testRejectsAbsentAndUnresolvedMainClass() {
        assertEquals(
            "Run with ODB cannot resolve the selected application's main class.",
            rejection(launch(parameters = supportedParameters().apply { mainClass = null })),
        )

        val profile = application(mainClass = "sample.Missing")
        val parameters = supportedParameters().apply { mainClass = "sample.Missing" }

        assertEquals(
            "Run with ODB cannot resolve the selected application's main class.",
            rejection(launch(profile, parameters)),
        )
    }

    fun testRejectsUnconventionalMainSignature() {
        addClass("sample.InvalidMain", "package sample; public class InvalidMain { public void main(String[] args) {} }")
        val profile = application(mainClass = "sample.InvalidMain")
        val parameters = supportedParameters().apply { mainClass = "sample.InvalidMain" }

        assertEquals(
            "Run with ODB requires public static void main(String[]). Selected: sample.InvalidMain.",
            rejection(launch(profile, parameters)),
        )

        val implicit = application().apply { isImplicitClassConfiguration = true }
        assertEquals(
            "Run with ODB requires public static void main(String[]). Selected: sample.Main.",
            rejection(launch(implicit, supportedParameters())),
        )
    }

    fun testRejectsUnavailableDesktopGraphicsLast() {
        val launch = launch()

        assertEquals(
            "Run with ODB requires a local desktop graphics environment for its Swing debugger.",
            rejection(launch, desktopAvailable = false),
        )
    }

    fun testNormalizesRealIntellijResolutionFailureForMissingJdk() {
        val profile = ApplicationConfiguration("missing jdk", project).apply {
            isAlternativeJrePathEnabled = true
            alternativeJrePath = "/missing/jdk"
            mainClassName = "sample.Main"
        }
        val environment = environment(profile)
        val state = requireNotNull(profile.getState(OdbExecutor(), environment))

        assertEquals(
            "Run with ODB cannot start because this configuration has no resolved JDK.",
            rejection(profile, state),
        )
    }

    fun testNormalizesRealIntellijResolutionFailureForUnresolvedMain() {
        val profile = application(mainClass = "sample.DoesNotExist")
        val environment = environment(profile)
        val state = requireNotNull(profile.getState(OdbExecutor(), environment))

        assertEquals(
            "Run with ODB cannot resolve the selected application's main class.",
            rejection(profile, state),
        )
    }

    fun testRunnerRejectsBeforeMutationOrStateExecution() {
        val parameters = JavaParameters().apply { mainClass = "sample.Main" }
        val launch = launch(parameters = parameters)
        val runner = TestableRunner(supportedPreflight())

        val error = failure {
            runner.executeState(launch.state, launch.environment)
        }

        assertEquals("Run with ODB cannot start because this configuration has no resolved JDK.", error.message)
        assertEquals(0, launch.state.executionCount)
        assertEquals(1, launch.state.parameterResolutionCount)
        assertEquals("sample.Main", parameters.mainClass)
        assertEquals(emptyList<String>(), parameters.classPath.pathList)
    }

    fun testRunnerRejectsMissingRuntimeBeforeStateExecution() {
        val launch = launch()
        val runner = TestableRunner(supportedPreflight())
        val original = System.getProperty(OdbProgramRunner.RUNTIME_PATH_PROPERTY)
        try {
            System.setProperty(OdbProgramRunner.RUNTIME_PATH_PROPERTY, "/missing/odb-runtime.jar")

            val error = failure { runner.executeState(launch.state, launch.environment) }

            assertEquals(OdbProgramRunner.RUNTIME_MISSING_MESSAGE, error.message)
            assertEquals(0, launch.state.executionCount)
            assertEquals("sample.Main", launch.parameters.mainClass)
        } finally {
            if (original == null) {
                System.clearProperty(OdbProgramRunner.RUNTIME_PATH_PROPERTY)
            } else {
                System.setProperty(OdbProgramRunner.RUNTIME_PATH_PROPERTY, original)
            }
        }
    }

    fun testRunnerRejectsPartialRuntimeOverrideBeforeStateExecution() {
        val launch = launch()
        val runner = TestableRunner(supportedPreflight())
        val originalManifest = System.getProperty(OdbProgramRunner.RUNTIME_MANIFEST_PATH_PROPERTY)
        try {
            System.clearProperty(OdbProgramRunner.RUNTIME_MANIFEST_PATH_PROPERTY)

            val error = failure { runner.executeState(launch.state, launch.environment) }

            assertEquals(OdbProgramRunner.RUNTIME_MISSING_MESSAGE, error.message)
            assertEquals(0, launch.state.executionCount)
            assertEquals("sample.Main", launch.parameters.mainClass)
        } finally {
            if (originalManifest == null) {
                System.clearProperty(OdbProgramRunner.RUNTIME_MANIFEST_PATH_PROPERTY)
            } else {
                System.setProperty(OdbProgramRunner.RUNTIME_MANIFEST_PATH_PROPERTY, originalManifest)
            }
        }
    }

    fun testRunnerCleansPreparedStateWhenProcessCreationFails() {
        val launch = launch()
        val root = Files.createTempDirectory("odb-start-failure-root").toRealPath()
        val directory = Files.createTempDirectory(root, "session-").toRealPath()
        val prepared = OdbPreparedRuntime(
            OdbSessionState.create(root, directory),
            directory.resolve("odb-runtime.jar"),
            "0123456789abcdef0123456789abcdef",
        )
        val runner = PreparedTestableRunner(supportedPreflight()) { prepared }

        assertThrows(AssertionError::class.java) {
            runner.executeState(launch.state, launch.environment)
        }

        assertEquals(1, launch.state.executionCount)
        assertFalse(Files.exists(directory))
    }

    fun testRunnerSeedsSelectedMainSourceRootBeforeProcessCreation() {
        val sourceRoot = Files.createTempDirectory("odb source root ").toRealPath()
        val secondaryRoot = Files.createTempDirectory("odb secondary source root ").toRealPath()
        Disposer.register(testRootDisposable) { OdbSessionState.deleteTree(sourceRoot) }
        Disposer.register(testRootDisposable) { OdbSessionState.deleteTree(secondaryRoot) }
        val sourceFile = sourceRoot.resolve("sourcefixture/Main.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            "package sourcefixture; public class Main { public static void main(String[] args) {} }",
        )
        val virtualRoot = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourceRoot))
        PsiTestUtil.addContentRoot(module, virtualRoot)
        PsiTestUtil.addSourceRoot(module, virtualRoot)
        val secondaryVirtualRoot = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(secondaryRoot),
        )
        PsiTestUtil.addContentRoot(module, secondaryVirtualRoot)
        PsiTestUtil.addSourceRoot(module, secondaryVirtualRoot)
        assertTrue(
            JavaPsiFacade.getInstance(project)
                .findClass("sourcefixture.Main", GlobalSearchScope.moduleScope(module)) != null,
        )
        val profile = ApplicationConfiguration("fixture", project).apply {
            setModule(module)
            mainClassName = "sourcefixture.Main"
        }
        val parameters = supportedParameters().apply { mainClass = "sourcefixture.Main" }
        val environment = environment(profile)
        val state = SourceRootRecordingJavaState(
            environment,
            parameters,
            listOf(sourceRoot.toString(), secondaryRoot.toString()),
        )
        val root = Files.createTempDirectory("odb-source-root-state").toRealPath()
        val directory = Files.createTempDirectory(root, "session-").toRealPath()
        val runtime = Files.write(directory.resolve("odb-runtime.jar"), byteArrayOf(1))
        val prepared = OdbPreparedRuntime(
            OdbSessionState.create(root, directory),
            runtime,
            "0123456789abcdef0123456789abcdef",
        )
        val runner = PreparedTestableRunner(supportedPreflight()) { prepared }

        assertThrows(SourceRootsObserved::class.java) {
            runner.executeState(state, environment)
        }

        assertTrue(state.observed)
        assertFalse(Files.exists(directory))
    }

    private fun rejection(launch: Launch, desktopAvailable: Boolean = true): String =
        rejection(launch.profile, launch.state, desktopAvailable)

    private fun rejection(
        profile: RunProfile,
        state: RunProfileState,
        desktopAvailable: Boolean = true,
        targetRequest: TargetEnvironmentRequest? = null,
    ): String = requireNotNull(failure {
        OdbPreflight(isIntelliJ = true, productName = "IntelliJ IDEA", desktopAvailable = { desktopAvailable })
            .resolve(profile, state, targetRequest)
    }.message)

    private fun failure(block: () -> Unit): ExecutionException {
        try {
            block()
            throw AssertionError("Expected preflight rejection")
        } catch (error: ExecutionException) {
            return error
        }
    }

    private fun launch(
        profile: ApplicationConfiguration = application(),
        parameters: JavaParameters = supportedParameters(),
    ): Launch {
        val environment = environment(profile)
        return Launch(profile, RecordingJavaState(environment, parameters), parameters, environment)
    }

    private fun application(mainClass: String = "sample.Main"): ApplicationConfiguration {
        if (mainClass == "sample.Main" && findClass(mainClass) == null) {
            addClass(mainClass, "package sample; public class Main { public static void main(String[] args) {} }")
        }
        return ApplicationConfiguration("fixture", project).apply {
            setModule(module)
            mainClassName = mainClass
        }
    }

    private fun findClass(mainClass: String): com.intellij.psi.PsiClass? =
        com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(
            mainClass,
            com.intellij.psi.search.GlobalSearchScope.projectScope(project),
        )

    private fun addClass(qualifiedName: String, source: String) {
        val path = "src/${qualifiedName.replace('.', '/')}.java"
        myFixture.addFileToProject(path, source)
    }

    private fun supportedParameters(): JavaParameters = JavaParameters().apply {
        jdk = jdk8
        mainClass = "sample.Main"
        classPath.add("/target/classes")
    }

    private fun environment(profile: RunProfile): ExecutionEnvironment =
        ExecutionEnvironmentBuilder(project, OdbExecutor())
            .runProfile(profile)
            .runner(OdbProgramRunner())
            .build()

    private fun supportedPreflight() =
        OdbPreflight(isIntelliJ = true, productName = "IntelliJ IDEA", desktopAvailable = { true })

    private fun nonLocalTargetRequest(): TargetEnvironmentRequest = Proxy.newProxyInstance(
        TargetEnvironmentRequest::class.java.classLoader,
        arrayOf(TargetEnvironmentRequest::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "duplicate" -> nonLocalTargetRequest()
            "getUploadVolumes", "getDownloadVolumes", "getTargetPortBindings", "getLocalPortBindings" -> emptySet<Any>()
            else -> null
        }
    } as TargetEnvironmentRequest

    private data class Launch(
        val profile: ApplicationConfiguration,
        val state: RecordingJavaState,
        val parameters: JavaParameters,
        val environment: ExecutionEnvironment,
    )

    private open class StaticJavaState(
        environment: ExecutionEnvironment,
        protected val parameters: JavaParameters,
    ) : JavaCommandLineState(environment) {
        override fun createJavaParameters(): JavaParameters = parameters
    }

    private class RecordingJavaState(
        environment: ExecutionEnvironment,
        parameters: JavaParameters,
    ) : StaticJavaState(environment, parameters) {
        var executionCount = 0
        var parameterResolutionCount = 0

        override fun createJavaParameters(): JavaParameters {
            parameterResolutionCount++
            return parameters
        }

        override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
            executionCount++
            throw AssertionError("Rejected launch reached state.execute()")
        }
    }

    private class SourceRootRecordingJavaState(
        environment: ExecutionEnvironment,
        parameters: JavaParameters,
        private val expectedRoots: List<String>,
    ) : StaticJavaState(environment, parameters) {
        var observed = false

        override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
            val stateDirectory = Path.of(
                requireNotNull(parameters.vmParametersList.getPropertyValue(OdbLaunchPlan.STATE_DIRECTORY_PROPERTY)),
            )
            val roots = Files.readAllLines(stateDirectory.resolve(OdbSourceRoots.FILE_NAME))
            assertEquals(expectedRoots, roots)
            observed = true
            throw SourceRootsObserved()
        }
    }

    private class SourceRootsObserved : AssertionError()

    private class TestableRunner(preflight: OdbPreflight) : OdbProgramRunner(preflight) {
        fun executeState(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? =
            doExecute(state, environment)
    }

    private class PreparedTestableRunner(
        preflight: OdbPreflight,
        prepareRuntime: () -> OdbPreparedRuntime,
    ) : OdbProgramRunner(preflight, prepareRuntime) {
        fun executeState(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? =
            doExecute(state, environment)
    }

    private class UnsupportedProfile : RunProfile {
        override fun getName(): String = "unsupported"
        override fun getIcon() = null
        override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null
    }

    companion object {
        private const val LOCAL_ONLY_MESSAGE =
            "Run with ODB supports local Java Application processes only; remote, WSL, Docker, and SSH targets are not supported."
    }
}
