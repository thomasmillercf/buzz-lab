package uk.buzzlab

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Export and import. Exports use phyphox's CSV layout, so existing scripts read them. */
object Io {
    private const val MAX_IMPORT = 400L shl 20

    private fun ZipOutputStream.text(name: String, s: String) {
        putNextEntry(ZipEntry(name))
        write(s.toByteArray())
        closeEntry()
    }

    /** One zip of drives. extra, when given, makes it a full export: plates included, plus these files. */
    @JvmStatic @JvmOverloads
    @Throws(Exception::class)
    fun export(st: Store, drives: List<JSONObject>, os: OutputStream, positions: Boolean, extra: Map<String, String>? = null) {
        ZipOutputStream(os.buffered()).use { z ->
            z.text("cars.json", (if (extra != null) st.cars() else strip(st.cars())).toString(1))
            extra?.forEach { (name, s) -> z.text(name, s) }
            val index = JSONArray()
            for (m in drives) {
                val id = m.getString("id")
                val dir = st.driveDir(id)
                val p = "drives/$id/"
                index.put(m)
                z.text(p + "meta.json", m.toString(1))
                z.text(p + "stats.json", Dsp.load(dir).json().toString())
                val raw = Dsp.raw(dir)
                z.putNextEntry(ZipEntry(p + "Accelerometer.csv"))
                val w = z.bufferedWriter()
                w.write("\"Time (s)\",\"Acceleration x (m/s^2)\",\"Acceleration y (m/s^2)\",\"Acceleration z (m/s^2)\"\n")
                for (i in raw.t.indices) w.write(String.format(Locale.UK, "%.6f,%.6f,%.6f,%.6f\n", raw.t[i], raw.v[i][0], raw.v[i][1], raw.v[i][2]))
                w.flush()
                z.closeEntry()
                val base = DataInputStream(FileInputStream(File(dir, "acc.bin"))).use { it.readLong() }
                z.text(p + "Location.csv", location(File(dir, "gps.csv"), base, positions))
                val marks = File(dir, "marks.csv")
                if (marks.exists()) z.text(p + "marks.csv", marks(marks, base))
            }
            z.text("drives.json", index.toString(1))
        }
    }

    /** GPS rows in phyphox's layout. Without positions, latitude, longitude and heading are NaN. */
    private fun location(gps: File, base: Long, positions: Boolean) = buildString {
        append(
            "\"Time (s)\",\"Latitude\",\"Longitude\",\"Height (m)\",\"Velocity (m/s)\",\"Direction\",\"Horizontal Accuracy (m)\",\"Vertical Accuracy (m)\"\n",
        )
        for (line in gps.readLines()) {
            val q = line.split(",")
            if (q.size < 6 || q[0].startsWith("t")) continue
            append(String.format(Locale.UK, "%.3f", (q[0].toLong() - base) / 1e9))
            append(',').append(if (positions) q[1] else "NaN")
            append(',').append(if (positions) q[2] else "NaN")
            append(",NaN,").append(q[3].ifEmpty { "NaN" })
            append(',').append(if (positions && q[5].isNotEmpty()) q[5] else "NaN")
            append(',').append(q[4]).append(",NaN\n")
        }
    }

    private fun marks(f: File, base: Long) = buildString {
        append("t_s,label\n")
        for (line in Store.read(f).orEmpty().lines()) {
            val k = line.indexOf(',')
            if (k < 1 || line.startsWith("t")) continue
            append(String.format(Locale.UK, "%.2f", (line.substring(0, k).toLong() - base) / 1e9)).append(line.substring(k)).append('\n')
        }
    }

    /** Number plates stay on the phone. */
    private fun strip(cars: JSONArray) = JSONArray().apply {
        for (i in 0 until cars.length()) put(JSONObject(cars.getJSONObject(i).toString()).apply { remove("reg") })
    }

    /** Adds a phyphox export (with GPS) as a drive. Returns its id. */
    @JvmStatic
    @Throws(Exception::class)
    fun importPhyphox(st: Store, input: InputStream, car: String?, place: String?, note: String?, tyre: String?): String {
        val files = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (name != "Accelerometer.csv" && name != "Location.csv" && name != "time.csv") continue
                val b = ByteArrayOutputStream()
                val buf = ByteArray(65536)
                while (true) {
                    val k = z.read(buf)
                    if (k <= 0) break
                    total += k
                    if (total > MAX_IMPORT) throw IOException("zip too large")
                    b.write(buf, 0, k)
                }
                files[name] = b.toByteArray()
            }
        }
        val acc = files["Accelerometer.csv"] ?: throw IOException("not a phyphox export with GPS")
        val loc = files["Location.csv"] ?: throw IOException("not a phyphox export with GPS")
        var start = System.currentTimeMillis()
        files["time.csv"]?.let { t -> String(t).lines().firstOrNull { "START" in it }?.let { start = (it.split(",")[2].toDouble() * 1000).toLong() } }
        if (st.list().any { it.optLong("start") == start }) throw IOException("already imported")

        val id = start.toString()
        val dir = st.driveDir(id).apply { mkdirs() }
        var n = 0L
        var last = 0.0
        DataOutputStream(FileOutputStream(File(dir, "acc.bin")).buffered(1 shl 16)).use { o ->
            for (line in String(acc).lines().drop(1)) {
                val p = line.split(",")
                if (p.size < 4) continue
                last = p[0].toDouble()
                o.writeLong((last * 1e9).toLong())
                for (k in 1..3) o.writeFloat(p[k].toFloat())
                n++
            }
        }
        var fixes = 0
        File(dir, "gps.csv").printWriter().use { o ->
            o.println("t_ns,lat,lon,speed_ms,acc_m,bearing")
            for (line in String(loc).lines().drop(1)) {
                val p = line.split(",")
                if (p.size < 7) continue
                val speed = if (p[4] == "NaN") "" else p[4]
                val heading = if (p[5] == "NaN") "" else p[5]
                o.println("${(p[0].toDouble() * 1e9).toLong()},${p[1]},${p[2]},$speed,${p[6]},$heading")
                fixes++
            }
        }
        st.saveMeta(
            JSONObject().put("id", id).put("start", start).put("end", start + (last * 1000).toLong()).put("car", car).put("place", place)
                .put("note", note).put("tyre", tyre).put("samples", n).put("fixes", fixes).put("imported", "phyphox").put("recording", false),
        )
        return id
    }
}
