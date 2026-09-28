package com.wasimaster.wmkeyboard.core.netlog

import app.cash.turbine.test
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two flows [NetLog] publishes, watched with Turbine: [NetLog.inFlight],
 * which drives the settings screen's live line and the keyboard's network dot,
 * and [NetLog.version], which tells a screen to re-read the log.
 *
 * What matters about both is *which* changes they announce, and Turbine is
 * the tool for that: every emission has to be accounted for, so an extra one
 * (a screen re-reading the log for nothing) fails as surely as a missing one.
 *
 * [NetLog] is one object per process, so each test ends every call it starts
 * and puts the switches back.
 */
class NetLogFlowTest {

    private var wasEnabled = true

    @Before
    fun on() {
        wasEnabled = NetLog.enabled
        NetLog.enabled = true
    }

    @After
    fun restore() {
        NetLog.enabled = wasEnabled
    }

    private fun request(path: String = "/x") = NetLog.call(NetSource.OTHER, "get", "https://example.com$path")

    @Test
    fun `a request is in flight from its start until its end, and only then`() = runTest {
        NetLog.inFlight.test {
            assertEquals(emptyList<NetCall>(), awaitItem())

            val first = request("/a")
            assertEquals(listOf(first), awaitItem())
            val second = request("/b")
            assertEquals(listOf(first, second), awaitItem())

            first.end()
            assertEquals(listOf(second), awaitItem())
            second.end()
            assertEquals(emptyList<NetCall>(), awaitItem())

            // A second end() from a finally block is a no-op, not a new state.
            second.end()
            expectNoEvents()
        }
    }

    @Test
    fun `the log announces each finished request once, and never a start`() = runTest {
        NetLog.version.test {
            val before = awaitItem()

            val call = request()
            expectNoEvents()

            call.end()
            assertEquals(before + 1, awaitItem())

            call.end()
            expectNoEvents()
        }
    }

    @Test
    fun `a request begun while the log was off stays unrecorded`() = runTest {
        NetLog.version.test {
            awaitItem()
            NetLog.enabled = false
            val call = request()
            // Switched back on mid-request: the row still belongs to a request
            // the user did not want logged.
            NetLog.enabled = true
            call.end()
            expectNoEvents()
        }
    }

    @Test
    fun `a long-lived link is recorded but never shown as in flight`() = runTest {
        NetLog.inFlight.map { it.size }.test {
            assertEquals(0, awaitItem())
            val link = NetLog.callTo(NetSource.OTHER, "link", "tcp", "192.168.0.2", live = false)
            expectNoEvents()
            link.end()
            expectNoEvents()
        }
    }

    @Test
    fun `the last request to finish marks the log idle`() = runTest {
        val first = request("/a")
        val second = request("/b")
        val idleBefore = NetLog.lastIdleAt

        first.end()
        assertEquals("still one request under way", idleBefore, NetLog.lastIdleAt)

        second.end()
        assertTrue(NetLog.lastIdleAt >= idleBefore)
        assertTrue(NetLog.lastIdleAt > 0)
    }
}
