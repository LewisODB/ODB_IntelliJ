package org.lewisodb.intellij.protocol

sealed interface OdbEvent {
    data class RuntimeReady(val target: String) : OdbEvent
    data class TargetLoaded(val target: String) : OdbEvent
    data class RecordingStarted(val created: Long, val retained: Long) : OdbEvent
    data class DebuggerReady(val created: Long, val retained: Long) : OdbEvent
    data class Fatal(val code: String, val message: String, val errorClass: String?, val cause: String?) : OdbEvent
}

data class OdbDecodedLine(val event: OdbEvent?, val ordinaryText: String?)
