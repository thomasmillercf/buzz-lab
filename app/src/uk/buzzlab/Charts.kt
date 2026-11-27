package uk.buzzlab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Heat map (speed by frequency), spectrum, spread, speed and bar charts, drawn to scale. */
object Charts {
    /** One orange ramp on navy: dark is near zero, bright is strong. */
    private val RAMP = longArrayOf(0xFF062B3F, 0xFF4A2430, 0xFF8E2A1A, 0xFFD4320F, 0xFFF95A26, 0xFFFF8F4D, 0xFFFFD9B8).map { it.toInt() }

    /** Twelfth-octave bands from 2 to about 220 Hz. */
    const val NK = 82

    private fun centre(k: Int) = 2 * 2.0.pow(k / 12.0)

    private fun edge(k: Int) = max(2.0, centre(k) * 2.0.pow(-1 / 24.0))

    /** Power averaged into twelfth-octave bands, then lightly blended with its neighbours in frequency and speed. Display only. */
    @JvmStatic fun smooth(d: Dsp): Array<DoubleArray> {
        val v = Array(Dsp.NB) { DoubleArray(NK) }
        val o = Array(Dsp.NB) { DoubleArray(NK) }
        for (b in 0 until Dsp.NB) {
            if (d.n[b] < 3) continue
            val q = DoubleArray(NK) { k ->
                var s = 0.0
                var c = 0
                var f = ceil(edge(k)).toInt()
                while (f < edge(k + 1) && f <= 230) {
                    s += d.psd[b][f - Dsp.F0]
                    c++
                    f++
                }
                if (c == 0) {
                    val x = min(229.0, centre(k))
                    val i = x.toInt()
                    s = d.psd[b][i - Dsp.F0] * (i + 1 - x) + d.psd[b][i + 1 - Dsp.F0] * (x - i)
                    c = 1
                }
                s / c / d.n[b]
            }
            for (k in 0 until NK) v[b][k] = .5 * q[k] + .25 * q[max(0, k - 1)] + .25 * q[min(NK - 1, k + 1)]
        }
        for (b in 0 until Dsp.NB) {
            if (d.n[b] < 3) continue
            for (k in 0 until NK) {
                var s = 0.0
                var w = 0.0
                for (j in -1..1) {
                    val c = b + j
                    if (c < 0 || c >= Dsp.NB || d.n[c] < 3) continue
                    val u = (if (j == 0) 1.0 else .35) * min(30, d.n[c])
                    s += u * v[c][k]
                    w += u
                }
                o[b][k] = s / w
            }
        }
        return o
    }

    /** Mixes two colours; w from 0 (all a) to 1 (all b). */
    private fun mix(a: Int, b: Int, w: Double): Int {
        var r = 0
        for (sh in 0 until 24 step 8) r = r or (((a shr sh and 255) * (1 - w) + (b shr sh and 255) * w).roundToInt() shl sh)
        return 0xFF000000.toInt() or r
    }

    /** Colours blend between steps instead of jumping. */
    @JvmStatic fun col(a: Double, log: Boolean): Int {
        val t = (if (log) (if (a <= .005) 0.0 else ln(a / .005) / ln(2.0)) else a / .35 * 6).coerceIn(0.0, 6.0)
        val i = min(5, t.toInt())
        return mix(RAMP[i], RAMP[i + 1], t - i)
    }

    private fun ly(f: Double, top: Float, h: Float) = (top + h - ln(max(f, 2.0) / 2) / ln(115.0) * h).toFloat()

    private fun pen(dp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * dp }

    /** Speed across, frequency up, brightness for strength. White curves are once (solid) and twice (dashed) per wheel turn. */
    @SuppressLint("ViewConstructor")
    class Heat(c: Context, @JvmField var d: Dsp, private val circ: Double, @Suppress("unused") dark: Boolean, private val mu: Int) : View(c) {
        @JvmField var log = false

        init {
            contentDescription = "Chart of vibration strength by speed and frequency"
        }

        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            setMeasuredDimension(width, (width * .85).toInt())
        }

        override fun onDraw(g: Canvas) {
            val dp = resources.displayMetrics.density
            val left = 30 * dp
            val top = 6 * dp
            val w = width - left - 6 * dp
            val h = height - top - 38 * dp
            val p = pen(dp)
            val hi = max(8, (0 until Dsp.NB).filter { d.n[it] >= 3 }.maxOfOrNull { it + 1 } ?: 0)
            val lo = 1
            val cw = w / (hi - lo)
            val z = smooth(d)
            for (b in lo until hi) {
                if (d.n[b] < 3) continue
                for (k in 0 until NK) {
                    p.color = col(sqrt(z[b][k]), log)
                    g.drawRect(left + (b - lo) * cw + dp, ly(min(230.0, edge(k + 1)), top, h), left + (b - lo + 1) * cw - dp, ly(edge(k), top, h) + 1, p)
                }
            }
            p.color = mu
            p.textAlign = Paint.Align.RIGHT
            for (f in intArrayOf(2, 5, 10, 20, 30, 50, 100, 200)) g.drawText("$f", left - 4 * dp, ly(f.toDouble(), top, h) + 4 * dp, p)
            p.textAlign = Paint.Align.CENTER
            for (m in 10..hi * 5 step 10) g.drawText("$m", left + (m / 5f - lo) * cw, top + h + 15 * dp, p)
            g.drawText("mph (Hz up the side, log scale)", left + w / 2, top + h + 32 * dp, p)
            if (circ > 0) {
                p.color = 0xFFF2F0EA.toInt()
                p.strokeWidth = 2.5f * dp
                p.style = Paint.Style.STROKE
                for (order in 1..2) {
                    p.pathEffect = if (order == 1) null else DashPathEffect(floatArrayOf(7 * dp, 5 * dp), 0f)
                    val f1 = order * lo * 5 / Dsp.MPH / circ
                    val f2 = order * hi * 5 / Dsp.MPH / circ
                    val q = Path()
                    var end = 0f to 0f
                    for (k in 0..40) {
                        val f = f1 + (f2 - f1) * k / 40
                        if (f < 2) continue
                        val x = left + w * k / 40
                        val y = min(top + h, ly(f, top, h))
                        if (q.isEmpty) q.moveTo(x, y) else q.lineTo(x, y)
                        end = x to y
                    }
                    g.drawPath(q, p)
                    p.pathEffect = null
                    p.style = Paint.Style.FILL
                    p.textAlign = Paint.Align.RIGHT
                    g.drawText("$order×", end.first - 4 * dp, end.second - 5 * dp, p)
                    p.style = Paint.Style.STROKE
                }
            }
        }
    }

    /**
     * Spread of strength at 20 to 50 Hz in each speed band: the bar runs from the 10th to the 90th percentile of 2 s windows,
     * the tick is the median. A tall bar means it comes and goes; a short one means it is steady. b, in grey, is a comparison.
     */
    @SuppressLint("ViewConstructor")
    class Spread(c: Context, private val a: Dsp, private val b: Dsp?, private val mu: Int) : View(c) {
        init {
            contentDescription = "Chart of how much the strength varies at each speed"
        }

        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            setMeasuredDimension(width, (width * .6).toInt())
        }

        override fun onDraw(g: Canvas) {
            val dp = resources.displayMetrics.density
            val left = 40 * dp
            val top = 8 * dp
            val w = width - left - 8 * dp
            val h = height - top - 34 * dp
            val sets = listOfNotNull(a, b)
            val used = (0 until Dsp.NB).filter { k -> sets.any { it.spreadCount(k) >= MIN } }
            if (used.isEmpty()) return
            val lo = max(1, used.first())
            val hi = max(lo + 4, used.last() + 1)
            var mx = .1
            for (d in sets) for (k in lo until hi) if (d.spreadCount(k) >= MIN) mx = max(mx, d.pct(k, .9) * 1.1)
            val p = pen(dp)
            val step = if (mx > 1.2) {
                .5
            } else if (mx > .6) {
                .2
            } else if (mx > .3) {
                .1
            } else {
                .05
            }
            for (v in generateSequence(0.0) { it + step }.takeWhile { it < mx }) {
                val y = (top + h - v / mx * h).toFloat()
                p.color = mu
                p.alpha = 55
                p.strokeWidth = dp
                g.drawLine(left, y, left + w, y, p)
                p.alpha = 255
                p.textAlign = Paint.Align.RIGHT
                g.drawText(String.format(Locale.UK, "%.2f", v), left - 4 * dp, y + 4 * dp, p)
            }
            val cw = w / (hi - lo)
            p.textAlign = Paint.Align.CENTER
            for (k in lo..hi) if (k % 2 == 0) g.drawText("${k * 5}", left + (k - lo) * cw, top + h + 15 * dp, p)
            g.drawText("mph", left + w / 2, top + h + 30 * dp, p)
            val y = { v: Double -> (top + h - v / mx * h).toFloat() }
            for ((i, d) in sets.withIndex()) {
                val slot = cw / sets.size
                for (k in lo until hi) {
                    if (d.spreadCount(k) < MIN) continue
                    val x0 = left + (k - lo) * cw + i * slot + slot * .2f
                    val x1 = left + (k - lo) * cw + (i + 1) * slot - slot * .2f
                    p.color = if (i == 0) 0xFFFF803A.toInt() else 0xFF9DB4C0.toInt()
                    p.alpha = 150
                    g.drawRoundRect(x0, y(d.pct(k, .9)), x1, y(d.pct(k, .1)), 2 * dp, 2 * dp, p)
                    p.alpha = 255
                    p.color = 0xFFF2F0EA.toInt()
                    val m = y(d.pct(k, .5))
                    g.drawRect(x0, m - dp, x1, m + dp, p)
                }
            }
        }

        companion object {
            /** Windows a band needs before it is drawn: 10 s of steady driving. */
            const val MIN = 5
        }
    }

    /** Spectrum: a in orange, the optional comparison b in grey. */
    @SuppressLint("ViewConstructor")
    class Line(
        c: Context,
        @JvmField var a: DoubleArray?,
        @JvmField var b: DoubleArray?,
        @Suppress("unused") dark: Boolean,
        private val mu: Int,
        @JvmField var log: Boolean,
    ) : View(c) {
        init {
            contentDescription = "Chart of vibration strength by frequency"
        }

        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            setMeasuredDimension(width, (width * .65).toInt())
        }

        override fun onDraw(g: Canvas) {
            val dp = resources.displayMetrics.density
            val left = 42 * dp
            val top = 8 * dp
            val w = width - left - 16 * dp
            val h = height - top - 36 * dp
            var mx = .1
            for (q in listOfNotNull(a, b)) for (f in 5..230) mx = max(mx, sqrt(q[f - Dsp.F0]) * 1.08)
            val p = pen(dp)
            val step = if (mx > .4) {
                .2
            } else if (mx > .2) {
                .1
            } else {
                .05
            }
            val ticks = if (log) listOf(.001, .01, .1, 1.0).filter { it <= mx } else generateSequence(0.0) { it + step }.takeWhile { it < mx }.toList()
            for (v in ticks) {
                val y = yv(v, mx, top, h)
                p.color = mu
                p.alpha = 60
                p.strokeWidth = dp
                g.drawLine(left, y, left + w, y, p)
                p.alpha = 255
                p.textAlign = Paint.Align.RIGHT
                g.drawText(String.format(Locale.UK, if (log) "%.3f" else "%.2f", v), left - 4 * dp, y + 4 * dp, p)
            }
            p.textAlign = Paint.Align.CENTER
            for (f in intArrayOf(5, 10, 20, 30, 50, 100, 200)) g.drawText("$f", lx(f.toDouble(), left, w), top + h + 15 * dp, p)
            g.drawText("Hz, log scale", left + w / 2, top + h + 30 * dp, p)
            p.style = Paint.Style.STROKE
            for ((q, main) in listOf(b to false, a to true)) {
                if (q == null) continue
                val path = Path()
                for (f in 5..230) {
                    val x = lx(f.toDouble(), left, w)
                    val y = yv(sqrt(q[f - Dsp.F0]), mx, top, h)
                    if (f == 5) path.moveTo(x, y) else path.lineTo(x, y)
                }
                p.color = if (main) 0xFFFF803A.toInt() else 0xFF9DB4C0.toInt()
                p.strokeWidth = (if (main) 2.5f else 1.8f) * dp
                g.drawPath(path, p)
            }
        }

        private fun lx(f: Double, left: Float, w: Float) = (left + ln(f / 5) / ln(46.0) * w).toFloat()

        private fun yv(v: Double, mx: Double, top: Float, h: Float): Float {
            val mn = .001
            val share = if (log) ln(max(v, mn) / mn) / ln(mx / mn) else v / mx
            return (top + h - share * h).toFloat()
        }
    }

    /** Speed against time for one drive, coloured by speed. */
    @SuppressLint("ViewConstructor")
    class Speed(c: Context, private val t: Trip, private val mu: Int) : View(c) {
        init {
            contentDescription = "Chart of speed over the drive"
        }

        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            setMeasuredDimension(width, (width * .45).toInt())
        }

        override fun onDraw(g: Canvas) {
            val dp = resources.displayMetrics.density
            val left = 34 * dp
            val top = 8 * dp
            val w = width - left - 12 * dp
            val h = height - top - 30 * dp
            val n = t.t.size
            if (n < 2) return
            val end = t.t[n - 1].toDouble()
            val highest = max(30.0, ceil(t.topMph / 10) * 10)
            val p = pen(dp)
            val grid = if (highest > 60) 20 else 10
            for (v in 0..highest.toInt() step grid) {
                val y = (top + h - v / highest * h).toFloat()
                p.color = mu
                p.alpha = 55
                p.strokeWidth = dp
                g.drawLine(left, y, left + w, y, p)
                p.alpha = 255
                p.textAlign = Paint.Align.RIGHT
                g.drawText("$v", left - 5 * dp, y + 4 * dp, p)
            }
            p.textAlign = Paint.Align.CENTER
            val step = if (end > 3600) {
                900
            } else if (end > 1200) {
                300
            } else if (end > 300) {
                60
            } else {
                30
            }
            for (s in 0..end.toInt() step step) {
                val label = if (s >= 3600) String.format(Locale.UK, "%d:%02dh", s / 3600, s % 3600 / 60) else "${s / 60}m"
                g.drawText(label, left + (s / end).toFloat() * w, top + h + 16 * dp, p)
            }
            p.strokeWidth = 2.5f * dp
            p.strokeCap = Paint.Cap.ROUND
            for (i in 1 until n) {
                if (t.mph[i].isNaN() || t.mph[i - 1].isNaN() || t.t[i] - t.t[i - 1] > 6) continue
                p.color = MapView.speedColour(t.mph[i])
                g.drawLine(
                    left + (t.t[i - 1] / end).toFloat() * w,
                    (top + h - t.mph[i - 1] / highest * h).toFloat(),
                    left + (t.t[i] / end).toFloat() * w,
                    (top + h - t.mph[i] / highest * h).toFloat(),
                    p,
                )
            }
        }
    }

    /** Simple bars with a label under each, and the tallest one's value above it. */
    @SuppressLint("ViewConstructor")
    class Bars(c: Context, private val v: DoubleArray, private val lab: Array<String>, private val unit: String, private val col: Int, private val mu: Int) :
        View(c) {
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            setMeasuredDimension(width, (width * .45).toInt())
        }

        override fun onDraw(g: Canvas) {
            val dp = resources.displayMetrics.density
            val top = 18 * dp
            val h = height - top - 22 * dp
            val m = max(1e-9, v.maxOrNull() ?: 0.0)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 11 * dp
                textAlign = Paint.Align.CENTER
            }
            val bw = width.toFloat() / v.size
            val every = max(1, ceil(v.size / 8.0).toInt())
            for (i in v.indices) {
                val bh = (v[i] / m * h).toFloat()
                val x = i * bw
                p.color = col
                g.drawRoundRect(x + bw * .14f, top + h - bh, x + bw * .86f, top + h, 3 * dp, 3 * dp, p)
                p.color = mu
                if (i % every == 0) drawCentredInside(g, lab[i], x + bw / 2, top + h + 15 * dp, p)
            }
            val k = v.indices.maxByOrNull { v[it] } ?: return
            if (v[k] > 0) {
                p.color = 0xFFF2F0EA.toInt()
                val tallest = String.format(Locale.UK, if (v[k] >= 10) "%.0f%s" else "%.1f%s", v[k], unit)
                drawCentredInside(g, tallest, k * bw + bw / 2, top + h - (v[k] / m * h).toFloat() - 5 * dp, p)
            }
        }

        private fun drawCentredInside(g: Canvas, s: String, x: Float, y: Float, p: Paint) {
            val half = p.measureText(s) / 2
            g.drawText(s, x.coerceIn(half, max(half, width - half)), y, p)
        }
    }
}
