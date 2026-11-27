package uk.buzzlab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SampleTimingTest {
    private val sensorClockStartNs = 5_000_000_000L
    private val twoMillisecondsNs = 2_000_000L

    /** Readings every 2 ms; deliveries alternate between 7 ms and 10 ms late. Returns the indexes where add() was true. */
    private fun feed(timing: SampleTiming, readings: Int): List<Int> = (0 until readings).filter { index ->
        val sampleNs = sensorClockStartNs + index * twoMillisecondsNs
        val deliveryDelayNs = if (index % 2 == 0) 10_000_000L else 7_000_000L
        timing.add(sampleNs, sampleNs + deliveryDelayNs)
    }

    @Test
    fun `the first second gives the reading rate, once`() {
        val timing = SampleTiming()
        assertTrue(timing.rateHz.isNaN())
        assertEquals(listOf(500), feed(timing, 2000))
        assertEquals(500.0, timing.rateHz, 1e-9)
    }

    @Test
    fun `the clock lag is the quickest delivery`() {
        val timing = SampleTiming()
        feed(timing, 2000)
        assertEquals(7_000_000L, timing.sensorClockLagNs)
    }

    @Test
    fun `a sensor on another clock shows as a large lag`() {
        val timing = SampleTiming()
        val phoneClockAheadNs = 3_600_000_000_000L
        for (index in 0..500) {
            val sampleNs = sensorClockStartNs + index * twoMillisecondsNs
            timing.add(sampleNs, sampleNs + phoneClockAheadNs)
        }
        assertEquals(3_600_000_000_000L, timing.sensorClockLagNs)
    }

    @Test
    fun `only waits longer than 50 ms count as gaps`() {
        val timing = SampleTiming()
        var sampleNs = sensorClockStartNs
        for (waitMs in listOf(0L, 2, 50, 51, 2, 80, 2)) {
            sampleNs += waitMs * 1_000_000
            timing.add(sampleNs, sampleNs)
        }
        assertEquals(2, timing.gaps)
        assertEquals(80_000_000L, timing.longestGapNs)
    }

    @Test
    fun `a pause is not a gap`() {
        val timing = SampleTiming()
        timing.add(sensorClockStartNs, sensorClockStartNs)
        timing.pause()
        val afterPauseNs = sensorClockStartNs + 60_000_000_000L
        timing.add(afterPauseNs, afterPauseNs)
        assertEquals(0, timing.gaps)
    }

    @Test
    fun `a pause in the first second starts the measurement again`() {
        val timing = SampleTiming()
        feed(timing, 100)
        timing.pause()
        val afterPauseNs = sensorClockStartNs + 60_000_000_000L
        for (index in 0..500) {
            val sampleNs = afterPauseNs + index * twoMillisecondsNs
            timing.add(sampleNs, sampleNs)
        }
        assertEquals(500.0, timing.rateHz, 1e-9)
    }
}
