package uk.buzzlab

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Where and how fast a drive went, from its GPS file. Kept as trip.json so lists stay quick. */
class Trip {
    @JvmField var lat = DoubleArray(0)
    @JvmField var lon = DoubleArray(0)
    @JvmField var mph = DoubleArray(0)
    @JvmField var t = DoubleArray(0)
    @JvmField var miles = 0.0
    @JvmField var movingS = 0.0
    @JvmField var topMph = 0.0
    /** Seconds moving in each 5 mph band. */
    @JvmField val atSpeed = DoubleArray(16)
    @JvmField var start = 0L

    /** Average while moving, so time stopped doesn't count. */
    fun avgMoving() = if (movingS > 0) miles / (movingS / 3600) else 0.0

    fun json(): JSONObject {
        val la = JSONArray()
        val lo = JSONArray()
        val v = JSONArray()
        val tt = JSONArray()
        for (i in lat.indices) {
            la.put((lat[i] * 1e5).roundToLong() / 1e5)
            lo.put((lon[i] * 1e5).roundToLong() / 1e5)
            v.put(if (mph[i].isNaN()) -1.0 else (mph[i] * 10).roundToLong() / 10.0)
            tt.put((t[i] * 10).roundToLong() / 10.0)
        }
        return JSONObject().put("v", 1).put("miles", miles).put("moving", movingS).put("top", topMph)
            .put("at", JSONArray().apply { atSpeed.forEach { put(it.roundToLong()) } })
            .put("lat", la).put("lon", lo).put("mph", v).put("t", tt)
    }

    companion object {
        const val MPH = 2.23694

        @JvmStatic @Throws(Exception::class)
        fun load(dir: File, start: Long): Trip {
            val cache = File(dir, "trip.json")
            Store.read(cache)?.let { runCatching { return from(JSONObject(it), start) } }
            return build(dir).also {
                it.start = start
                Store.write(cache, it.json().toString())
            }
        }

        /** Fixes with a position and accuracy within 40 m; mph is NaN where GPS gave no speed. */
        @JvmStatic @Throws(java.io.IOException::class)
        fun build(dir: File): Trip {
            var base = -1L
            val rows = File(dir, "gps.csv").readLines().drop(1).mapNotNull { line ->
                val q = line.split(",")
                if (q.size < 5 || q[1].isEmpty() || q[2].isEmpty()) return@mapNotNull null
                val la = q[1].toDouble()
                val lo = q[2].toDouble()
                if (la.isNaN() || lo.isNaN() || (la == 0.0 && lo == 0.0)) return@mapNotNull null
                if ((if (q[4].isEmpty()) 0.0 else q[4].toDouble()) > 40) return@mapNotNull null
                val ns = q[0].toLong()
                if (base < 0) base = ns
                doubleArrayOf((ns - base) / 1e9, la, lo, if (q[3].isEmpty()) Double.NaN else q[3].toDouble() * MPH)
            }
            val trip = Trip()
            trip.t = DoubleArray(rows.size) { rows[it][0] }
            trip.lat = DoubleArray(rows.size) { rows[it][1] }
            trip.lon = DoubleArray(rows.size) { rows[it][2] }
            trip.mph = DoubleArray(rows.size) { rows[it][3] }
            for (i in rows.indices) {
                val v = rows[i][3]
                if (i > 0) {
                    val dt = rows[i][0] - rows[i - 1][0]
                    if (dt > 0 && dt < 5 && !v.isNaN()) {
                        trip.miles += v * dt / 3600
                        if (v >= 2) {
                            trip.movingS += dt
                            trip.atSpeed[min(15, (v / 5).toInt())] += dt
                        }
                    }
                }
                if (!v.isNaN()) trip.topMph = max(trip.topMph, v)
            }
            return trip
        }

        @JvmStatic @Throws(JSONException::class)
        fun from(o: JSONObject, start: Long): Trip {
            if (o.optInt("v") != 1) throw JSONException("old")
            val trip = Trip()
            trip.start = start
            trip.miles = o.getDouble("miles")
            trip.movingS = o.getDouble("moving")
            trip.topMph = o.getDouble("top")
            val at = o.getJSONArray("at")
            for (i in 0 until 16) trip.atSpeed[i] = at.getDouble(i)
            val la = o.getJSONArray("lat")
            val lo = o.getJSONArray("lon")
            val v = o.getJSONArray("mph")
            val tt = o.getJSONArray("t")
            val n = la.length()
            trip.lat = DoubleArray(n) { la.getDouble(it) }
            trip.lon = DoubleArray(n) { lo.getDouble(it) }
            trip.mph = DoubleArray(n) { v.getDouble(it).let { m -> if (m < 0) Double.NaN else m } }
            trip.t = DoubleArray(n) { tt.getDouble(it) }
            return trip
        }
    }
}
