package uk.buzzlab

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Random
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.sin

/** Synthetic drives in the same files the phone writes, so tests need no real (private) data. */
object Fixtures {
    /** The phone's accelerometer rate. */
    const val FS = 474.0

    /** Sensor clock at the first reading, in ns. */
    const val BASE = 1_000_000_000_000L

    fun tempDir(): File = Files.createTempDirectory("buzzlab").toFile().apply { deleteOnExit() }

    /**
     * A drive at a steady speed with a pure buzz on one axis (0 across, 1 along the phone, 2 up),
     * light noise on all three, and gravity on the up axis. GPS once a second.
     */
    fun drive(
        dir: File,
        seconds: Int = 120,
        mph: Double = 40.0,
        hz: Double = 30.0,
        amp: Double = .5,
        axis: Int = 1,
        gpsAccuracy: Double = 5.0,
        seed: Long = 1,
        extraGpsColumns: String = "",
    ) {
        dir.mkdirs()
        val r = Random(seed)
        DataOutputStream(BufferedOutputStream(FileOutputStream(File(dir, "acc.bin")))).use { o ->
            for (i in 0 until (seconds * FS).toInt()) {
                val t = i / FS
                o.writeLong(BASE + (t * 1e9).toLong())
                for (a in 0..2) {
                    var v = .02 * r.nextGaussian()
                    if (a == axis) v += amp * sin(2 * PI * hz * t)
                    if (a == 2) v += 9.81
                    o.writeFloat(v.toFloat())
                }
            }
        }
        val ms = mph / Dsp.MPH
        File(dir, "gps.csv").printWriter().use { o ->
            o.println("t_ns,lat,lon,speed_ms,acc_m,bearing")
            for (s in 0..seconds) o.println("${BASE + s * 1_000_000_000L},${51.5 + s * ms / 111_000.0},-0.1,$ms,$gpsAccuracy,0$extraGpsColumns")
        }
    }

    /** A store holding one car and one finished drive. Returns the drive's details. */
    fun storeWithDrive(st: Store, id: String = "1700000000000", reg: String = "AB12 CDE"): JSONObject {
        st.putCar(JSONObject().put("id", "c1").put("name", "Test car").put("reg", reg).put("tyre", "225/45R17"))
        drive(st.driveDir(id))
        val m = JSONObject().put("id", id).put("start", id.toLong()).put("end", id.toLong() + 120_000).put("car", "c1")
            .put("place", "Console, taped").put("tyre", "225/45R17").put("recording", false)
        st.saveMeta(m)
        return m
    }

    /** Every file in a zip, by name. */
    fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes().decodeToString()
            }
        }
        return out
    }
}
