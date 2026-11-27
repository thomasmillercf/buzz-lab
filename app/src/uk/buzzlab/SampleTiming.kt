package uk.buzzlab

import kotlin.math.max
import kotlin.math.min

class SampleTiming {
    var gaps = 0
        private set

    var longestGapNs = 0L
        private set

    var rateHz = Double.NaN
        private set

    /** The phone's clock (the one GPS fixes use) minus the sensor's own, taken from the quickest delivery. */
    var sensorClockLagNs = 0L
        private set

    private val measured get() = !rateHz.isNaN()
    private var firstSampleNs = 0L
    private var previousSampleNs = 0L
    private var measuredSamples = 0L
    private var smallestLagNs = Long.MAX_VALUE

    /** True once, when the first second of readings has given the rate and the clock lag. */
    fun add(sampleNs: Long, arrivedNs: Long): Boolean {
        if (previousSampleNs != 0L && sampleNs - previousSampleNs > MAX_GAP_NS) {
            gaps++
            longestGapNs = max(longestGapNs, sampleNs - previousSampleNs)
        }
        previousSampleNs = sampleNs
        if (measured) return false
        if (measuredSamples == 0L) firstSampleNs = sampleNs
        measuredSamples++
        smallestLagNs = min(smallestLagNs, arrivedNs - sampleNs)
        val measuredNs = sampleNs - firstSampleNs
        if (measuredNs < MEASURE_NS) return false
        rateHz = (measuredSamples - 1) * 1e9 / measuredNs
        sensorClockLagNs = smallestLagNs
        return true
    }

    fun pause() {
        previousSampleNs = 0
        if (measured) return
        measuredSamples = 0
        smallestLagNs = Long.MAX_VALUE
    }

    companion object {
        const val MAX_GAP_NS = (Dsp.MAX_GAP_S * 1e9).toLong()
        private const val MEASURE_NS = 1_000_000_000L
    }
}
