package uk.buzzlab

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vibration by speed. Sums are stored, so drives combine exactly.
 * Method matches analyse.py: steady 2 s windows, 1 s Hann segments.
 */
class Dsp {
    @JvmField val psd = Array(NB) { DoubleArray(NF) }

    @JvmField val ax = Array(NB) { DoubleArray(3) }

    @JvmField val n = IntArray(NB)

    /** For each speed bin, how many windows fell in each strength step at 20 to 50 Hz. Shows spread, not just the average. */
    @JvmField val h = Array(NB) { IntArray(NH) }

    /** For each speed bin and frequency band, how many windows fell in each strength step, which shows the bursts an average hides. */
    @JvmField val bandLevelCounts = Array(NB) { Array(BANDS) { IntArray(LEVEL_STEPS) } }

    @JvmField var maxMph = 0.0

    @JvmField var km = 0.0

    fun add(o: Dsp) {
        for (b in 0 until NB) {
            n[b] += o.n[b]
            for (f in 0 until NF) psd[b][f] += o.psd[b][f]
            for (a in 0 until 3) ax[b][a] += o.ax[b][a]
            for (k in 0 until NH) h[b][k] += o.h[b][k]
            for (band in 0 until BANDS) {
                for (step in 0 until LEVEL_STEPS) bandLevelCounts[b][band][step] += o.bandLevelCounts[b][band][step]
            }
        }
        maxMph = max(maxMph, o.maxMph)
        km += o.km
    }

    /** Each band's counts as [first step, count, count, …], or empty where it has none, so the unused steps take no room. */
    private fun bandLevelsJson(): JSONArray {
        val bySpeedBin = JSONArray()
        for (speedBin in 0 until NB) {
            val byBand = JSONArray()
            for (counts in bandLevelCounts[speedBin]) {
                val run = JSONArray()
                val firstStep = counts.indexOfFirst { it > 0 }
                if (firstStep >= 0) {
                    run.put(firstStep)
                    for (step in firstStep..counts.indexOfLast { it > 0 }) run.put(counts[step])
                }
                byBand.put(run)
            }
            bySpeedBin.put(byBand)
        }
        return bySpeedBin
    }

    fun json(): JSONObject {
        val counts = JSONArray()
        val spectra = JSONArray()
        val axes = JSONArray()
        val hist = JSONArray()
        for (b in 0 until NB) {
            hist.put(JSONArray().apply { h[b].forEach { put(it) } })
            counts.put(n[b])
            val p = JSONArray()
            val q = JSONArray()
            if (n[b] > 0) {
                psd[b].forEach { p.put(it.toFloat().toDouble()) }
                ax[b].forEach { q.put(it.toFloat().toDouble()) }
            }
            spectra.put(p)
            axes.put(q)
        }
        return JSONObject().put("v", V).put("n", counts).put("psd", spectra).put("ax", axes).put("h", hist)
            .put("bandLevels", bandLevelsJson()).put("maxMph", maxMph).put("km", km)
    }

    /** Mean PSD over a speed range, or null when there was no steady driving in it. */
    fun mean(loMph: Int, hiMph: Int): DoubleArray? {
        val s = DoubleArray(NF)
        var c = 0
        for (b in bins(loMph, hiMph)) {
            if (n[b] > 0) {
                c += n[b]
                for (f in 0 until NF) s[f] += psd[b][f]
            }
        }
        if (c == 0) return null
        for (f in 0 until NF) s[f] /= c.toDouble()
        return s
    }

    fun count(lo: Int, hi: Int): Int = bins(lo, hi).sumOf { n[it] }

    /** Windows in speed bin b with spread counted. Drives analysed before spread was kept have none. */
    fun spreadCount(b: Int): Int = h[b].sum()

    /**
     * The q-th share (0 to 1) of window strengths at 20 to 50 Hz in speed bin b, in m/s²; NaN when there are none.
     * Read off the steps, interpolating within one on a log scale.
     */
    fun pct(b: Int, q: Double): Double = levelUnder(q, h[b], H_LO, HPO)

    /** Windows in a speed range whose band levels were counted. Stats worked out before version 4 have none. */
    fun bandLevelWindows(loMph: Int, hiMph: Int): Int = bins(loMph, hiMph).sumOf { bandLevelCounts[it][0].sum() }

    /** For each band, the strength in m/s²/√Hz that a share (0 to 1) of windows stayed under, so 0.99 is the strongest 1%. Null with no levels counted. */
    fun bandLevelsAt(share: Double, loMph: Int, hiMph: Int): DoubleArray? = bandLevelsAcross(bins(loMph, hiMph), share)

    fun bandLevelsInSpeedBin(share: Double, speedBin: Int): DoubleArray? = bandLevelsAcross(listOf(speedBin), share)

    private fun bandLevelsAcross(speedBins: List<Int>, share: Double): DoubleArray? {
        val combinedCounts = IntArray(LEVEL_STEPS)
        val levels = DoubleArray(BANDS)
        for (band in 0 until BANDS) {
            combinedCounts.fill(0)
            for (speedBin in speedBins) {
                for (step in 0 until LEVEL_STEPS) combinedCounts[step] += bandLevelCounts[speedBin][band][step]
            }
            levels[band] = levelUnder(share, combinedCounts, LEVEL_LOWEST, LEVEL_STEPS_PER_DOUBLING)
        }
        val nothingCounted = levels[0].isNaN()
        return if (nothingCounted) null else levels
    }

    /** Share of 25 to 35 Hz along the phone's length. */
    fun foreAft(lo: Int, hi: Int): Double {
        var y = 0.0
        var t = 0.0
        for (b in bins(lo, hi)) {
            y += ax[b][1]
            t += ax[b][0] + ax[b][1] + ax[b][2]
        }
        return if (t > 0) y / t else 0.0
    }

    /** Readings in seconds from the first one, and GPS rows of (seconds, lat, lon, m/s, accuracy). */
    class Raw(@JvmField val t: DoubleArray, @JvmField val v: Array<FloatArray>, @JvmField val gps: Array<DoubleArray>)

    companion object {
        const val F0 = 2
        const val F1 = 230
        const val NF = F1 - F0 + 1
        const val NB = 16
        const val MPH = 2.23694

        /** Stats layout version; older caches are worked out again when the raw readings are there. */
        const val V = 4

        /** Spread steps: HPO per doubling, from H_LO m/s² up. Values outside go in the end steps. */
        const val NH = 24
        private const val HPO = 3
        private const val H_LO = .01

        @JvmStatic fun hEdge(k: Int): Double = H_LO * 2.0.pow(k.toDouble() / HPO)

        @JvmStatic fun hBin(r: Double): Int = if (r <= H_LO) 0 else min(NH - 1, floor(HPO * ln(r / H_LO) / ln(2.0)).toInt())

        /** Frequency bands a twelfth of an octave wide, from 2 Hz to about 220 Hz. */
        const val BANDS = 82

        @JvmStatic fun bandCentre(band: Int): Double = 2 * 2.0.pow(band / 12.0)

        @JvmStatic fun bandLowerEdge(band: Int): Double = max(2.0, bandCentre(band) * 2.0.pow(-1 / 24.0))

        /** Mean power in one band of a spectrum with a value per whole Hz. A band too narrow to hold a whole Hz reads between the two nearest. */
        @JvmStatic fun bandPower(spectrum: DoubleArray, band: Int): Double {
            var sum = 0.0
            var wholeHzInBand = 0
            var hz = ceil(bandLowerEdge(band)).toInt()
            while (hz < bandLowerEdge(band + 1) && hz <= F1) {
                sum += spectrum[hz - F0]
                wholeHzInBand++
                hz++
            }
            if (wholeHzInBand > 0) return sum / wholeHzInBand
            val centre = min(F1 - 1.0, bandCentre(band))
            val hzBelow = centre.toInt()
            return spectrum[hzBelow - F0] * (hzBelow + 1 - centre) + spectrum[hzBelow + 1 - F0] * (centre - hzBelow)
        }

        /** Band level steps: LEVEL_STEPS_PER_DOUBLING per doubling, from LEVEL_LOWEST m/s²/√Hz up. Levels outside go in the end steps. */
        const val LEVEL_STEPS = 104
        private const val LEVEL_STEPS_PER_DOUBLING = 8
        private const val LEVEL_LOWEST = .0005

        @JvmStatic fun levelStep(level: Double): Int {
            if (level <= LEVEL_LOWEST) return 0
            val doublingsAboveLowest = ln(level / LEVEL_LOWEST) / ln(2.0)
            return min(LEVEL_STEPS - 1, floor(LEVEL_STEPS_PER_DOUBLING * doublingsAboveLowest).toInt())
        }

        /** The level a share of the counted windows stayed under, interpolated within a step on a log scale. NaN when none were counted. */
        private fun levelUnder(share: Double, counts: IntArray, lowestLevel: Double, stepsPerDoubling: Int): Double {
            val total = counts.sum()
            if (total == 0) return Double.NaN
            val windowsWanted = share * total
            var windowsBelow = 0.0
            for (step in counts.indices) {
                if (counts[step] == 0) continue
                if (windowsBelow + counts[step] >= windowsWanted) {
                    val partWayThroughStep = ((windowsWanted - windowsBelow) / counts[step]).coerceIn(0.0, 1.0)
                    return lowestLevel * 2.0.pow((step + partWayThroughStep) / stepsPerDoubling)
                }
                windowsBelow += counts[step]
            }
            return lowestLevel * 2.0.pow(counts.size.toDouble() / stepsPerDoubling)
        }

        /** Band levels drawn out as power at each whole Hz from F0, so they chart the same way as a mean spectrum. */
        @JvmStatic fun spectrumFromBandLevels(levels: DoubleArray): DoubleArray = DoubleArray(NF) { index ->
            val hz = F0 + index
            val bandPosition = (12 * ln(hz / 2.0) / ln(2.0)).coerceIn(0.0, BANDS - 1.0)
            val bandBelow = min(BANDS - 2, bandPosition.toInt())
            val partWayToNextBand = bandPosition - bandBelow
            val level = levels[bandBelow] * (1 - partWayToNextBand) + levels[bandBelow + 1] * partWayToNextBand
            level * level
        }

        private fun readBandLevels(saved: JSONArray?, into: Dsp) {
            if (saved == null) return
            for (speedBin in 0 until min(NB, saved.length())) {
                val byBand = saved.optJSONArray(speedBin) ?: continue
                for (band in 0 until min(BANDS, byBand.length())) {
                    val run = byBand.optJSONArray(band) ?: continue
                    if (run.length() == 0) continue
                    val firstStep = run.getInt(0)
                    for (position in 1 until run.length()) {
                        val step = firstStep + position - 1
                        if (step in 0 until LEVEL_STEPS) into.bandLevelCounts[speedBin][band][step] = run.getInt(position)
                    }
                }
            }
        }

        /** Speed bins wholly inside lo..hi mph. */
        private fun bins(lo: Int, hi: Int) = (0 until NB).filter { it * 5 >= lo && it * 5 + 5 <= hi }

        @JvmStatic fun bin(mph: Double): Int = if (mph < 0 || (mph / 5).toInt() >= NB) -1 else (mph / 5).toInt()

        @JvmStatic fun rms(p: DoubleArray, a: Int, b: Int): Double {
            var s = 0.0
            for (h in a until b) if (h in F0..F1) s += p[h - F0]
            return sqrt(s)
        }

        @JvmStatic fun peak(p: DoubleArray, a: Int, b: Int): Int {
            var k = a
            for (h in a..b) if (p[h - F0] > p[k - F0]) k = h
            return k
        }

        @JvmStatic
        @Throws(JSONException::class)
        fun from(o: JSONObject): Dsp {
            val d = Dsp()
            for (b in 0 until NB) {
                d.n[b] = o.getJSONArray("n").getInt(b)
                val p = o.getJSONArray("psd").getJSONArray(b)
                val q = o.getJSONArray("ax").getJSONArray(b)
                if (d.n[b] > 0 && p.length() != NF) throw JSONException("older stats")
                for (f in 0 until p.length()) d.psd[b][f] = p.getDouble(f)
                for (a in 0 until q.length()) d.ax[b][a] = q.getDouble(a)
                o.optJSONArray("h")?.optJSONArray(b)?.let { hb -> for (k in 0 until min(NH, hb.length())) d.h[b][k] = hb.getInt(k) }
            }
            readBandLevels(o.optJSONArray("bandLevels"), d)
            d.maxMph = o.optDouble("maxMph")
            d.km = o.optDouble("km")
            return d
        }

        /**
         * Cached stats if they're current, else worked out from the raw files and cached.
         * An older cache is used as it is when the raw readings can't be read again.
         */
        @JvmStatic
        @Throws(Exception::class)
        fun load(dir: File): Dsp {
            val cache = File(dir, "stats.json")
            val old = Store.read(cache)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (old != null && old.optInt("v") >= V) runCatching { return from(old) }
            return try {
                val r = raw(dir)
                analyse(r.t, r.v, r.gps).also { Store.write(cache, it.json().toString()) }
            } catch (x: Exception) {
                old?.let { runCatching { from(it) }.getOrNull() } ?: throw x
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun raw(dir: File): Raw {
            val f = File(dir, "acc.bin")
            val count = (f.length() / 20).toInt()
            val t = DoubleArray(count)
            val v = Array(count) { FloatArray(3) }
            var base = 0L
            DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16)).use { input ->
                for (i in 0 until count) {
                    val ns = input.readLong()
                    if (i == 0) base = ns
                    t[i] = (ns - base) / 1e9
                    for (a in 0 until 3) v[i][a] = input.readFloat()
                }
            }
            val gps = File(dir, "gps.csv").readLines().drop(1).mapNotNull { line ->
                val p = line.split(",")
                if (p.size < 5 || p[3].isEmpty()) {
                    null
                } else {
                    doubleArrayOf((p[0].toLong() - base) / 1e9, 0.0, 0.0, p[3].toDouble(), p[4].toDouble())
                }
            }.toTypedArray()
            return Raw(t, v, gps)
        }

        @JvmStatic
        fun analyse(t: DoubleArray, v: Array<FloatArray>, gps: Array<DoubleArray>): Dsp {
            val d = Dsp()
            val m = t.size
            if (m < 2000 || gps.size < 3) return d
            val gaps = DoubleArray(min(m - 1, 20000)) { t[it + 1] - t[it] }.apply { sort() }
            val fs = 1 / gaps[gaps.size / 2]
            val w = (2 * fs).toInt()
            val seg = fs.roundToInt()
            val win = DoubleArray(seg) { .5 - .5 * cos(2 * PI * it / seg) }
            val sw = win.sumOf { it * it }
            val co = Array(NF) { f -> DoubleArray(seg) { cos(2 * PI * (F0 + f) * it / fs) } }
            val si = Array(NF) { f -> DoubleArray(seg) { sin(2 * PI * (F0 + f) * it / fs) } }

            for (i in 1 until gps.size) {
                val speed = gps[i][3]
                if (!speed.isNaN()) d.maxMph = max(d.maxMph, speed * MPH)
                val dt = gps[i][0] - gps[i - 1][0]
                if (!speed.isNaN() && dt < 5) d.km += speed * dt / 1000
            }

            val t0 = max(t[0], gps[0][0])
            val t1 = min(t[m - 1], gps[gps.size - 1][0])
            val x = Array(3) { DoubleArray(w) }
            var ai = 0
            var s = t0
            while (s + 2 < t1) {
                val start = s
                s += 2
                val mph = steadySpeed(gps, start) ?: continue
                val b = bin(mph)
                if (b < 0) continue
                while (ai < m - 2 && t[ai + 1] < start) ai++
                if (!resample(t, v, ai, start, fs, x)) continue

                val p = DoubleArray(NF)
                val pa = DoubleArray(3)
                var segments = 0
                var o = 0
                while (o + seg <= w) {
                    segments++
                    for (a in 0 until 3) {
                        var me = 0.0
                        for (i in 0 until seg) me += x[a][o + i]
                        me /= seg
                        for (f in 0 until NF) {
                            var re = 0.0
                            var im = 0.0
                            for (i in 0 until seg) {
                                val z = (x[a][o + i] - me) * win[i]
                                re += z * co[f][i]
                                im += z * si[f][i]
                            }
                            val power = 2 * (re * re + im * im) / (fs * sw)
                            p[f] += power
                            if (F0 + f in 25 until 35) pa[a] += power
                        }
                    }
                    o += seg / 2
                }
                var band = 0.0
                for (f in 0 until NF) {
                    d.psd[b][f] += p[f] / segments
                    if (F0 + f in 20 until 50) band += p[f] / segments
                }
                d.h[b][hBin(sqrt(band))]++
                val windowSpectrum = DoubleArray(NF) { p[it] / segments }
                for (frequencyBand in 0 until BANDS) {
                    val level = sqrt(bandPower(windowSpectrum, frequencyBand))
                    d.bandLevelCounts[b][frequencyBand][levelStep(level)]++
                }
                for (a in 0 until 3) d.ax[b][a] += pa[a] / segments
                d.n[b]++
            }
            return d
        }

        /** Mean mph over the 2 s from start, or null if GPS is missing or speed varied by 2 mph or more. */
        private fun steadySpeed(gps: Array<DoubleArray>, start: Double): Double? {
            var sum = 0.0
            var sq = 0.0
            var k = 0
            var q = 0
            var u = start
            while (u < start + 2) {
                while (q < gps.size - 2 && gps[q + 1][0] < u) q++
                val a = gps[q][3]
                val b = gps[q + 1][3]
                if (a.isNaN() || b.isNaN() || gps[q + 1][0] - gps[q][0] > 4) return null
                val frac = (u - gps[q][0]) / (gps[q + 1][0] - gps[q][0])
                val z = (a + (b - a) * max(0.0, min(1.0, frac))) * MPH
                sum += z
                sq += z * z
                k++
                u += .1
            }
            val mean = sum / k
            return if (sqrt(max(0.0, sq / k - mean * mean)) > 2) null else mean
        }

        /** Readings onto an even grid; false if there's a gap over 50 ms. */
        private fun resample(t: DoubleArray, v: Array<FloatArray>, from: Int, start: Double, fs: Double, x: Array<DoubleArray>): Boolean {
            var j = from
            for (i in x[0].indices) {
                val u = start + i / fs
                while (j < t.size - 2 && t[j + 1] < u) j++
                val h = t[j + 1] - t[j]
                if (h > .05) return false
                val frac = (u - t[j]) / h
                for (a in 0 until 3) x[a][i] = v[j][a] + (v[j + 1][a] - v[j][a]) * frac
            }
            return true
        }
    }
}
