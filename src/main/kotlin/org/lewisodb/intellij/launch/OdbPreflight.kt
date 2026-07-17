package org.lewisodb.intellij.launch

import com.intellij.execution.ExecutionException
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.JavaCommandLineState
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.target.TargetEnvironmentRequest
import com.intellij.execution.target.local.LocalTargetEnvironmentRequest
import com.intellij.execution.util.JavaParametersUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.JavaSdkType
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.psi.CommonClassNames
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiTypes
import com.intellij.util.PlatformUtils
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal class OdbPreflight(
    private val isIntelliJ: Boolean = PlatformUtils.isIntelliJ(),
    private val productName: String = ApplicationInfo.getInstance().versionName,
    private val desktopAvailable: () -> Boolean = ::hasDesktopGraphics,
) {
    fun resolve(
        profile: RunProfile,
        state: RunProfileState,
        targetRequest: TargetEnvironmentRequest?,
    ): JavaParameters {
        rejectUnless(isIntelliJ) {
            "Run with ODB version 1 supports IntelliJ IDEA only. Current product: $productName."
        }

        val configuration = profile as? ApplicationConfiguration
            ?: reject("Run with ODB supports Java Application configurations only. Selected: ${profileType(profile)}.")
        val javaState = state as? JavaCommandLineState ?: reject(LOCAL_ONLY_MESSAGE)

        val parameters = try {
            javaState.javaParameters
        } catch (error: ExecutionException) {
            rejectUnless(isLocalConfiguration(configuration, targetRequest)) { LOCAL_ONLY_MESSAGE }
            normalizeResolutionFailure(configuration, error)
        }

        rejectUnless(isLocal(configuration, javaState, targetRequest)) { LOCAL_ONLY_MESSAGE }

        val sdk = parameters.jdk
            ?: reject("Run with ODB cannot start because this configuration has no resolved JDK.")

        val executable = executablePath(sdk)
        rejectUnless(isLocalSdk(sdk) && executable != null && isRegularLocalFile(executable)) {
            "Run with ODB cannot use the resolved Java executable: ${executable ?: unresolvedExecutable(sdk)}. Select a local JDK 8."
        }

        val version = JavaSdk.getInstance().getVersion(sdk)
        rejectUnless(version == JavaSdkVersion.JDK_1_8) {
            val selected = version?.description ?: sdk.versionString ?: "unknown"
            "Run with ODB version 1 supports target JDK 8 only. Selected: $selected (${sdk.homePath ?: "<unresolved>"}). IntelliJ's own runtime is unrelated."
        }

        rejectUnless(parameters.moduleName.isNullOrBlank() && parameters.modulePath.pathList.isEmpty()) {
            "Run with ODB version 1 does not support Java module-path applications. Use a classpath-based Java Application configuration."
        }

        val selectedMain = parameters.mainClass?.takeIf(String::isNotBlank)
        val mainClass = selectedMain?.let { configuration.mainClass }
            ?: reject("Run with ODB cannot resolve the selected application's main class.")
        rejectUnless(!configuration.isImplicitClassConfiguration && hasConventionalMain(mainClass)) {
            "Run with ODB requires public static void main(String[]). Selected: ${mainClass.qualifiedName ?: selectedMain}."
        }

        rejectUnless(desktopAvailable()) {
            "Run with ODB requires a local desktop graphics environment for its Swing debugger."
        }

        return parameters
    }

    private fun normalizeResolutionFailure(
        configuration: ApplicationConfiguration,
        original: ExecutionException,
    ): JavaParameters {
        if (resolvedSdk(configuration) == null) {
            reject("Run with ODB cannot start because this configuration has no resolved JDK.")
        }
        if (configuration.mainClassName.isNullOrBlank() || configuration.mainClass == null) {
            reject("Run with ODB cannot resolve the selected application's main class.")
        }
        throw original
    }

    private fun resolvedSdk(configuration: ApplicationConfiguration): Sdk? = runCatching {
        val alternative = configuration.alternativeJrePath.takeIf { configuration.isAlternativeJrePathEnabled }
        configuration.configurationModule.module?.let {
            JavaParametersUtil.createModuleJdk(it, false, alternative)
        } ?: JavaParametersUtil.createProjectJdk(configuration.project, alternative)
    }.getOrNull()

    private fun isLocal(
        configuration: ApplicationConfiguration,
        state: JavaCommandLineState,
        targetRequest: TargetEnvironmentRequest?,
    ): Boolean {
        if (!isLocalConfiguration(configuration, targetRequest)) return false
        val customRequest = runCatching { state.createCustomTargetEnvironmentRequest() }
            .getOrElse { return false }
        return customRequest == null || customRequest is LocalTargetEnvironmentRequest
    }

    private fun isLocalConfiguration(
        configuration: ApplicationConfiguration,
        targetRequest: TargetEnvironmentRequest?,
    ): Boolean =
        configuration.defaultTargetName.isNullOrBlank() &&
            !configuration.needPrepareTarget() &&
            (targetRequest == null || targetRequest is LocalTargetEnvironmentRequest)

    private fun isLocalSdk(sdk: Sdk): Boolean =
        sdk.homeDirectory?.fileSystem?.protocol?.equals(StandardFileSystems.FILE_PROTOCOL, ignoreCase = true) != false

    private fun executablePath(sdk: Sdk): String? =
        (sdk.sdkType as? JavaSdkType)?.getVMExecutablePath(sdk)?.takeIf(String::isNotBlank)

    private fun unresolvedExecutable(sdk: Sdk): String = sdk.homePath ?: "<unresolved>"

    private fun isRegularLocalFile(value: String): Boolean = try {
        Files.isRegularFile(Path.of(value))
    } catch (_: InvalidPathException) {
        false
    }

    private fun hasConventionalMain(mainClass: PsiClass): Boolean =
        mainClass.findMethodsByName("main", true).any { method ->
            method.hasModifierProperty(PsiModifier.PUBLIC) &&
                method.hasModifierProperty(PsiModifier.STATIC) &&
                method.returnType == PsiTypes.voidType() &&
                method.parameterList.parameters.singleOrNull()?.type.let { parameterType ->
                    parameterType is PsiArrayType &&
                        parameterType.componentType.equalsToText(CommonClassNames.JAVA_LANG_STRING)
                }
        }

    private fun profileType(profile: RunProfile): String = when (profile) {
        is RunConfiguration -> profile.type.displayName
        else -> profile.javaClass.simpleName.ifBlank { profile.name }
    }

    private inline fun rejectUnless(condition: Boolean, message: () -> String) {
        if (!condition) reject(message())
    }

    private fun reject(message: String): Nothing = throw ExecutionException(message)

    companion object {
        private const val LOCAL_ONLY_MESSAGE =
            "Run with ODB supports local Java Application processes only; remote, WSL, Docker, and SSH targets are not supported."

        private fun hasDesktopGraphics(): Boolean =
            !GraphicsEnvironment.isHeadless() && runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.isNotEmpty()
            }.getOrDefault(false)
    }
}
