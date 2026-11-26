package uk.buzzlab

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.PI

class StoreTest {
    @Test
    fun `tyre size gives the rolling distance`() {
        // 17 in rim + 2 x 45% of 225 mm sidewall, times the 0.97 rolling factor
        assertEquals(PI * (17 * 25.4 + 2 * 225 * .45) / 1000 * .97, Store.circ("225/45R17"), 1e-9)
        assertEquals(Store.circ("225/45R17"), Store.circ("225/45 R17"), 1e-9)
        assertEquals(0.0, Store.circ("big"))
        assertEquals(0.0, Store.circ(null))
    }

    @Test
    fun `cars are added, replaced and deleted`() {
        val st = Store(Fixtures.tempDir())
        st.putCar(JSONObject().put("id", "a").put("name", "One"))
        st.putCar(JSONObject().put("id", "b").put("name", "Two"))
        st.putCar(JSONObject().put("id", "a").put("name", "Renamed"))
        assertEquals(listOf("Renamed", "Two"), st.cars().objects().map { it.getString("name") })
        st.delCar("a")
        assertNull(st.car("a"))
        assertEquals("Two", st.car("b")?.getString("name"))
    }

    @Test
    fun `drives list newest first and delete completely`() {
        val st = Store(Fixtures.tempDir())
        for (id in listOf("1700000000000", "1700000500000", "1700000200000")) {
            st.driveDir(id).mkdirs()
            st.saveMeta(JSONObject().put("id", id).put("start", id.toLong()))
        }
        assertEquals(listOf("1700000500000", "1700000200000", "1700000000000"), st.list().map { it.getString("id") })
        st.delDrive("1700000200000")
        assertFalse(st.driveDir("1700000200000").exists())
        assertEquals(2, st.list().size)
    }

    @Test
    fun `writes replace the whole file`() {
        val f = Fixtures.tempDir().resolve("x.json")
        Store.write(f, "a long first version")
        Store.write(f, "short")
        assertEquals("short", Store.read(f))
        assertNull(Store.read(f.resolveSibling("missing")))
    }
}
