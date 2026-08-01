package org.lewisodb.intellij.lifecycle

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

internal fun interface OdbMetadataMover {
    fun move(source: Path, target: Path)
}

internal data class OdbSessionMetadata(
    val sessionId: String,
    val ideOwner: OdbProcessIdentity,
    val odbChild: OdbProcessIdentity?,
) {
    fun encode(): String = buildString {
        append("{\"version\":")
        append(VERSION)
        append(",\"sessionId\":\"")
        append(sessionId)
        append("\",\"ideOwner\":")
        append(ideOwner.encode())
        append(",\"odbChild\":")
        append(odbChild?.encode() ?: "null")
        append('}')
    }

    companion object {
        private val sessionIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val fields = setOf("version", "sessionId", "ideOwner", "odbChild")
        private val identityFields = setOf("pid", "startedAt")

        fun read(directory: Path): OdbSessionMetadata? {
            val path = directory.resolve(METADATA_FILE)
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
            if (Files.size(path) > MAX_METADATA_BYTES) return null
            val value = JsonParser.parseString(Files.readString(path))
            if (!value.isJsonObject) return null
            val objectValue = value.asJsonObject
            if (objectValue.keySet() != fields) return null
            val version = objectValue.get("version")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asString
            if (version != VERSION.toString()) return null
            val sessionId = objectValue.get("sessionId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.takeIf(sessionIdPattern::matches) ?: return null
            val ownerValue = objectValue.get("ideOwner")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val owner = ownerValue.identity() ?: return null
            val childValue = objectValue.get("odbChild")
            val child = when {
                childValue == null || childValue.isJsonNull -> null
                childValue.isJsonObject -> childValue.asJsonObject.identity() ?: return null
                else -> return null
            }
            return OdbSessionMetadata(sessionId, owner, child)
        }

        fun requireSessionId(value: String): String = value.takeIf(sessionIdPattern::matches)
            ?: throw IllegalArgumentException("Invalid ODB session identifier.")

        private fun JsonObject.identity(): OdbProcessIdentity? {
            if (keySet() != identityFields) return null
            val pidText = get("pid")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString
                ?: return null
            if (!pidText.matches(Regex("[1-9][0-9]*"))) return null
            val pid = pidText.toLongOrNull() ?: return null
            val startedAtText = get("startedAt")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString ?: return null
            val startedAt = try {
                Instant.parse(startedAtText)
            } catch (_: Exception) {
                return null
            }
            return OdbProcessIdentity(pid, startedAt)
        }

        const val METADATA_FILE = "session.json"
        const val VERSION = 1
        private const val MAX_METADATA_BYTES = 16 * 1024
    }
}

internal class OdbSessionMetadataFile(
    private val directory: Path,
    private val mover: OdbMetadataMover = OdbMetadataMover { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
) {
    fun write(metadata: OdbSessionMetadata) {
        val temporary = directory.resolve(".${OdbSessionMetadata.METADATA_FILE}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(
                temporary,
                metadata.encode(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            mover.move(temporary, directory.resolve(OdbSessionMetadata.METADATA_FILE))
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private fun OdbProcessIdentity.encode(): String =
    "{\"pid\":$pid,\"startedAt\":\"$startedAt\"}"
