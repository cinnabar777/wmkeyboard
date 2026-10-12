package com.wasimaster.wmkeyboard.core.tools

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where an MCP call that asks first waits for the user (#573). */
class AiToolApprovalGateTest {

    private val pool = Executors.newSingleThreadExecutor()

    private fun waitUntil(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 2_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `the answer reaches the waiting call, and the question is taken down`() {
        val gate = AiToolApprovalGate()
        val shown = CopyOnWriteArrayList<AiToolActivity>()
        val approver = gate.approver(isActive = { true }, show = { shown += it }, shown = { shown += it })
        val ask = AiToolActivity("Tool", running = true, server = "S", awaitingApproval = true, details = "{}")
        val result = pool.submit<AiToolApproval> { approver.approve(ask) }
        waitUntil { gate.asking }
        assertTrue(gate.answer(AiToolApproval.ALWAYS))
        assertEquals(AiToolApproval.ALWAYS, result.get(2, TimeUnit.SECONDS))
        assertEquals(2, shown.size)
        assertTrue(shown[0].awaitingApproval)
        assertFalse(shown[1].awaitingApproval)
        assertTrue(shown[1].running)
        assertFalse(gate.asking)
    }

    @Test
    fun `a stopped run gives up instead of waiting for good`() {
        val gate = AiToolApprovalGate()
        val live = java.util.concurrent.atomic.AtomicBoolean(true)
        val result = pool.submit<AiToolApproval> { gate.await { live.get() } }
        waitUntil { gate.asking }
        live.set(false)
        assertEquals(AiToolApproval.CANCELLED, result.get(2, TimeUnit.SECONDS))
    }

    @Test
    fun `an answer with no question is ignored`() {
        assertFalse(AiToolApprovalGate().answer(AiToolApproval.ONCE))
    }
}
