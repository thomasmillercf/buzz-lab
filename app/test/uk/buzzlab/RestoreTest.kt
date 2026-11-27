package uk.buzzlab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A drive uploaded to the server comes back to an empty phone as the same drive. */
class RestoreTest {
    private fun uploaded(): Pair<Store, ByteArray> {
        val st = Store(Fixtures.tempDir())
        val m = Fixtures.storeWithDrive(st)
        File(st.driveDir("1700000000000"), "marks.csv").writeText("t_ns,label\n${Fixtures.BASE + 30_000_000_000L},Strong\n")
        val out = ByteArrayOutputStream()
        Io.export(st, listOf(m), out, true) // what the sync uploads
        return st to out.toByteArray()
    }

    @Test
    fun `round trip gives the same readings, route, marks and results`() {
        val (from, zip) = uploaded()
        val to = Store(Fixtures.tempDir())
        assertEquals("1700000000000", Restore.drive(to, ByteArrayInputStream(zip), "https://server"))
        val m = to.list().single()
        assertEquals("c1", m.getString("car"))
        assertEquals("https://server", m.getString("syncedPos"), "restored drives aren't uploaded again")
        assertFalse(m.optBoolean("recording"))
        val a = Dsp.raw(from.driveDir("1700000000000"))
        val b = Dsp.raw(to.driveDir("1700000000000"))
        assertEquals(a.t.size, b.t.size)
        assertEquals(a.t.last(), b.t.last(), 1e-5)
        assertEquals(a.v[1000][1], b.v[1000][1], 1e-5f)
        assertEquals(a.gps.size, b.gps.size)
        val gps = File(to.driveDir("1700000000000"), "gps.csv").readLines()
        assertEquals(121, gps.size - 1)
        assertTrue(gps[1].split(",")[1].startsWith("51.5"), "positions come back: ${gps[1]}")
        val marks = File(to.driveDir("1700000000000"), "marks.csv").readLines()
        assertTrue(marks[1].endsWith(",Strong"), marks.toString())
        File(to.driveDir("1700000000000"), "stats.json").delete() // worked out again from the restored readings
        assertEquals(
            Dsp.load(from.driveDir("1700000000000")).json().getJSONArray("n").toString(),
            Dsp.load(to.driveDir("1700000000000")).json().getJSONArray("n").toString(),
        )
        val car = to.car("c1")!!
        assertEquals("Test car", car.getString("name"))
        assertNull(car.opt("reg"), "plates never left the phone")
    }

    @Test
    fun `height, speed accuracy and GPS timing survive the round trip`() {
        val from = Store(Fixtures.tempDir())
        val m = Fixtures.storeWithDrive(from)
        Fixtures.drive(from.driveDir("1700000000000"), extraGpsColumns = ",0.4,87.5")
        from.saveMeta(m.put("sensorClockLagNs", 3_000_000_000L))
        val zip = ByteArrayOutputStream().also { Io.export(from, listOf(m), it, true) }.toByteArray()
        val to = Store(Fixtures.tempDir())
        Restore.drive(to, ByteArrayInputStream(zip), "https://server")
        val gps = File(to.driveDir("1700000000000"), "gps.csv").readLines()
        assertEquals("t_ns,lat,lon,speed_ms,acc_m,bearing,speed_acc_ms,alt_m", gps[0])
        assertTrue(gps[1].endsWith(",0.4,87.5"), gps[1])
        assertFalse(to.list().single().has("sensorClockLagNs"), "the restored files are already on one clock")
        assertEquals(-3.0, Dsp.raw(to.driveDir("1700000000000")).gps[0][0], 1e-3)
    }

    @Test
    fun `a drive already on the phone is left alone`() {
        val (from, zip) = uploaded()
        val e = assertThrows(IOException::class.java) { Restore.drive(from, ByteArrayInputStream(zip), "x") }
        assertEquals("already on this phone", e.message)
        assertEquals(1, from.list().size)
    }

    @Test
    fun `a zip without readings leaves nothing behind`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("drives/1700000000000/meta.json"))
            z.write("""{"id":"1700000000000","start":1700000000000}""".toByteArray())
        }
        val to = Store(Fixtures.tempDir())
        assertThrows(IOException::class.java) { Restore.drive(to, ByteArrayInputStream(out.toByteArray()), "x") }
        assertTrue(to.list().isEmpty())
        assertFalse(to.driveDir("1700000000000").exists())
    }
}
