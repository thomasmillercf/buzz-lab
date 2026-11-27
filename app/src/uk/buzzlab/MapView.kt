package uk.buzzlab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/** OpenStreetMap tiles, darkened, with routes coloured by speed. */
@SuppressLint("ViewConstructor")
class MapView(c: Context, t: List<Trip>, private val bg: Int) : View(c) {
    private val trips = t.filter { it.lat.size > 1 }
    private val cache = File(c.cacheDir, "tiles")
    private val dp = resources.displayMetrics.density
    private var cx = 0.0
    private var cy = 0.0
    private var scale = 1.0
    private var z = 12
    private var fitted = false

    /** Until the map is pinched, a one-finger drag is left to the page, so the page still scrolls past the map. */
    private var pinched = false
    private var lx = 0f
    private var ly = 0f
    private val h = Handler(Looper.getMainLooper())

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        val m = ColorMatrix(floatArrayOf(-.75f, 0f, 0f, 0f, 190f, 0f, -.75f, 0f, 0f, 195f, 0f, 0f, -.7f, 0f, 205f, 0f, 0f, 0f, 1f, 0f))
        m.postConcat(ColorMatrix().apply { setSaturation(.25f) })
        colorFilter = ColorMatrixColorFilter(m)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10 * dp
        color = 0xCCF2F0EA.toInt()
    }
    private val creditPaint = Paint().apply { color = 0x99021B29.toInt() }

    private val pinch = ScaleGestureDetector(
        c,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                pinched = true
                zoomAt(d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        },
    )
    private val taps = GestureDetector(
        c,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                fitted = false
                pinched = false
                invalidate()
                return true
            }
        },
    )

    init {
        contentDescription = "Map of the routes driven"
    }

    override fun onMeasure(w: Int, hh: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, (width * .8).toInt())
    }

    private fun fit() {
        if (trips.isEmpty() || width == 0) return
        val lats = trips.flatMap { it.lat.asList() }
        val lons = trips.flatMap { it.lon.asList() }
        val (s, n, w, e) = listOf(lats.min(), lats.max(), lons.min(), lons.max())
        z = 17
        while (z > 2 && (mx(e, z) - mx(w, z) > width * .86 || my(s, z) - my(n, z) > height * .82)) z--
        cx = (mx(w, z) + mx(e, z)) / 2
        cy = (my(s, z) + my(n, z)) / 2
        scale = 1.0
        fitted = true
    }

    private fun zoomAt(f: Float, fx: Float, fy: Float) {
        var wx = cx + (fx - width / 2.0) / scale
        var wy = cy + (fy - height / 2.0) / scale
        scale *= f
        while (scale > 1.6 && z < 18) {
            scale /= 2
            z++
            wx *= 2
            wy *= 2
        }
        while (scale < .8 && z > 2) {
            scale *= 2
            z--
            wx /= 2
            wy /= 2
        }
        cx = wx - (fx - width / 2.0) / scale
        cy = wy - (fy - height / 2.0) / scale
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val mapTakesThisTouch = pinched || e.pointerCount > 1
        parent.requestDisallowInterceptTouchEvent(mapTakesThisTouch)
        taps.onTouchEvent(e)
        pinch.onTouchEvent(e)
        if (e.pointerCount == 1 && !pinch.isInProgress) {
            if (pinched && e.actionMasked == MotionEvent.ACTION_MOVE) {
                cx -= (e.x - lx) / scale
                cy -= (e.y - ly) / scale
                invalidate()
            }
            lx = e.x
            ly = e.y
        } else if (e.pointerCount > 1) {
            lx = e.getX(0)
            ly = e.getY(0)
        }
        if (e.actionMasked == MotionEvent.ACTION_UP) parent.requestDisallowInterceptTouchEvent(false)
        return true
    }

    override fun onDraw(g: Canvas) {
        if (!fitted) fit()
        g.drawColor(bg)
        val w = width.toFloat()
        val ht = height.toFloat()
        val ts = 256 * scale
        val left = cx - w / 2 / scale
        val top = cy - ht / 2 / scale
        val n = 1 shl z
        for (tx0 in floor(left / 256).toInt()..floor((cx + w / 2 / scale) / 256).toInt()) {
            for (ty in floor(top / 256).toInt()..floor((cy + ht / 2 / scale) / 256).toInt()) {
                if (ty !in 0 until n) continue
                val b = tile(z, Math.floorMod(tx0, n), ty) ?: continue
                val x0 = ((tx0 * 256 - left) * scale).toFloat()
                val y0 = ((ty * 256 - top) * scale).toFloat()
                g.drawBitmap(b, null, RectF(x0, y0, (x0 + ts).toFloat(), (y0 + ts).toFloat()), tilePaint)
            }
        }
        linePaint.strokeWidth = (if (trips.size > 3) 3f else 4.5f) * dp
        for (t in trips) {
            var px = 0f
            var py = 0f
            for (i in t.lat.indices) {
                val x = ((mx(t.lon[i], z) - left) * scale).toFloat()
                val y = ((my(t.lat[i], z) - top) * scale).toFloat()
                if (i > 0 && t.t[i] - t.t[i - 1] < 6) {
                    linePaint.color = speedColour(t.mph[i])
                    g.drawLine(px, py, x, y, linePaint)
                }
                px = x
                py = y
            }
        }
        val credit = "© OpenStreetMap contributors"
        val cw = textPaint.measureText(credit)
        g.drawRect(w - cw - 10 * dp, ht - 16 * dp, w, ht, creditPaint)
        g.drawText(credit, w - cw - 5 * dp, ht - 5 * dp, textPaint)
        if (trips.isEmpty()) {
            textPaint.textSize = 14 * dp
            g.drawText("No positions recorded", 16 * dp, 30 * dp, textPaint)
            textPaint.textSize = 10 * dp
        }
    }

    /** From memory, else null while it loads from disk or the network in the background. */
    private fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val k = "$z/$x/$y"
        MEM[k]?.let { return it }
        if (BUSY.add(k)) NET.execute { load(k) }
        return null
    }

    private fun load(k: String) {
        try {
            val f = File(cache, "$k.png")
            var got = if (f.exists() && System.currentTimeMillis() - f.lastModified() < 30L * 86400000) BitmapFactory.decodeFile(f.path) else null
            if (got == null) {
                val c = URL("https://tile.openstreetmap.org/$k.png").openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "BuzzLab/2.5 (personal Android app)")
                c.connectTimeout = 10000
                c.readTimeout = 15000
                if (c.responseCode == 200) {
                    val d = c.inputStream.use { it.readBytes() }
                    got = BitmapFactory.decodeByteArray(d, 0, d.size)
                    if (got != null) {
                        f.parentFile?.mkdirs()
                        f.writeBytes(d)
                    }
                }
                c.disconnect()
            }
            if (got != null) {
                MEM[k] = got
                h.post { invalidate() }
            }
        } catch (_: Exception) {
        } finally {
            BUSY.remove(k)
        }
    }

    companion object {
        private val NET = Executors.newFixedThreadPool(2)
        private val MEM: MutableMap<String, Bitmap> = Collections.synchronizedMap(
            object : LinkedHashMap<String, Bitmap>(64, .75f, true) {
                override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>) = size > 120
            },
        )
        private val BUSY: MutableSet<String> = Collections.synchronizedSet(HashSet())
        private val STOPS = intArrayOf(0xFF3F7FE0.toInt(), 0xFF2EC4B6.toInt(), 0xFFFFD166.toInt(), 0xFFFF8A3D.toInt(), 0xFFFF3B1F.toInt())
        private val AT = doubleArrayOf(0.0, 20.0, 40.0, 55.0, 70.0)

        private fun mx(lon: Double, z: Int) = (lon + 180) / 360 * 256 * (1 shl z)

        private fun my(lat: Double, z: Int): Double {
            val r = Math.toRadians(lat)
            return (1 - ln(tan(r) + 1 / cos(r)) / Math.PI) / 2 * 256 * (1 shl z)
        }

        /** Cool blue when slow, through teal and yellow, to hot orange and red when fast. */
        @JvmStatic fun speedColour(v: Double): Int {
            if (v.isNaN()) return 0xFF7C8B95.toInt()
            if (v <= 0) return STOPS[0]
            for (i in 1 until AT.size) {
                if (v < AT[i]) {
                    val w = (v - AT[i - 1]) / (AT[i] - AT[i - 1])
                    val a = STOPS[i - 1]
                    val b = STOPS[i]
                    var o = 0xFF000000.toInt()
                    for (s in 0 until 24 step 8) o = o or (((a shr s and 255) * (1 - w) + (b shr s and 255) * w).roundToInt() shl s)
                    return o
                }
            }
            return STOPS.last()
        }
    }
}
