package org.lewisodb.intellij.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class OdbRecoveryStartupActivityTest {
    @Test
    fun `recovery runs once across concurrent project startups`() {
        val calls = AtomicInteger()
        val gate = OdbRecoveryGate(calls::incrementAndGet)
        val executor = Executors.newFixedThreadPool(4)

        executor.invokeAll(List(8) { Callable { gate.runOnce() } }).forEach { it.get() }
        executor.shutdownNow()

        assertEquals(1, calls.get())
    }
}
