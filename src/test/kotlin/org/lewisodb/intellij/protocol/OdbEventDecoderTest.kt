package org.lewisodb.intellij.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OdbEventDecoderTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun `accepts exact authenticated versioned increasing events across chunks`() {
        val decoder = OdbEventDecoder(token)
        val line = event(token, 1, "runtime-ready", "\"target\":\"example.Main\"")

        assertTrue(decoder.append(line.substring(0, 19)).isEmpty())
        val decoded = decoder.append(line.substring(19))

        assertEquals(1, decoded.size)
        assertEquals(OdbEvent.RuntimeReady("example.Main"), decoded.single().event)
        assertNull(decoded.single().ordinaryText)
    }

    @Test
    fun `rejects unauthenticated malformed unknown duplicate and decreasing lines as ordinary text`() {
        val decoder = OdbEventDecoder(token)
        assertEquals(OdbEvent.RuntimeReady("example.Main"), decoder.append(event(token, 2, "runtime-ready", "\"target\":\"example.Main\"" )).single().event)
        val lines = listOf(
            event("f".repeat(32), 3, "target-loaded", "\"target\":\"example.Main\""),
            event(token, 3, "target-loaded", "\"target\":\"example.Main\"").replace("\"version\":1", "\"version\":2"),
            event(token, 3, "target-loaded", "\"target\":\"example.Main\"").replace("\"sequence\":3", "\"sequence\":3.0"),
            event(token, 3, "unknown", "\"target\":\"example.Main\""),
            event(token, 3, "fatal", "\"code\":\"MADE_UP\",\"message\":\"lookalike\""),
            event(token, 2, "target-loaded", "\"target\":\"example.Main\""),
            "@@ODB-INTEGRATION@@\t$token\t{bad json}\n",
            "target says @@ODB-INTEGRATION@@\t$token\t{}\n",
        )

        for (line in lines) {
            val result = decoder.append(line).single()
            assertNull(result.event)
            assertEquals(line, result.ordinaryText)
        }
    }

    @Test
    fun `decodes every known event type`() {
        val decoder = OdbEventDecoder(token)
        val decoded = listOf(
            event(token, 1, "runtime-ready", "\"target\":\"example.Main\""),
            event(token, 2, "target-loaded", "\"target\":\"example.Main\""),
            event(token, 3, "recording-started", "\"created\":4,\"retained\":2"),
            event(token, 4, "debugger-ready", "\"created\":5,\"retained\":3"),
            event(token, 5, "fatal", "\"code\":\"NO_RECORDING\",\"message\":\"No usable recording\""),
        ).flatMap(decoder::append).mapNotNull { it.event }

        assertEquals(5, decoded.size)
        assertTrue(decoded.last() is OdbEvent.Fatal)
    }

    private fun event(token: String, sequence: Long, type: String, data: String): String =
        "@@ODB-INTEGRATION@@\t$token\t{\"version\":1,\"sequence\":$sequence,\"type\":\"$type\",$data}\n"
}
