package uk.buzzlab

import org.json.JSONArray
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Self-calibration: plays candidate waveforms, measures them with the phone's own accelerometer, keeps the best. */
class Cal(private val e: Engine, private val usage: Int, private val log: Log) {
    /** One measurement. rms is the buzz level; flutter is how strongly it pulses at the asked rate (m/s2); depth is flutter / level. */
    class M {
        @JvmField var rms = 0.0

        @JvmField var flutter = 0.0

        @JvmField var depth = 0.0

        @JvmField var peakHz = 0.0

        @JvmField var ok = false
    }

    interface Log {
        fun line(s: String)
        fun cancelled(): Boolean
    }

    /** A pulse waveform and how it measured. */
    class Best {
        @JvmField var rate = 0.0

        @JvmField var carrier = 0.0

        @JvmField var duty = 0.0

        @JvmField var brake = 0.0

        @JvmField var bg = 0.0

        @JvmField var gain = 0.0

        @JvmField var m: M = M()

        @JvmField var score = 0.0
        fun spec(sec: Double) = Cal.spec(rate, carrier, duty, brake, bg, gain, sec)
    }

    @JvmField var runs = 0

    @Throws(Exception::class)
    fun test(s: Engine.Spec): M {
        if (log.cancelled()) throw InterruptedException("cancelled")
        runs++
        return measure(e.run(s, usage), s.rate)
    }

    /** Coordinate search in two passes. The score favours strong pulsing at exactly the asked rate. */
    @Throws(Exception::class)
    fun tune(rate: Double): Best {
        var b = Best().apply {
            this.rate = rate
            carrier = 150.0
            duty = .35
            brake = .15
            bg = 1.0
            gain = .6
        }
        eval(b)
        for (pass in 0 until 2) {
            val grid = if (pass == 0) {
                arrayOf(
                    doubleArrayOf(135.0, 140.0, 145.0, 155.0, 160.0, 165.0),
                    doubleArrayOf(.25, .30, .45, .55),
                    doubleArrayOf(0.0, .08, .12, .2, .25),
                    doubleArrayOf(.4, .7),
                    doubleArrayOf(.35, .45, .75, .9),
                )
            } else {
                arrayOf(
                    doubleArrayOf(b.carrier - 3, b.carrier + 3),
                    doubleArrayOf(b.duty - .05, b.duty + .05),
                    doubleArrayOf(max(0.0, b.brake - .04), b.brake + .04),
                    doubleArrayOf(max(.2, b.bg - .2), min(1.0, b.bg + .2)),
                    doubleArrayOf(max(.2, b.gain - .1), min(1.0, b.gain + .1)),
                )
            }
            for (dim in 0 until 5) {
                for (value in grid[dim]) {
                    val c = copy(b)
                    when (dim) {
                        0 -> c.carrier = value
                        1 -> c.duty = value
                        2 -> c.brake = value
                        3 -> c.bg = value
                        else -> c.gain = value
                    }
                    if (c.duty + c.brake > .95 || c.duty < .1) continue
                    eval(c)
                    if (c.score > b.score * 1.02) {
                        log.line("${f(rate, 0)}/s better: ${desc(c)}")
                        b = c
                    }
                }
            }
        }
        val m1 = test(b.spec(2.5))
        val m2 = test(b.spec(2.5))
        b.m = avg(m1, m2).apply { ok = m1.ok && m2.ok }
        log.line("RESULT ${desc(b)}")
        return b
    }

    @Throws(Exception::class)
    fun eval(b: Best) {
        b.m = test(b.spec(1.9))
        b.score = if (b.m.ok) b.m.flutter * sqrt(min(1.0, b.m.depth / .5)) else b.m.flutter * .2
    }

    /** Measured level at a range of strengths, forced to rise, so the intensity slider can work in m/s2. */
    @Throws(Exception::class)
    fun curve(b: Best): JSONArray {
        val out = JSONArray()
        var top = 0.0
        for (g in doubleArrayOf(.02, .04, .08, .15, .3, .5, b.gain)) {
            if (g > b.gain) continue
            val m = test(copy(b).apply { gain = g }.spec(1.9))
            top = max(top, m.rms)
            out.put(JSONArray().put(g).put(top))
            log.line("CURVE ${f(b.rate, 0)}/s gain ${f(g, 2)} level ${f(m.rms, 3)}")
        }
        return out
    }

    companion object {
        /** Measure a recording: a 90 Hz high-pass leaves the motor's buzz, then its power is searched for pulsing between 4 and 80 Hz. */
        @JvmStatic fun measure(r: Engine.Rec, rate: Double): M {
            val m = M()
            val t: LongArray
            val v: Array<FloatArray>
            synchronized(r) {
                t = r.t.toLongArray()
                v = r.v.toTypedArray()
            }
            val n = t.size
            if (n < 300) return m
            val fs = (n - 1) / ((t[n - 1] - t[0]) / 1e9)
            val s = (fs * .75).toInt()
            val q = (n - fs * .35).toInt()
            if (q - s < fs * .6) return m
            val pw = DoubleArray(q - s)
            for (axis in 0 until 3) {
                val mean = v.sumOf { it[axis].toDouble() } / n
                val x = DoubleArray(n) { v[it][axis] - mean }
                highPass(x, fs, 90.0, .541)
                highPass(x, fs, 90.0, 1.307)
                for (i in s until q) pw[i - s] += x[i] * x[i]
            }
            val p0 = pw.average()
            m.rms = sqrt(p0)
            var best = 0.0
            var bestHz = 0.0
            var atRate = 0.0
            var f = 4.0
            while (f <= 80) {
                val p = project(pw, t, s, f, p0)
                if (p > best) {
                    best = p
                    bestHz = f
                }
                if (abs(f - rate) < .13) atRate = p
                f += .25
            }
            m.peakHz = bestHz
            m.flutter = atRate / (2 * m.rms + 1e-9)
            m.depth = m.flutter / (m.rms + 1e-9)
            m.ok = abs(bestHz - rate) <= .75
            return m
        }

        /** Strength of frequency f in p, with a Hann window, using the real reading times. */
        private fun project(p: DoubleArray, t: LongArray, s: Int, f: Double, mean: Double): Double {
            var a = 0.0
            var b = 0.0
            var w0 = 0.0
            val n = p.size
            for (i in 0 until n) {
                val w = .5 - .5 * cos(2 * PI * i / (n - 1))
                val ph = 2 * PI * f * ((t[s + i] - t[s]) / 1e9)
                a += w * (p[i] - mean) * cos(ph)
                b += w * (p[i] - mean) * sin(ph)
                w0 += w
            }
            return 2 * hypot(a, b) / w0
        }

        /** Second-order high-pass filter, in place. Two in a row make a fourth-order Butterworth. */
        private fun highPass(x: DoubleArray, fs: Double, fc: Double, q: Double) {
            val w = 2 * PI * fc / fs
            val c = cos(w)
            val al = sin(w) / (2 * q)
            val a0 = 1 + al
            val b0 = (1 + c) / 2 / a0
            val b1 = -(1 + c) / a0
            val a1 = -2 * c / a0
            val a2 = (1 - al) / a0
            var x1 = 0.0
            var x2 = 0.0
            var y1 = 0.0
            var y2 = 0.0
            for (i in x.indices) {
                val y = b0 * x[i] + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
                x2 = x1
                x1 = x[i]
                y2 = y1
                y1 = y
                x[i] = y
            }
        }

        @JvmStatic fun spec(rate: Double, carrier: Double, duty: Double, brake: Double, bg: Double, gain: Double, sec: Double) = Engine.Spec().also {
            it.shape = "pulse"
            it.rate = rate
            it.carrier = carrier
            it.duty = duty
            it.brake = brake
            it.bg = bg
            it.gain = gain
            it.sec = sec
        }

        @JvmStatic fun copy(a: Best) = Best().also {
            it.rate = a.rate
            it.carrier = a.carrier
            it.duty = a.duty
            it.brake = a.brake
            it.bg = a.bg
            it.gain =
                a.gain
        }

        private fun avg(a: M, b: M) = M().apply {
            rms = (a.rms + b.rms) / 2
            flutter = (a.flutter + b.flutter) / 2
            depth = (a.depth + b.depth) / 2
            peakHz =
                (a.peakHz + b.peakHz) / 2
        }

        private fun f(d: Double, places: Int) = String.format(Locale.UK, "%.${places}f", d)

        private fun desc(b: Best) = "${f(
            b.rate,
            0,
        )}/s carrier ${f(
            b.carrier,
            0,
        )} duty ${f(
            b.duty,
            2,
        )} brake ${f(
            b.brake,
            2,
        )}x${f(b.bg, 1)} gain ${f(b.gain, 2)} -> got ${f(b.m.peakHz, 2)}/s level ${f(b.m.rms, 3)} flutter ${f(b.m.flutter, 3)} depth ${f(b.m.depth, 2)}"

        /** Strength setting that gives a level, read off a calibration curve. */
        @JvmStatic fun gainFor(a: JSONArray, level: Double): Double = runCatching {
            var g0 = 0.0
            var l0 = 0.0
            for (i in 0 until a.length()) {
                val g = a.getJSONArray(i).getDouble(0)
                val l = a.getJSONArray(i).getDouble(1)
                if (l >= level) return if (l - l0 < 1e-6) g else g0 + (g - g0) * (level - l0) / (l - l0)
                g0 = g
                l0 = l
            }
            g0
        }.getOrDefault(.1)

        /** Strongest level a curve reached. */
        @JvmStatic fun top(a: JSONArray): Double = runCatching { a.getJSONArray(a.length() - 1).getDouble(1) }.getOrDefault(0.0)
    }
}
