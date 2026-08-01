package org.lewisodb.intellij.protocol

class OdbSessionReporter(
    token: String,
    private val printStatus: (String) -> Unit,
    private val reportFailure: (String) -> Unit,
) {
    private val decoder = OdbEventDecoder(token)
    private var state = State.PREPARING
    private var failureReported = false
    private var stopRequested = false

    fun onStderr(text: String) {
        decoder.append(text).mapNotNull(OdbDecodedLine::event).forEach(::accept)
    }

    fun onTerminated(exitCode: Int) {
        if (stopRequested) return
        if (state == State.FATAL) return
        if (exitCode != 0) {
            failOnce("ODB process exited unexpectedly (exit code $exitCode).")
        } else if (state != State.DEBUGGER_READY) {
            failOnce("ODB process exited before the debugger was ready (exit code $exitCode).")
        }
    }

    fun onStopRequested() {
        stopRequested = true
    }

    private fun accept(event: OdbEvent) {
        when (event) {
            is OdbEvent.RuntimeReady -> transition(State.PREPARING, State.RUNTIME_READY) {
                printStatus("Loading ${event.target} with ODB...\n")
            }
            is OdbEvent.TargetLoaded -> transition(State.RUNTIME_READY, State.TARGET_LOADED) {
                printStatus("ODB target loaded.\n")
            }
            is OdbEvent.RecordingStarted -> transition(State.TARGET_LOADED, State.RECORDING_STARTED) {
                printStatus("ODB recording started.\n")
            }
            is OdbEvent.DebuggerReady -> transition(State.RECORDING_STARTED, State.DEBUGGER_READY) {
                printStatus("ODB debugger ready.\n")
            }
            is OdbEvent.Fatal -> {
                state = State.FATAL
                if (!stopRequested) failOnce("${event.code}: ${event.message}")
            }
        }
    }

    private inline fun transition(expected: State, next: State, action: () -> Unit) {
        if (state == expected) {
            state = next
            action()
        }
    }

    private fun failOnce(message: String) {
        if (!failureReported) {
            failureReported = true
            reportFailure(message)
        }
    }

    private enum class State {
        PREPARING,
        RUNTIME_READY,
        TARGET_LOADED,
        RECORDING_STARTED,
        DEBUGGER_READY,
        FATAL,
    }
}
