package org.lewisodb.intellij.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser

class OdbEventDecoder(private val expectedToken: String) {
    private val pending = StringBuilder()
    private var lastSequence = 0L

    init {
        require(expectedToken.matches(Regex("[0-9a-f]{32}")))
    }

    fun append(text: String): List<OdbDecodedLine> {
        pending.append(text)
        val decoded = mutableListOf<OdbDecodedLine>()
        while (true) {
            val newline = pending.indexOf("\n")
            if (newline < 0) return decoded
            val line = pending.substring(0, newline + 1)
            pending.delete(0, newline + 1)
            val event = decodeLine(line)
            decoded += if (event == null) OdbDecodedLine(null, line) else OdbDecodedLine(event, null)
        }
    }

    private fun decodeLine(line: String): OdbEvent? {
        if (!line.startsWith(PREFIX)) return null
        val tokenEnd = line.indexOf('\t', PREFIX.length)
        if (tokenEnd < 0 || line.substring(PREFIX.length, tokenEnd) != expectedToken) return null
        val json = line.substring(tokenEnd + 1, line.length - 1)
        val objectValue = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        } catch (_: Exception) {
            return null
        }
        val version = objectValue.long("version") ?: return null
        val sequence = objectValue.long("sequence") ?: return null
        val type = objectValue.string("type") ?: return null
        if (version != 1L || sequence <= lastSequence) return null
        val event = decodeEvent(type, objectValue) ?: return null
        lastSequence = sequence
        return event
    }

    private fun decodeEvent(type: String, value: JsonObject): OdbEvent? = when (type) {
        "runtime-ready" -> value.string("target")?.let(OdbEvent::RuntimeReady)
        "target-loaded" -> value.string("target")?.let(OdbEvent::TargetLoaded)
        "recording-started" -> counts(value) { created, retained -> OdbEvent.RecordingStarted(created, retained) }
        "debugger-ready" -> counts(value) { created, retained -> OdbEvent.DebuggerReady(created, retained) }
        "fatal" -> {
            val code = value.string("code")
            val message = value.string("message")
            if (code != null && code in FATAL_CODES && message != null) {
                OdbEvent.Fatal(code, message, value.optionalString("class"), value.optionalString("cause"))
            } else {
                null
            }
        }
        else -> null
    }

    private fun counts(value: JsonObject, event: (Long, Long) -> OdbEvent): OdbEvent? {
        val created = value.long("created") ?: return null
        val retained = value.long("retained") ?: return null
        if (created < 0 || retained < 0) return null
        return event(created, retained)
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf(String::isNotEmpty)

    private fun JsonObject.optionalString(name: String): String? =
        if (!has(name)) null else string(name)

    private fun JsonObject.long(name: String): Long? {
        val value = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString ?: return null
        if (!value.matches(Regex("0|[1-9][0-9]*"))) return null
        return value.toLongOrNull()
    }

    companion object {
        const val PREFIX = "@@ODB-INTEGRATION@@\t"
        private val FATAL_CODES = setOf(
            "BAD_CONTRACT",
            "DEFAULTS_IO",
            "TARGET_CLASS_NOT_FOUND",
            "MAIN_METHOD_INVALID",
            "INSTRUMENTATION_FAILED",
            "NO_RECORDING",
            "INTERNAL_ERROR",
        )
    }
}
