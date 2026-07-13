package org.lewisodb.intellij.launch

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.JavaParameters
import java.nio.file.Path

class OdbLaunchPlan(private val adapterJar: Path) {
    fun applyTo(parameters: JavaParameters) {
        val targetMain = parameters.mainClass
            ?.takeIf(String::isNotBlank)
            ?: throw ExecutionException("Run with ODB requires a target main class.")
        if (targetMain == ADAPTER_MAIN) {
            throw ExecutionException("Run with ODB launch parameters were already prepared.")
        }

        parameters.classPath.addFirst(adapterJar.toString())
        parameters.programParametersList.prepend(targetMain)
        parameters.mainClass = ADAPTER_MAIN
    }

    companion object {
        const val ADAPTER_MAIN = "com.lambda.Debugger.IntegrationLauncher"
    }
}
