package uk.buzzlab

import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

/** The live charts while recording, and the calibration maths for the Feel tab. */
class LiveAndCalTest {
    private fun feed(live: Live, from: Int, seconds: Int, hz: Double) {
        for (i in from until from + (seconds * Fixtures.FS).toInt()) {
            val t = i / Fixtures.FS
            live.put(Fixtures.BASE + (t * 1e9).toLong(), 0f, (.5 * sin(2 * PI * hz * t)).toFloat(), 9.81f)
        }
    }

    @Test
    fun `live charts find the buzz and fill the heat map once speed is steady`() {
        val live = Live()
        var at = 0
        // The first 3 s fill the buffer, and steady needs two readings in a row, so 8 s gives 5 steady seconds.
        for (s in 0 until 8) {
            feed(live, at, 1, 30.0)
            at += Fixtures.FS.toInt()
            live.step(40.0)
        }
        assertEquals(30, live.peak)
        assertNotNull(live.now)
        assertEquals(5, live.sum.n[8], "steady seconds at 40 mph: ${live.sum.n[8]}")
        assertEquals(30, Dsp.peak(live.avg!!, 15, 200))
    }

    @Test
    fun `nothing is drawn before a second of readings`() {
        val live = Live()
        feed(live, 0, 1, 30.0)
        live.step(40.0)
        live.step(40.0)
        assertNull(live.avg)
        assertEquals(0, live.sum.n.sum(), "not enough readings yet, or speed not yet steady")
    }

    @Test
    fun `changing speed doesn't count as steady`() {
        val live = Live()
        var at = 0
        for (mph in listOf(20.0, 30.0, 40.0, 50.0)) {
            feed(live, at, 1, 30.0)
            at += Fixtures.FS.toInt()
            live.step(mph)
        }
        assertEquals(0, live.sum.n.sum())
    }

    /** A 160 Hz motor buzz switched on and off rate times a second, as the accelerometer sees it. */
    private fun pulses(rate: Double, seconds: Double = 2.5): Engine.Rec {
        val r = Engine.Rec()
        for (i in 0 until (seconds * Fixtures.FS).toInt()) {
            val t = i / Fixtures.FS
            val on = (t * rate) % 1.0 < .5
            val buzz = if (on) (.3 * sin(2 * PI * 160 * t)).toFloat() else 0f
            r.t.add(Fixtures.BASE + (t * 1e9).toLong())
            r.v.add(floatArrayOf(buzz, buzz / 2, 9.81f + buzz))
        }
        return r
    }

    @Test
    fun `calibration measures how many pulses a second the motor made`() {
        for (rate in listOf(30.0, 42.0)) {
            val m = Cal.measure(pulses(rate), rate)
            assertEquals(rate, m.peakHz, 1.0, "asked for $rate")
            assertTrue(m.rms > 0)
        }
    }

    @Test
    fun `too short a recording measures nothing`() {
        assertEquals(0.0, Cal.measure(pulses(30.0, .3), 30.0).rms)
    }

    private val curve = JSONArray("[[0.1,0.05],[0.5,0.3],[1.0,0.6]]")

    @Test
    fun `strength setting is read off the calibration curve`() {
        assertEquals(.6, Cal.top(curve), 1e-12)
        assertEquals(.3, Cal.gainFor(curve, .175), 1e-9, "halfway between the first two points")
        assertEquals(.5, Cal.gainFor(curve, .3), 1e-9)
        assertEquals(1.0, Cal.gainFor(curve, 5.0), 1e-9, "beyond the curve, the strongest setting")
        assertEquals(.1, Cal.gainFor(JSONArray("[\"x\"]"), .2), 1e-12, "a broken curve falls back to gentle")
        assertEquals(0.0, Cal.top(JSONArray()), 1e-12)
    }

    @Test
    fun `a spec is read from text with defaults for the rest`() {
        val s = Engine.Spec.parse("sine:42:180")
        assertEquals("sine", s.shape)
        assertEquals(42.0, s.rate)
        assertEquals(180.0, s.carrier)
        assertEquals(.5, s.duty)
        assertEquals(4.0, s.sec)
    }

    @Test
    fun `a loop is one second of three channels and a clip fades in`() {
        val s = Engine.Spec.parse("sine:30:160:.5:0:1:0:220:2")
        assertEquals(Engine.SR * 3, Engine.renderLoop(s).size)
        val clip = Engine.render(s)
        assertEquals(2 * Engine.SR * 3, clip.size)
        assertEquals(0, clip[2].toInt(), "the motor channel starts silent")
    }
}
