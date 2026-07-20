package org.lewisodb.intellij.runtime

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class OdbRuntimeManifest(
    val sourceRepository: String,
    val sourceCommit: String,
    val artifact: String,
    val sha256: String,
    val sourceArchive: String,
    val sourceSha256: String,
    val javaClassVersion: Int,
    val integrationProtocol: Int,
    val dependencies: Map<String, String>,
) {
    companion object {
        private val fields = setOf(
            "sourceRepository",
            "sourceCommit",
            "artifact",
            "sha256",
            "sourceArchive",
            "sourceSha256",
            "javaClassVersion",
            "integrationProtocol",
            "dependencies",
        )
        private val commitPattern = Regex("[0-9a-f]{40}")
        private val digestPattern = Regex("[0-9a-f]{64}")

        fun parse(json: String): OdbRuntimeManifest = try {
            val objectValue = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
                ?: invalidManifest()
            if (objectValue.keySet() != fields) invalidManifest()

            val sourceRepository = objectValue.requiredString("sourceRepository")
            val sourceCommit = objectValue.requiredString("sourceCommit")
            val artifact = objectValue.requiredString("artifact")
            val sha256 = objectValue.requiredString("sha256")
            val sourceArchive = objectValue.requiredString("sourceArchive")
            val sourceSha256 = objectValue.requiredString("sourceSha256")
            val javaClassVersion = objectValue.requiredInt("javaClassVersion")
            val integrationProtocol = objectValue.requiredInt("integrationProtocol")
            val dependencies = objectValue.requiredDependencies()

            if (
                sourceRepository != "https://github.com/LewisODB/OmniscientDebugger" ||
                !commitPattern.matches(sourceCommit) ||
                artifact != "odb-runtime.jar" ||
                !digestPattern.matches(sha256) ||
                sourceArchive != "odb-source-$sourceCommit.tar.gz" ||
                !digestPattern.matches(sourceSha256) ||
                javaClassVersion != 52 ||
                integrationProtocol != 1 ||
                dependencies != REQUIRED_DEPENDENCIES
            ) invalidManifest()

            OdbRuntimeManifest(
                sourceRepository,
                sourceCommit,
                artifact,
                sha256,
                sourceArchive,
                sourceSha256,
                javaClassVersion,
                integrationProtocol,
                dependencies,
            )
        } catch (error: OdbRuntimeException) {
            throw error
        } catch (error: Exception) {
            throw OdbRuntimeException("Invalid bundled ODB runtime manifest.", error)
        }

        private fun JsonObject.requiredString(name: String): String =
            get(name).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: invalidManifest()

        private fun JsonObject.requiredInt(name: String): Int {
            val value = get(name).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString
                ?: invalidManifest()
            if (!value.matches(Regex("0|[1-9][0-9]*"))) invalidManifest()
            return value.toIntOrNull() ?: invalidManifest()
        }

        private fun JsonObject.requiredDependencies(): Map<String, String> {
            val dependencies = get("dependencies").takeIf { it.isJsonObject }?.asJsonObject ?: invalidManifest()
            return dependencies.entrySet().associate { (name, value) ->
                name to (value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    ?: invalidManifest())
            }
        }

        private fun invalidManifest(): Nothing = throw OdbRuntimeException("Invalid bundled ODB runtime manifest.")

        val REQUIRED_DEPENDENCIES = mapOf(
            "org.apache.bcel:bcel" to "6.12.0",
            "org.apache.commons:commons-lang3" to "3.20.0",
            "commons-io:commons-io" to "2.21.0",
            "org.ow2.asm:asm" to "9.7.1",
        )
    }
}

class OdbRuntimeException(message: String, cause: Throwable? = null) : Exception(message, cause)
