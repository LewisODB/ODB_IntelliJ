package org.lewisodb.intellij.runtime

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class OdbRuntimeManifest(
    val sourceCommit: String,
    val artifact: String,
    val sha256: String,
    val sourceArtifact: String,
    val sourceSha256: String,
    val javaClassVersion: Int,
    val integrationProtocol: Int,
    val adapterClass: String,
    val dependencies: List<String>,
) {
    companion object {
        private val fields = setOf(
            "sourceCommit",
            "artifact",
            "sha256",
            "sourceArtifact",
            "sourceSha256",
            "javaClassVersion",
            "integrationProtocol",
            "adapterClass",
            "dependencies",
        )
        private val digestPattern = Regex("[0-9a-f]{64}")
        private val commitPattern = Regex("[0-9a-f]{40}")
        private const val ADAPTER_CLASS = "com.lambda.Debugger.IntegrationLauncher"
        private val approvedDependencies = listOf(
            "commons-io:commons-io:2.21.0",
            "org.apache.bcel:bcel:6.12.0",
            "org.apache.commons:commons-lang3:3.20.0",
            "org.ow2.asm:asm:9.7.1",
        )

        fun parse(json: String): OdbRuntimeManifest = try {
            val objectValue = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
                ?: invalidManifest()
            if (objectValue.keySet() != fields) invalidManifest()

            val sourceCommit = objectValue.requiredString("sourceCommit")
            val artifact = objectValue.requiredString("artifact")
            val sha256 = objectValue.requiredString("sha256")
            val sourceArtifact = objectValue.requiredString("sourceArtifact")
            val sourceSha256 = objectValue.requiredString("sourceSha256")
            val javaClassVersion = objectValue.requiredInt("javaClassVersion")
            val integrationProtocol = objectValue.requiredInt("integrationProtocol")
            val adapterClass = objectValue.requiredString("adapterClass")
            val dependencies = objectValue.requiredStrings("dependencies")

            if (
                !commitPattern.matches(sourceCommit) ||
                artifact != "odb-runtime.jar" ||
                !digestPattern.matches(sha256) ||
                sourceArtifact != "odb-source-$sourceCommit.tar.gz" ||
                !digestPattern.matches(sourceSha256) ||
                javaClassVersion != 52 ||
                integrationProtocol != 1 ||
                adapterClass != ADAPTER_CLASS ||
                dependencies != approvedDependencies
            ) invalidManifest()

            OdbRuntimeManifest(
                sourceCommit,
                artifact,
                sha256,
                sourceArtifact,
                sourceSha256,
                javaClassVersion,
                integrationProtocol,
                adapterClass,
                dependencies,
            )
        } catch (error: OdbRuntimeException) {
            throw error
        } catch (error: Exception) {
            throw OdbRuntimeException("Invalid bundled ODB runtime manifest.", error)
        }

        private fun JsonObject.requiredString(name: String): String =
            get(name).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: invalidManifest()

        private fun JsonObject.requiredStrings(name: String): List<String> {
            val value = get(name).takeIf { it.isJsonArray }?.asJsonArray ?: invalidManifest()
            return value.map { element ->
                element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: invalidManifest()
            }
        }

        private fun JsonObject.requiredInt(name: String): Int {
            val value = get(name).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString
                ?: invalidManifest()
            if (!value.matches(Regex("0|[1-9][0-9]*"))) invalidManifest()
            return value.toIntOrNull() ?: invalidManifest()
        }

        private fun invalidManifest(): Nothing = throw OdbRuntimeException("Invalid bundled ODB runtime manifest.")
    }
}

class OdbRuntimeException(message: String, cause: Throwable? = null) : Exception(message, cause)
