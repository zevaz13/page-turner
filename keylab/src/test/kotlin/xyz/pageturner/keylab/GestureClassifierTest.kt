package xyz.pageturner.keylab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.pageturner.keylab.Gesture.DOUBLE
import xyz.pageturner.keylab.Gesture.LONG
import xyz.pageturner.keylab.Gesture.SHORT

class GestureClassifierTest {
    private val c = GestureClassifier(longPressMs = 700, doubleTapMs = 400)

    @Test fun shortTapFiresOnlyAfterDoubleTapWindow() {
        assertEquals(emptyList<Gesture>(), c.onDown(1000))
        assertEquals(emptyList<Gesture>(), c.onUp(1250))
        assertEquals(1651L, c.nextDeadline())
        assertEquals(emptyList<Gesture>(), c.onTick(1650))
        assertEquals(listOf(SHORT), c.onTick(1651))
        assertNull(c.nextDeadline())
    }

    @Test fun longPressFiresWhileHeldAndNothingOnRelease() {
        c.onDown(1000)
        assertEquals(1700L, c.nextDeadline())
        assertEquals(emptyList<Gesture>(), c.onTick(1699))
        assertEquals(listOf(LONG), c.onTick(1700))
        assertNull(c.nextDeadline())
        assertEquals(emptyList<Gesture>(), c.onUp(4200))
        assertNull(c.nextDeadline())
    }

    @Test fun longPressDetectedOnReleaseIfTickWasLate() {
        c.onDown(1000)
        assertEquals(listOf(LONG), c.onUp(1800))
    }

    @Test fun doubleTapWithinWindow() {
        c.onDown(1000); c.onUp(1200)
        assertEquals(emptyList<Gesture>(), c.onDown(1550)) // 350 ms after first release
        assertNull(c.nextDeadline()) // second press: no long-press deadline
        assertEquals(listOf(DOUBLE), c.onUp(1700))
        assertNull(c.nextDeadline())
    }

    @Test fun secondPressAfterWindowIsTwoShorts() {
        c.onDown(1000); c.onUp(1200)
        // Tick missed (e.g. handler late): the late down flushes the pending SHORT.
        assertEquals(listOf(SHORT), c.onDown(1700))
        c.onUp(1900)
        assertEquals(listOf(SHORT), c.onTick(2301))
    }

    @Test fun upWithoutDownIsIgnored() {
        assertEquals(emptyList<Gesture>(), c.onUp(1000))
        assertNull(c.nextDeadline())
    }

    @Test fun repeatedDownWhileHeldIsIgnored() {
        c.onDown(1000)
        assertEquals(emptyList<Gesture>(), c.onDown(1100))
        assertEquals(1700L, c.nextDeadline()) // still measured from the first down
    }
}
