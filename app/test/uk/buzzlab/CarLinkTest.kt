package uk.buzzlab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.buzzlab.CarRule.Act

/** Starting and saving drives around Android Auto. */
class CarLinkTest {
    private val rule = CarRule(graceMs = 20_000)

    private fun step(t: Long, car: Boolean, watching: Boolean = true, recording: Boolean = false, auto: Boolean = true, top: Double = 40.0) =
        rule.step(t * 1000, car, watching, recording, auto, top)

    @Test
    fun `connecting starts a drive once, and a disconnect saves it after the grace period`() {
        assertEquals(Act.NONE, step(0, false))
        assertEquals(Act.START, step(1, true))
        assertTrue(rule.car)
        assertEquals(Act.NONE, step(2, true, recording = true), "no second start")
        assertEquals(Act.NONE, step(100, false, recording = true), "disconnect seen, waiting")
        assertEquals(Act.NONE, step(119, false, recording = true))
        assertEquals(Act.SAVE, step(120, false, recording = true))
        assertFalse(rule.car)
        assertEquals(Act.NONE, step(121, false), "saved once only")
    }

    @Test
    fun `a short blip doesn't end the drive`() {
        step(0, true)
        assertEquals(Act.NONE, step(10, false, recording = true))
        assertEquals(Act.NONE, step(15, true, recording = true))
        assertEquals(Act.NONE, step(40, false, recording = true), "the clock starts again from this disconnect")
        assertEquals(Act.NONE, step(55, false, recording = true))
        assertEquals(Act.SAVE, step(60, false, recording = true))
    }

    @Test
    fun `if the car never moved, nothing is kept`() {
        step(0, true)
        step(60, false, recording = true, top = 2.0)
        assertEquals(Act.DISCARD, step(80, false, recording = true, top = 2.0))
    }

    @Test
    fun `a drive started by hand is left for the driver to stop`() {
        step(0, true, recording = true, auto = false)
        step(60, false, recording = true, auto = false)
        assertEquals(Act.DISCONNECTED, step(80, false, recording = true, auto = false))
    }

    @Test
    fun `nothing starts when automatic recording is off, or a drive is already going`() {
        assertEquals(Act.CONNECTED, step(0, true, watching = false))
        val other = CarRule()
        assertEquals(Act.CONNECTED, other.step(0, true, true, true, true, 30.0), "already recording, started by speed")
    }

    @Test
    fun `a drive started by speed is saved by the disconnect too`() {
        assertEquals(Act.CONNECTED, step(0, true, recording = true))
        step(500, false, recording = true)
        assertEquals(Act.SAVE, step(520, false, recording = true))
    }
}
