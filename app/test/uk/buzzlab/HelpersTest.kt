package uk.buzzlab

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Calendar

/** Small rules the screens rely on. */
class HelpersTest {
    @Test
    fun `date ranges start at midnight`() {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(0, since(0))
        assertEquals(today, since(1))
        assertEquals(today - 6 * 86400000L, since(2), "last 7 days includes today")
        assertEquals(today - 29 * 86400000L, since(3))
        assertEquals(today - 89 * 86400000L, since(4))
    }

    @Test
    fun `filter by how a drive started`() {
        val auto = JSONObject().put("auto", true)
        val hand = JSONObject()
        assertTrue(startedAs(auto, 0) && startedAs(hand, 0))
        assertTrue(startedAs(auto, 1) && !startedAs(hand, 1))
        assertTrue(!startedAs(auto, 2) && startedAs(hand, 2))
    }

    @Test
    fun `car names show the tyre, and a missing car picks the first`() {
        val cs = listOf(JSONObject().put("id", "a").put("name", "One").put("tyre", "225/45R17"), JSONObject().put("id", "b").put("name", "Two"))
        assertEquals(listOf("One  (225/45R17)", "Two"), names(cs))
        assertEquals(1, idx(cs, "b"))
        assertEquals(0, idx(cs, "gone"))
    }

    @Test
    fun `server problems read as plain words`() {
        assertEquals("Wrong user name or password.", problem(401, ""))
        assertEquals("No connection: timeout", problem(0, "timeout"))
        assertEquals("too many drives", problem(400, """{"error":"too many drives"}"""))
        assertEquals("Server said 502", problem(502, "<html>"))
    }

    @Test
    fun `feelings read back in speed order`() {
        val m = JSONObject().put("feel", JSONObject().put("50-80", "None").put("10-30", "Strong"))
        assertEquals("10 to 30 mph strong, 50 to 80 mph none", feelText(m))
        assertEquals("", feelText(JSONObject()))
    }

    @Test
    fun `speed colours run blue to red and blend between`() {
        assertEquals(0xFF3F7FE0.toInt(), MapView.speedColour(0.0))
        assertEquals(0xFFFF3B1F.toInt(), MapView.speedColour(90.0))
        assertEquals(0xFF7C8B95.toInt(), MapView.speedColour(Double.NaN), "no speed is grey")
        val mid = MapView.speedColour(10.0)
        assertEquals((0x3F + 0x2E + 1) / 2, mid shr 16 and 255)
        assertEquals((0xE0 + 0xB6) / 2, mid and 255)
    }

    @Test
    fun `scenes are complete`() {
        assertTrue(Scenes.ALL.size >= 2, "calibration tunes the first two")
        assertEquals(Scenes.ALL.size, Scenes.ALL.map { it.key }.toSet().size, "keys are unique")
        assertFalse(Scenes.ALL.any { it.rate <= 0 || it.level <= 0 })
        for (t in Scenes.TYRES) assertTrue(Store.circ(t) > 1.5, t)
    }
}
