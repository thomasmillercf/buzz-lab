package uk.buzzlab

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Miles, moving time and time at each speed, from GPS. */
class TripTest {
    private fun gps(vararg rows: String): File {
        val dir = Fixtures.tempDir()
        File(dir, "gps.csv").writeText("t_ns,lat,lon,speed_ms,acc_m,bearing\n" + rows.joinToString("\n") + "\n")
        return dir
    }

    private fun row(s: Int, mph: Double, acc: Double = 5.0, lat: String = "51.5", lon: String = "-0.1") =
        "${s * 1_000_000_000L},$lat,$lon,${mph / Trip.MPH},$acc,0"

    @Test
    fun `a steady drive`() {
        val dir = Fixtures.tempDir()
        Fixtures.drive(dir, seconds = 120, mph = 40.0)
        val t = Trip.build(dir)
        assertEquals(40.0 * 120 / 3600, t.miles, 1e-6)
        assertEquals(120.0, t.movingS, 1e-6)
        assertEquals(40.0, t.topMph, 1e-6)
        assertEquals(120.0, t.atSpeed[8], 1e-6, "40 mph is the 40 to 45 column")
        assertEquals(40.0, t.avgMoving(), 1e-6)
    }

    @Test
    fun `stopped time is left out of moving time and the average`() {
        val dir = gps(*(0..60).map { row(it, if (it < 30) 30.0 else 0.0) }.toTypedArray())
        val t = Trip.build(dir)
        assertEquals(29.0, t.movingS, 1e-6)
        assertEquals(30.0, t.avgMoving(), 1e-6)
    }

    @Test
    fun `bad fixes are dropped`() {
        val dir = gps(row(0, 30.0), row(1, 30.0, acc = 80.0), row(2, 30.0, lat = "0", lon = "0"), row(3, 30.0, lat = ""), row(4, 30.0))
        val t = Trip.build(dir)
        assertEquals(2, t.lat.size, "only the two good fixes")
        assertEquals(30.0 * 4 / 3600, t.miles, 1e-6, "4 s between the good fixes still counts")
    }

    @Test
    fun `gaps longer than 5 s add no distance`() {
        val t = Trip.build(gps(row(0, 30.0), row(10, 30.0), row(11, 30.0)))
        assertEquals(30.0 / 3600, t.miles, 1e-9)
    }

    @Test
    fun `a fix with no speed adds nothing itself`() {
        val t = Trip.build(gps(row(0, 30.0), "1000000000,51.5,-0.1,,5,0", row(2, 30.0)))
        assertTrue(t.mph[1].isNaN())
        assertEquals(30.0 / 3600, t.miles, 1e-12, "only the second after it, at 30 mph")
    }

    @Test
    fun `load caches the trip and reads it back the same`() {
        val dir = Fixtures.tempDir()
        Fixtures.drive(dir, seconds = 60, mph = 50.0)
        val a = Trip.load(dir, 123)
        assertTrue(File(dir, "trip.json").exists())
        val b = Trip.load(dir, 123)
        assertEquals(a.miles, b.miles, 1e-9)
        assertArrayEquals(a.lat, b.lat, 1e-4, "positions are cached to about 10 m")
        assertArrayEquals(a.atSpeed, b.atSpeed, 1e-9)
        assertEquals(123, b.start)
    }
}
