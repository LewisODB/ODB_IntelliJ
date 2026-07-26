package org.lewisodb.intellij.runtime

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class OdbRuntimeManifest(
    val artifact: String,
    val sha256: String,
    val javaClassVersion: Int,
    val integrationProtocol: Int,
) {
    companion object {
        private val fields = setOf(
            "artifact",
            "sha256",
            "javaClassVersion",
            "integrationProtocol",
        )
        private val digestPattern = Regex("[0-9a-f]{64}")

        fun parse(json: String): OdbRuntimeManifest = try {
            val objectValue = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
                ?: invalidManifest()
            if (objectValue.keySet() != fields) invalidManifest()

            val artifact = objectValue.requiredString("artifact")
            val sha256 = objectValue.requiredString("sha256")
            val javaClassVersion = objectValue.requiredInt("javaClassVersion")
            val integrationProtocol = objectValue.requiredInt("integrationProtocol")

            if (
                artifact != "odb-runtime.jar" ||
                !digestPattern.matches(sha256) ||
                javaClassVersion != 52 ||
                integrationProtocol != 1
            ) invalidManifest()

            OdbRuntimeManifest(
                artifact,
                sha256,
                javaClassVersion,
                integrationProtocol,
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

        private fun invalidManifest(): Nothing = throw OdbRuntimeException("Invalid bundled ODB runtime manifest.")
    }
}

class OdbRuntimeException(message: String, cause: Throwable? = null) : Exception(message, cause)
