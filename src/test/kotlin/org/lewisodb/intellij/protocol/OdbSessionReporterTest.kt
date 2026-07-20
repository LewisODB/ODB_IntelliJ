package org.lewisodb.intellij.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OdbSessionReporterTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun `accepted ordered events print launch states`() {
        val console = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val reporter = OdbSessionReporter(token, console::add, failures::add)

        reporter.onStderr(event(1, "runtime-ready", "\"target\":\"example.Main\""))
        reporter.onStderr(event(2, "target-loaded", "\"target\":\"example.Main\""))
        reporter.onStderr(event(3, "recording-started", "\"created\":3,\"retained\":2"))
        reporter.onStderr(event(4, "debugger-ready", "\"created\":4,\"retained\":3"))
        reporter.onTerminated(0)

        assertEquals(
            listOf(
                "Loading example.Main with ODB...\n",
                "ODB target loaded.\n",
                "ODB recording started.\n",
                "ODB debugger ready.\n",
            ),
            console,
        )
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `fatal and premature termination each report only one failure`() {
        val fatalFailures = mutableListOf<String>()
        val fatal = OdbSessionReporter(token, {}, fatalFailures::add)
        fatal.onStderr(event(1, "fatal", "\"code\":\"NO_RECORDING\",\"message\":\"No usable recording\""))
        fatal.onTerminated(1)
        assertEquals(listOf("NO_RECORDING: No usable recording"), fatalFailures)

        val prematureFailures = mutableListOf<String>()
        val premature = OdbSessionReporter(token, {}, prematureFailures::add)
        premature.onStderr(event(1, "runtime-ready", "\"target\":\"example.Main\""))
        premature.onTerminated(7)
        premature.onTerminated(7)
        assertEquals(listOf("ODB process exited unexpectedly (exit code 7)."), prematureFailures)

        val postReadyFailures = mutableListOf<String>()
        val postReady = OdbSessionReporter(token, {}, postReadyFailures::add)
        postReady.onStderr(event(1, "runtime-ready", "\"target\":\"example.Main\""))
        postReady.onStderr(event(2, "target-loaded", "\"target\":\"example.Main\""))
        postReady.onStderr(event(3, "recording-started", "\"created\":3,\"retained\":2"))
        postReady.onStderr(event(4, "debugger-ready", "\"created\":4,\"retained\":3"))
        postReady.onTerminated(7)
        assertEquals(listOf("ODB process exited unexpectedly (exit code 7)."), postReadyFailures)
    }

    @Test
    fun `lookalike and out-of-order events never advance session state`() {
        val console = mutableListOf<String>()
        val reporter = OdbSessionReporter(token, console::add, {})

        reporter.onStderr(event(1, "debugger-ready", "\"created\":4,\"retained\":3"))
        reporter.onStderr(event(2, "runtime-ready", "\"target\":\"example.Main\"").replace(token, "f".repeat(32)))

        assertTrue(console.isEmpty())
    }

    private fun event(sequence: Long, type: String, data: String): String =
        "@@ODB-INTEGRATION@@\t$token\t{\"version\":1,\"sequence\":$sequence,\"type\":\"$type\",$data}\n"
}
