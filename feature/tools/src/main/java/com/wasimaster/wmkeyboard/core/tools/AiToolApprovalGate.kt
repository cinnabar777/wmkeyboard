package com.wasimaster.wmkeyboard.core.tools

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where a tool call that has to ask first waits for the user's answer (#573).
 *
 * One per surface that runs tools: the keyboard's AI panel has one, the chat
 * has one. The tool loop's thread blocks in [await]; the surface's Allow and
 * Deny buttons call [answer]. A run that is stopped or replaced while the
 * question is showing is not left blocked: [await] keeps checking whether
 * its run is still the live one and gives up with
 * [AiToolApproval.CANCELLED] when it is not, or when nobody has answered for
 * [TIMEOUT_MS]: a question on a panel the user has left would otherwise hold
 * a thread for as long as the process lives.
 */
class AiToolApprovalGate {

    private class Ask {
        val latch = CountDownLatch(1)

        @Volatile
        var choice = AiToolApproval.CANCELLED
    }

    @Volatile
    private var current: Ask? = null

    /** Whether a question is showing. */
    val asking: Boolean get() = current != null

    /** Blocks until [answer] is called, or until [isActive] says the run is over. */
    fun await(isActive: () -> Boolean): AiToolApproval {
        val ask = Ask()
        current = ask
        val giveUpAt = System.currentTimeMillis() + TIMEOUT_MS
        try {
            while (isActive() && System.currentTimeMillis() < giveUpAt) {
                if (ask.latch.await(POLL_MS, TimeUnit.MILLISECONDS)) return ask.choice
            }
            return AiToolApproval.CANCELLED
        } finally {
            if (current === ask) current = null
        }
    }

    /** Answers the question showing now. False when there is none, as after a double tap. */
    fun answer(choice: AiToolApproval): Boolean {
        val ask = current ?: return false
        current = null
        ask.choice = choice
        ask.latch.countDown()
        return true
    }

    /**
     * An approver for one run: [show] puts the question on screen, [await]
     * waits for the answer, and [shown] takes the question away again with
     * the call still running, whatever the answer was.
     */
    fun approver(
        isActive: () -> Boolean,
        show: (AiToolActivity) -> Unit,
        shown: (AiToolActivity) -> Unit,
    ): AiToolApprover = AiToolApprover { ask ->
        show(ask)
        try {
            await(isActive)
        } finally {
            shown(ask.copy(awaitingApproval = false, details = ""))
        }
    }

    private companion object {
        /** How often a waiting call checks that its run was not stopped. */
        const val POLL_MS = 250L

        /** How long a question waits for an answer before the call is dropped. */
        const val TIMEOUT_MS = 5 * 60_000L
    }
}
