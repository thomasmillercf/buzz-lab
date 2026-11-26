package uk.buzzlab

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.sqrt

/** The analysis must find a known buzz at the right frequency, speed and strength. */
class DspTest {
    private fun analysed(mph: Double = 40.0, hz: Double = 30.0, amp: Double = .5, axis: Int = 1): Dsp {
        val dir = Fixtures.tempDir()
        Fixtures.drive(dir, mph = mph, hz = hz, amp = amp, axis = axis)
        val r = Dsp.raw(dir)
        return Dsp.analyse(r.t, r.v, r.gps)
    }

    /** The stats cache keeps about 7 significant figures, which is plenty: within one part in a million. */
    private fun close(a: DoubleArray, b: DoubleArray) {
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-6 * Math.abs(a[i]) + 1e-15, "at $i")
    }

    @Test
    fun `speed bins are 5 mph wide and stop at 80`() {
        assertEquals(0, Dsp.bin(0.0))
        assertEquals(0, Dsp.bin(4.99))
        assertEquals(1, Dsp.bin(5.0))
        assertEquals(15, Dsp.bin(79.9))
        assertEquals(-1, Dsp.bin(80.0))
        assertEquals(-1, Dsp.bin(-1.0))
    }

    @Test
    fun `rms and peak read a spectrum`() {
        val p = DoubleArray(Dsp.NF)
        p[30 - Dsp.F0] = .09
        p[31 - Dsp.F0] = .16
        assertEquals(.5, Dsp.rms(p, 25, 35), 1e-12)
        assertEquals(.3, Dsp.rms(p, 25, 31), 1e-12)
        assertEquals(31, Dsp.peak(p, 15, 100))
    }

    @Test
    fun `a 30 Hz buzz at 40 mph lands at 30 Hz in the 40 mph column`() {
        val d = analysed()
        val p = d.mean(40, 45)!!
        assertEquals(30, Dsp.peak(p, 15, 200))
        assertTrue(d.count(40, 45) > 30, "steady windows counted: ${d.count(40, 45)}")
        assertEquals(0, d.count(10, 35), "no other speed has readings")
    }

    @Test
    fun `strength is the buzz's rms`() {
        val d = analysed(amp = .5)
        val rms = Dsp.rms(d.mean(40, 45)!!, 25, 35)
        assertEquals(.5 / sqrt(2.0), rms, .05, "a sine of amplitude 0.5 has rms 0.354")
    }

    @Test
    fun `feel bands give the buzz rate and strength for each 10 mph`() {
        val b = bands(analysed(mph = 44.0, hz = 37.0))
        assertEquals(1, b.size, "only 40 to 50 mph has driving")
        assertEquals(40, b[0].lo)
        assertEquals(37, b[0].rate)
        assertEquals(.5 / sqrt(2.0), b[0].level, .05)
        assertTrue(b[0].secs >= 6)
        assertTrue(bands(Dsp()).isEmpty())
    }

    @Test
    fun `the frequency follows the source, not the speed`() {
        for (mph in listOf(25.0, 55.0)) {
            val d = analysed(mph = mph, hz = 42.0)
            val b = Dsp.bin(mph) * 5
            assertEquals(42, Dsp.peak(d.mean(b, b + 5)!!, 15, 200), "at $mph mph")
        }
    }

    @Test
    fun `fore-aft share tells which way the phone shakes`() {
        assertTrue(analysed(axis = 1).foreAft(40, 45) > .95)
        assertTrue(analysed(axis = 0).foreAft(40, 45) < .05)
    }

    @Test
    fun `speed and distance come from GPS`() {
        val d = analysed()
        assertEquals(40.0, d.maxMph, .5)
        assertEquals(120 * 40 / Dsp.MPH / 1000, d.km, .1)
    }

    @Test
    fun `stats survive a round trip through json`() {
        val d = analysed()
        val e = Dsp.from(d.json())
        assertArrayEquals(d.n, e.n)
        for (b in 0 until Dsp.NB) close(d.psd[b], e.psd[b])
        assertEquals(d.km, e.km, 1e-6)
    }

    @Test
    fun `load caches the stats and still works once the raw readings are gone`() {
        val dir = Fixtures.tempDir()
        Fixtures.drive(dir)
        val first = Dsp.load(dir)
        assertTrue(File(dir, "stats.json").exists())
        File(dir, "acc.bin").delete()
        val second = Dsp.load(dir)
        close(first.mean(40, 45)!!, second.mean(40, 45)!!)
    }

    @Test
    fun `too little data gives empty stats instead of failing`() {
        val dir = Fixtures.tempDir()
        Fixtures.drive(dir, seconds = 2)
        val r = Dsp.raw(dir)
        val d = Dsp.analyse(r.t, r.v, r.gps)
        assertEquals(0, d.n.sum())
    }

    @Test
    fun `drives add up`() {
        val a = analysed()
        val total = Dsp()
        total.add(a)
        total.add(a)
        assertEquals(2 * a.count(0, 80), total.count(0, 80))
        assertArrayEquals(a.mean(40, 45), total.mean(40, 45), 1e-9)
    }
}
