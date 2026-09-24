package com.wasimaster.wmkeyboard.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.structuralEqualityPolicy

/**
 * A value that is replaced as a whole but read a piece at a time: the settings
 * object, which every preference write re-emits in full, read by screens whose
 * rows each care about one field of it.
 *
 * Handed down as the plain value, it made every write a change to every
 * composable that took it — the screen's own parameter was new, so every row
 * lambda capturing it was new, and every row on the screen recomposed to find
 * out that its own switch had not moved. This holder is the same instance for
 * as long as the screen is up, so nothing that takes it or captures it changes
 * on a write. What changes is [watch]: each call subscribes only its own
 * restart scope, and only to the piece it selected.
 *
 * Two ways to read, and which one depends on where the read is:
 *
 * - **In composition, [watch].** Put it inside the row that draws the value
 *   (the `item { }` of a settings group), not at the top of the screen: a read
 *   recomposes the scope it sits in, and a screen-level read hands the value
 *   to every lambda below it that captures it.
 * - **In a callback, [value].** A click or a coroutine reads the whole value at
 *   the moment it runs, and nothing is subscribed because nothing is composing.
 *   Read in composition, [value] still works, but subscribes the reader to
 *   every change, which is the cost this class exists to remove.
 */
@Stable
class LiveState<T>(private val state: State<T>) {

    /**
     * The whole value, now. For callbacks and effects; see the class note for
     * why a composable reads through [watch] instead.
     */
    val value: T get() = state.value

    /**
     * The piece of the value [select] picks out, recomposing the caller when
     * that piece changes and not when anything else does.
     *
     * Compared by `equals`, so selecting a whole nested settings family
     * (`{ it.sound }`) recomposes on a change to any field in it, and a
     * selector that builds a fresh list or string of equal contents does not
     * count as a change. [select] may capture the caller's other inputs — an id
     * from the route, say — and is re-read when they change.
     */
    @Composable
    fun <R> watch(select: (T) -> R): R {
        val selector = rememberUpdatedState(select)
        return remember(this) {
            derivedStateOf(structuralEqualityPolicy()) { selector.value(state.value) }
        }.value
    }
}
