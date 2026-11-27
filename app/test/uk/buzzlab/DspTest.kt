package uk.buzzlab

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
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
    fun `GPS times move onto the sensor's clock by the drive's clock lag`() {
        val dir = Fixtures.tempDir()
        Fixtures.drive(dir)
        assertEquals(0.0, Dsp.raw(dir).gps[0][0], 1e-12)
        File(dir, "meta.json").writeText("""{"id":"1","sensorClockLagNs":3000000000}""")
        assertEquals(-3.0, Dsp.raw(dir).gps[0][0], 1e-12)
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

    /** The band a twelfth of an octave wide that holds 30 Hz: it runs from 29.3 to 31.1 Hz. */
    private val bandAt30Hz = 47

    /** 200 s at 40 mph, quiet except for a 30 Hz buzz of amplitude 0.5 through the first 20 s. */
    private fun analysedWithABuzzInTheFirstTenth(): Dsp {
        val seconds = 200
        val readings = (seconds * Fixtures.FS).toInt()
        val noise = Random(1)
        val times = DoubleArray(readings) { it / Fixtures.FS }
        val acceleration = Array(readings) { index ->
            val buzz = if (times[index] < 20) .5 * sin(2 * PI * 30 * times[index]) else 0.0
            floatArrayOf(
                (.02 * noise.nextGaussian()).toFloat(),
                (.02 * noise.nextGaussian() + buzz).toFloat(),
                (.02 * noise.nextGaussian() + 9.81).toFloat(),
            )
        }
        val gps = Array(seconds + 1) { second -> doubleArrayOf(second.toDouble(), 0.0, 0.0, 40 / Dsp.MPH, 5.0) }
        return Dsp.analyse(times, acceleration, gps)
    }

    @Test
    fun `a band's power is the mean of the whole Hz it holds`() {
        val spectrum = DoubleArray(Dsp.NF)
        spectrum[30 - Dsp.F0] = .6
        spectrum[31 - Dsp.F0] = .2
        assertEquals(.4, Dsp.bandPower(spectrum, bandAt30Hz), 1e-12)
    }

    @Test
    fun `a band too narrow to hold a whole Hz reads between the two nearest`() {
        val spectrum = DoubleArray(Dsp.NF)
        spectrum[2 - Dsp.F0] = 1.0
        spectrum[3 - Dsp.F0] = 2.0
        assertEquals(1.119, Dsp.bandPower(spectrum, 1), .001, "band 1 is centred on 2.119 Hz")
    }

    @Test
    fun `a steady buzz is as strong in a typical window as in the strongest`() {
        val d = analysed()
        assertEquals(.23, d.bandLevelsAt(.5, 40, 45)!![bandAt30Hz], .04, "a sine of amplitude 0.5 reads 0.23 in its band")
        assertEquals(.23, d.bandLevelsAt(.99, 40, 45)!![bandAt30Hz], .04)
    }

    @Test
    fun `a buzz in one window in ten shows in the strongest 1 percent but not in a typical window`() {
        val d = analysedWithABuzzInTheFirstTenth()
        assertEquals(.23, d.bandLevelsAt(.99, 40, 45)!![bandAt30Hz], .04)
        assertTrue(d.bandLevelsAt(.5, 40, 45)!![bandAt30Hz] < .01, "the typical window holds only noise")
    }

    @Test
    fun `one speed bin gives the same levels as a range that holds only it`() {
        val d = analysed()
        assertArrayEquals(d.bandLevelsAt(.9, 40, 45), d.bandLevelsInSpeedBin(.9, 8), 0.0)
        assertNull(d.bandLevelsInSpeedBin(.9, 3), "nothing was driven at 15 to 20 mph")
    }

    @Test
    fun `every steady window has its band levels counted`() {
        val d = analysed()
        assertEquals(d.count(40, 45), d.bandLevelWindows(40, 45))
    }

    @Test
    fun `band levels survive a round trip through json`() {
        val d = analysed()
        val e = Dsp.from(JSONObject(d.json().toString()))
        for (band in 0 until Dsp.BANDS) assertArrayEquals(d.bandLevelCounts[8][band], e.bandLevelCounts[8][band], "band $band")
    }

    @Test
    fun `band levels add up across drives`() {
        val a = analysed()
        val total = Dsp()
        total.add(a)
        total.add(a)
        assertEquals(2 * a.bandLevelWindows(40, 45), total.bandLevelWindows(40, 45))
        assertArrayEquals(a.bandLevelsAt(.9, 40, 45), total.bandLevelsAt(.9, 40, 45), 1e-12)
    }

    @Test
    fun `stats saved before band levels were kept have none`() {
        val old = analysed().json().apply { remove("bandLevels") }
        val d = Dsp.from(old)
        assertEquals(0, d.bandLevelWindows(40, 45))
        assertNull(d.bandLevelsAt(.9, 40, 45))
    }

    @Test
    fun `stats from an older version are worked out again from the raw readings`() {
        val dir = Fixtures.tempDir()
        Fixtures.drive(dir)
        val old = Dsp.load(dir).json().put("v", 3).apply { remove("bandLevels") }
        Store.write(File(dir, "stats.json"), old.toString())
        assertTrue(Dsp.load(dir).bandLevelWindows(40, 45) > 30)
    }

    @Test
    fun `band levels drawn as a spectrum hold their power at each frequency`() {
        val spectrum = Dsp.spectrumFromBandLevels(DoubleArray(Dsp.BANDS) { .2 })
        assertEquals(Dsp.NF, spectrum.size)
        assertEquals(.04, spectrum[30 - Dsp.F0], 1e-12)
        assertEquals(.04, spectrum[230 - Dsp.F0], 1e-12)
    }
}
