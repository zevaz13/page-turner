package xyz.pageturner.keylab

enum class Gesture { SHORT, DOUBLE, LONG }

/**
 * Turns key down/up times (ms, one clock) into gestures. No timers inside: the caller
 * schedules a call to [onTick] at [nextDeadline], so the logic is testable on the JVM.
 *
 * - LONG fires while the key is still held, as soon as it has been held [longPressMs].
 * - DOUBLE: a second press starting within [doubleTapMs] of the first release.
 *   The second press always counts as part of the double tap, however long it is held.
 * - SHORT fires only once the double-tap window has passed with no second press,
 *   so a short tap is reported [doubleTapMs] late. That delay is the price of double tap.
 */
class GestureClassifier(
    val longPressMs: Long,
    val doubleTapMs: Long,
) {
    private sealed interface State
    private object Idle : State
    private class Down(val t: Long, val second: Boolean, var longFired: Boolean = false) : State
    private class WaitSecond(val upT: Long) : State

    private var state: State = Idle

    fun onDown(t: Long): List<Gesture> {
        val s = state
        return when (s) {
            is Down -> emptyList() // already held (missed up or framework repeat)
            is WaitSecond ->
                if (t - s.upT <= doubleTapMs) {
                    state = Down(t, second = true); emptyList()
                } else {
                    // The tick that should have closed the window never ran; flush it now.
                    state = Down(t, second = false); listOf(Gesture.SHORT)
                }
            Idle -> { state = Down(t, second = false); emptyList() }
        }
    }

    fun onUp(t: Long): List<Gesture> {
        val s = state as? Down ?: return emptyList()
        return when {
            s.longFired -> { state = Idle; emptyList() }
            s.second -> { state = Idle; listOf(Gesture.DOUBLE) }
            t - s.t >= longPressMs -> { state = Idle; listOf(Gesture.LONG) }
            else -> { state = WaitSecond(t); emptyList() }
        }
    }

    fun onTick(t: Long): List<Gesture> {
        val s = state
        return when {
            s is Down && !s.second && !s.longFired && t - s.t >= longPressMs -> {
                s.longFired = true; listOf(Gesture.LONG)
            }
            s is WaitSecond && t - s.upT > doubleTapMs -> { state = Idle; listOf(Gesture.SHORT) }
            else -> emptyList()
        }
    }

    /** When the caller should call [onTick] next, or null if nothing is pending. */
    fun nextDeadline(): Long? = when (val s = state) {
        is Down -> if (!s.second && !s.longFired) s.t + longPressMs else null
        is WaitSecond -> s.upT + doubleTapMs + 1
        Idle -> null
    }
}
