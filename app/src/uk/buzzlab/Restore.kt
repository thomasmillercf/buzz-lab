package uk.buzzlab

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * Puts a drive back on the phone from the zip the app uploaded (the server keeps it as raw.zip): the
 * same files the phone writes, rebuilt from the export's CSVs. Times in the export count from the first
 * reading, so any starting clock works as long as readings, GPS and marks share it.
 */
object Restore {
    private const val MAX_ENTRY = 600L shl 20

    /** Returns the drive's id. syncedTo marks it as already on that server, so it isn't sent again. */
    @JvmStatic
    @Throws(Exception::class)
    fun drive(st: Store, input: InputStream, syncedTo: String): String {
        var dir: File? = null
        try {
            var meta: JSONObject? = null
            var base = 0L
            var cars: JSONArray? = null
            var samples = 0L
            var fixes = 0
            val z = ZipInputStream(input.buffered(1 shl 16))
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "cars.json") {
                    cars = runCatching { JSONArray(z.readBytes().decodeToString()) }.getOrNull()
                    continue
                }
                val p = e.name.split("/")
                if (p.size != 3 || p[0] != "drives") continue
                if (p[2] == "meta.json") {
                    if (meta != null) throw IOException("more than one drive in this zip")
                    val m = JSONObject(z.readBytes().decodeToString())
                    val id = m.getString("id")
                    if (id != p[1] || !id.all { it.isDigit() } || id.length !in 10..16) throw IOException("a drive has a bad id")
                    val existing = st.driveDir(id)
                    if (File(existing, "meta.json").exists()) throw IOException("already on this phone")
                    existing.deleteRecursively()
                    meta = m
                    base = m.optLong("start", id.toLong()) * 1_000_000L
                    dir = st.driveDir(id).apply { mkdirs() }
                    continue
                }
                val d = dir ?: continue // meta.json comes first in the app's zips
                if (p[1] != meta?.optString("id")) continue
                val lines = BufferedReader(InputStreamReader(Limited(z, MAX_ENTRY), Charsets.UTF_8), 1 shl 16)
                when (p[2]) {
                    "stats.json" -> Store.write(File(d, "stats.json"), lines.readText())
                    "Accelerometer.csv" -> DataOutputStream(FileOutputStream(File(d, "acc.bin")).buffered(1 shl 16)).use { o ->
                        lines.readLine()
                        while (true) {
                            val q = (lines.readLine() ?: break).split(",")
                            if (q.size < 4) continue
                            o.writeLong(base + (q[0].toDouble() * 1e9).toLong())
                            for (k in 1..3) o.writeFloat(q[k].toFloat())
                            samples++
                        }
                    }
                    "Location.csv" -> File(d, "gps.csv").printWriter().use { o ->
                        o.println("t_ns,lat,lon,speed_ms,acc_m,bearing")
                        lines.readLine()
                        while (true) {
                            val q = (lines.readLine() ?: break).split(",")
                            if (q.size < 7) continue
                            val speed = if (q[4] == "NaN") "" else q[4]
                            val heading = if (q[5] == "NaN") "" else q[5]
                            o.println("${base + (q[0].toDouble() * 1e9).toLong()},${q[1]},${q[2]},$speed,${q[6]},$heading")
                            fixes++
                        }
                    }
                    "marks.csv" -> File(d, "marks.csv").printWriter().use { o ->
                        o.println("t_ns,label")
                        lines.readLine()
                        while (true) {
                            val l = lines.readLine() ?: break
                            val k = l.indexOf(',')
                            if (k < 1) continue
                            val t = l.substring(0, k).toDoubleOrNull() ?: continue
                            o.println("${base + (t * 1e9).toLong()}${l.substring(k)}")
                        }
                    }
                }
            }
            val m = meta ?: throw IOException("no drive in this zip")
            val d = dir!!
            if (samples == 0L || !File(d, "gps.csv").exists()) throw IOException("the server copy has no raw readings")
            // its car, if this phone doesn't have it (plates never leave the phone, so there are none here)
            cars?.objects()?.firstOrNull { it.optString("id") == m.optString("car") }?.let { if (st.car(it.optString("id")) == null) st.putCar(it) }
            m.put("recording", false).put("syncedPos", syncedTo).put("samples", samples).put("fixes", fixes).remove("syncErr")
            st.saveMeta(m) // last, so a drive half written is never listed
            return m.getString("id")
        } catch (x: Exception) {
            if (dir != null && !File(dir, "meta.json").exists()) dir?.deleteRecursively()
            throw x
        }
    }

    /** Stops one zip entry from filling the phone. */
    private class Limited(val s: InputStream, var left: Long) : InputStream() {
        override fun read(): Int = if (left-- <= 0) throw IOException("a file in the zip is too large") else s.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) throw IOException("a file in the zip is too large")
            val n = s.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }
}
