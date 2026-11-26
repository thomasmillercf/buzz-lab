package uk.buzzlab

import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exports must keep private details out unless asked, and read back in as the same drive. */
class IoTest {
    private val plate = "AB12 CDE"

    private fun export(positions: Boolean, extra: Map<String, String>? = null): Map<String, String> {
        val st = Store(Fixtures.tempDir())
        val m = Fixtures.storeWithDrive(st, reg = plate)
        val out = ByteArrayOutputStream()
        Io.export(st, listOf(m), out, positions, extra)
        return Fixtures.unzip(out.toByteArray())
    }

    private fun locationRows(z: Map<String, String>) = z.getValue("drives/1700000000000/Location.csv").lines().drop(1).filter { it.isNotBlank() }

    @Test
    fun `without positions there is no latitude or longitude, but speed stays`() {
        val rows = locationRows(export(positions = false))
        assertEquals(121, rows.size)
        for (r in rows) {
            val q = r.split(",")
            assertEquals("NaN", q[1], r)
            assertEquals("NaN", q[2], r)
            assertEquals("NaN", q[5], "heading gives the route away too: $r")
            assertEquals(40.0, q[4].toDouble() * Dsp.MPH, 1e-6)
        }
        // Readings are times and accelerations only; nothing else in the zip may hold a position either.
        val others = export(positions = false).filterKeys { !it.endsWith("Accelerometer.csv") }
        assertFalse(others.values.any { "51.5" in it }, "no position anywhere in the zip")
    }

    @Test
    fun `with positions the route is there`() {
        val q = locationRows(export(positions = true))[0].split(",")
        assertEquals(51.5, q[1].toDouble(), 1e-9)
        assertEquals(-0.1, q[2].toDouble(), 1e-9)
    }

    @Test
    fun `number plates stay out of ordinary exports`() {
        val z = export(positions = true)
        assertFalse(plate in z.getValue("cars.json"))
        assertEquals("Test car", JSONArray(z.getValue("cars.json")).getJSONObject(0).getString("name"))
    }

    @Test
    fun `a full export keeps plates and adds the extra files`() {
        val z = export(positions = true, extra = mapOf("sync-log.txt" to "hello"))
        assertTrue(plate in z.getValue("cars.json"))
        assertEquals("hello", z["sync-log.txt"])
    }

    @Test
    fun `the zip holds readings, stats, details and a list of drives`() {
        val z = export(positions = false)
        val acc = z.getValue("drives/1700000000000/Accelerometer.csv").lines().filter { it.isNotBlank() }
        assertEquals((120 * Fixtures.FS).toInt() + 1, acc.size, "header plus every reading")
        assertTrue(acc[0].startsWith("\"Time (s)\""))
        assertEquals("0.000000", acc[1].split(",")[0])
        assertTrue("psd" in z.getValue("drives/1700000000000/stats.json"))
        assertEquals("1700000000000", JSONArray(z.getValue("drives.json")).getJSONObject(0).getString("id"))
    }

    @Test
    fun `an export imports back as the same drive`() {
        val st = Store(Fixtures.tempDir())
        val m = Fixtures.storeWithDrive(st)
        val zip = ByteArrayOutputStream().also { Io.export(st, listOf(m), it, true) }.toByteArray()
        val other = Store(Fixtures.tempDir())
        val id = Io.importPhyphox(other, ByteArrayInputStream(zip), "c9", "Other", "", "225/45R17")
        val back = other.list().single()
        assertEquals(id, back.getString("id"))
        assertEquals("phyphox", back.getString("imported"))
        assertEquals(Fixtures.FS * 120, back.getLong("samples").toDouble(), 1.0)
        val a = Dsp.load(st.driveDir(m.getString("id"))).mean(40, 45)!!
        val b = Dsp.load(other.driveDir(id)).mean(40, 45)!!
        assertEquals(30, Dsp.peak(b, 15, 200))
        assertEquals(Dsp.rms(a, 25, 35), Dsp.rms(b, 25, 35), .01)
        assertEquals(Trip.build(st.driveDir(m.getString("id"))).miles, Trip.build(other.driveDir(id)).miles, 1e-3)
    }

    @Test
    fun `a zip without GPS is refused`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("Accelerometer.csv"))
            it.write("t,x,y,z\n0,0,0,9.8\n".toByteArray())
        }
        val e = assertThrows(IOException::class.java) { Io.importPhyphox(Store(Fixtures.tempDir()), ByteArrayInputStream(out.toByteArray()), "c", "p", "", "") }
        assertTrue("GPS" in e.message.orEmpty())
    }
}
