package uk.buzzlab

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.math.PI
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

    @JvmField var maxMph = 0.0

    @JvmField var km = 0.0

    fun add(o: Dsp) {
        for (b in 0 until NB) {
            n[b] += o.n[b]
            for (f in 0 until NF) psd[b][f] += o.psd[b][f]
            for (a in 0 until 3) ax[b][a] += o.ax[b][a]
            for (k in 0 until NH) h[b][k] += o.h[b][k]
        }
        maxMph = max(maxMph, o.maxMph)
        km += o.km
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
            .put("maxMph", maxMph).put("km", km)
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
    fun pct(b: Int, q: Double): Double {
        val total = spreadCount(b)
        if (total == 0) return Double.NaN
        val want = q * total
        var c = 0.0
        for (k in 0 until NH) {
            if (h[b][k] == 0) continue
            if (c + h[b][k] >= want) {
                val frac = ((want - c) / h[b][k]).coerceIn(0.0, 1.0)
                return hEdge(k) * 2.0.pow(frac / HPO)
            }
            c += h[b][k]
        }
        return hEdge(NH)
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
        const val V = 3

        /** Spread steps: HPO per doubling, from H_LO m/s² up. Values outside go in the end steps. */
        const val NH = 24
        private const val HPO = 3
        private const val H_LO = .01

        @JvmStatic fun hEdge(k: Int): Double = H_LO * 2.0.pow(k.toDouble() / HPO)

        @JvmStatic fun hBin(r: Double): Int = if (r <= H_LO) 0 else min(NH - 1, floor(HPO * ln(r / H_LO) / ln(2.0)).toInt())

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
