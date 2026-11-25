package uk.buzzlab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.PI

/** Cars and drives as files in app-private storage. */
class Store(c: Context) {
    @JvmField val dir: File = c.filesDir
    @JvmField val drives = File(dir, "drives").apply { mkdirs() }
    @JvmField val carsF = File(dir, "cars.json")

    fun cars(): JSONArray = runCatching { JSONArray(read(carsF)) }.getOrDefault(JSONArray())

    fun car(id: String?): JSONObject? = cars().objects().firstOrNull { it.optString("id") == id }

    @Throws(Exception::class)
    fun putCar(c: JSONObject) {
        val all = cars().objects()
        val out = JSONArray()
        all.forEach { out.put(if (it.getString("id") == c.getString("id")) c else it) }
        if (all.none { it.getString("id") == c.getString("id") }) out.put(c)
        write(carsF, out.toString())
    }

    @Throws(Exception::class)
    fun delCar(id: String) {
        val out = JSONArray()
        cars().objects().filter { it.getString("id") != id }.forEach { out.put(it) }
        write(carsF, out.toString())
    }

    /** Every drive's details, newest first. */
    fun list(): MutableList<JSONObject> = (drives.listFiles() ?: emptyArray())
        .mapNotNull { d -> read(File(d, "meta.json"))?.let { runCatching { JSONObject(it) }.getOrNull() } }
        .sortedByDescending { it.optLong("start") }.toMutableList()

    fun driveDir(id: String) = File(drives, id)

    @Throws(Exception::class)
    fun saveMeta(m: JSONObject) = write(File(driveDir(m.getString("id")), "meta.json"), m.toString())

    fun delDrive(id: String) {
        driveDir(id).deleteRecursively()
    }

    companion object {
        private val TYRE = Regex("""(\d{3})\s*/\s*(\d{2})\s*[Zz]?[Rr]\s*(\d{2})""")

        @JvmStatic fun read(f: File): String? = runCatching { f.readText() }.getOrNull()

        /** Write through a temporary file, so a crash never leaves half a file. */
        @JvmStatic @Throws(java.io.IOException::class)
        fun write(f: File, s: String) {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(s)
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        /** "275/50R20" to rolling distance in metres (unloaded circumference x 0.97); 0 when not understood. */
        @JvmStatic fun circ(s: String?): Double {
            val m = TYRE.find(s ?: "") ?: return 0.0
            val (w, a, d) = m.destructured
            return PI * (d.toInt() * 25.4 + 2.0 * w.toInt() * a.toInt() / 100) / 1000 * .97
        }

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
