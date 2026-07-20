package org.lewisodb.intellij.launch

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.JavaParameters
import java.nio.file.Path

class OdbLaunchPlan(
    private val adapterJar: Path,
    private val stateDirectory: Path,
    private val token: String,
) {
    fun applyTo(parameters: JavaParameters) {
        val targetMain = parameters.mainClass
            ?.takeIf(String::isNotBlank)
            ?: throw ExecutionException("Run with ODB requires a target main class.")
        if (targetMain == ADAPTER_MAIN) {
            throw ExecutionException("Run with ODB launch parameters were already prepared.")
        }

        parameters.classPath.addFirst(adapterJar.toString())
        parameters.programParametersList.prepend(targetMain)
        parameters.vmParametersList.defineProperty(STATE_DIRECTORY_PROPERTY, stateDirectory.toString())
        parameters.vmParametersList.defineProperty(TOKEN_PROPERTY, token)
        parameters.mainClass = ADAPTER_MAIN
    }

    companion object {
        const val ADAPTER_MAIN = "com.lambda.Debugger.IntegrationLauncher"
        const val STATE_DIRECTORY_PROPERTY = "com.lambda.Debugger.integration.stateDir"
        const val TOKEN_PROPERTY = "com.lambda.Debugger.integration.token"
    }
}
