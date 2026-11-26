package uk.buzzlab

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Rolling one-second spectra while recording. Reads a copy of the latest readings on its own thread. Display only; nothing here is saved. */
class Live {
    private val ring = Array(3) { FloatArray(RN) }
    private val times = LongArray(RN)

    @Volatile var count = 0L
        private set

    /** Every steady second, by speed, for the live heat map. */
    @JvmField val sum = Dsp()

    /** Latest spectrum, smoothed over a few seconds. */
    @Volatile @JvmField
    var now: DoubleArray? = null

    /** Average spectrum while steady. */
    @Volatile @JvmField
    var avg: DoubleArray? = null

    @Volatile @JvmField
    var peak = 0

    @Volatile @JvmField
    var band = 0.0

    @Volatile @JvmField
    var version = 0L

    private var fs = 0.0
    private var seg = 0
    private lateinit var win: DoubleArray
    private var sw = 0.0
    private lateinit var co: Array<DoubleArray>
    private lateinit var si: Array<DoubleArray>
    private var lastMph = Double.NaN
    private val total = DoubleArray(Dsp.NF)
    private var totalN = 0

    /** Called on the recording thread for every reading. */
    fun put(t: Long, x: Float, y: Float, z: Float) {
        val i = (count and (RN - 1).toLong()).toInt()
        ring[0][i] = x
        ring[1][i] = y
        ring[2][i] = z
        times[i] = t
        count++
    }

    /** About once a second, on the chart thread. */
    fun step(mph: Double) {
        val n = count
        if (n < 1200) return
        if (seg == 0 && !setUp(n)) return
        val x = Array(3) { a ->
            val v = DoubleArray(seg) { ring[a][at(n - seg + it)].toDouble() }
            val mean = v.average()
            DoubleArray(seg) { (v[it] - mean) * win[it] }
        }
        val p = DoubleArray(Dsp.NF)
        val pa = DoubleArray(3)
        for (a in 0 until 3) {
            for (f in 0 until Dsp.NF) {
                var re = 0.0
                var im = 0.0
                for (i in 0 until seg) {
                    re += x[a][i] * co[f][i]
                    im += x[a][i] * si[f][i]
                }
                val power = 2 * (re * re + im * im) / (fs * sw)
                p[f] += power
                if (Dsp.F0 + f in 25 until 35) pa[a] += power
            }
        }
        val old = now
        val e = DoubleArray(Dsp.NF) { if (old == null) p[it] else .65 * old[it] + .35 * p[it] }
        now = e
        peak = Dsp.peak(e, 15, 200)
        band = Dsp.rms(e, 25, 35)
        val steady = !mph.isNaN() && !lastMph.isNaN() && abs(mph - lastMph) < 2
        lastMph = mph
        val b = if (steady) Dsp.bin(mph) else -1
        if (b >= 1) {
            for (f in 0 until Dsp.NF) {
                sum.psd[b][f] += p[f]
                total[f] += p[f]
            }
            for (a in 0 until 3) sum.ax[b][a] += pa[a]
            sum.n[b]++
            totalN++
            avg = DoubleArray(Dsp.NF) { total[it] / totalN }
        }
        version++
    }

    private fun at(i: Long) = (i and (RN - 1).toLong()).toInt()

    /** Measures the reading rate once and prepares the window and sine tables. */
    private fun setUp(n: Long): Boolean {
        val seconds = (times[at(n - 1)] - times[at(n - 1001)]) / 1e9
        fs = 1000 / seconds
        if (fs < 300 || fs > 1000) return false
        seg = fs.roundToInt()
        win = DoubleArray(seg) { .5 - .5 * cos(2 * PI * it / seg) }
        sw = win.sumOf { it * it }
        co = Array(Dsp.NF) { f -> DoubleArray(seg) { cos(2 * PI * (Dsp.F0 + f) * it / fs) } }
        si = Array(Dsp.NF) { f -> DoubleArray(seg) { sin(2 * PI * (Dsp.F0 + f) * it / fs) } }
        return true
    }

    private companion object {
        const val RN = 4096
    }
}
