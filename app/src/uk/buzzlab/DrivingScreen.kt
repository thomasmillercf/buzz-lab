package uk.buzzlab

import android.util.Log
import uk.buzzlab.MainActivity.Companion.AC
import uk.buzzlab.MainActivity.Companion.BG
import uk.buzzlab.MainActivity.Companion.MU
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.floor

/** Core driving numbers and everywhere a car has been. */
fun MainActivity.driving() {
    val cs = cars()
    if (cs.isEmpty()) {
        top("Driving")
        text("Add a car on Home first.", 16)
        return
    }
    val c = cs[idx(cs, pref("dcar"))]
    top(c.optString("name"))
    val pair = row()
    column(pair) {
        note("Car", 13)
        spinner(cs.map { it.optString("name") }, idx(cs, pref("dcar"))) {
            ui.edit().putString("dcar", cs[it].optString("id")).apply()
            show("driving")
        }
    }
    column(pair) {
        note("Dates", 13)
        spinner(MainActivity.DATES, ui.getInt("ddr", 0)) {
            ui.edit().putInt("ddr", it).apply()
            show("driving")
        }
    }
    note("How it started", 13)
    spinner(MainActivity.HOW, ui.getInt("dh", 0)) {
        ui.edit().putInt("dh", it).apply()
        show("driving")
    }
    val w = note("Working...", 15)
    val page = list
    val from = since(ui.getInt("ddr", 0))
    val how = ui.getInt("dh", 0)
    thread {
        val mine = st.list().filter {
            it.optString("car") == c.optString("id") && !it.optBoolean("recording") && it.optLong("start") >= from && startedAs(it, how)
        }
        val trips = mine.mapNotNull { m ->
            try {
                Trip.load(st.driveDir(m.optString("id")), m.optLong("start"))
            } catch (x: Throwable) {
                Log.w("BUZZLAB", "trip $x")
                null
            }
        }
        runOnUiThread {
            if (w.parent !== page || tab != "driving") return@runOnUiThread
            if (mine.isEmpty()) {
                w.text = "No drives for this car in these dates."
                return@runOnUiThread
            }
            page.removeView(w)
            drivingTotals(mine.size, trips)
        }
    }
}

private fun MainActivity.drivingTotals(drives: Int, ts: List<Trip>) {
    val mi = ts.sumOf { it.miles }
    val moving = ts.sumOf { it.movingS }
    val at = DoubleArray(16)
    for (t in ts) for (i in 0 until 16) at[i] += t.atSpeed[i]
    val f = figures("Drives", "Miles", "Driving time") + figures("Average, mph", "Top, mph", "Longest, mi")
    f[0].text = "$drives"
    f[1].text = String.format(Locale.UK, if (mi >= 100) "%.0f" else "%.1f", mi)
    f[2].text = String.format(Locale.UK, "%d:%02d", (moving / 3600).toInt(), (moving % 3600 / 60).toInt())
    f[3].text = if (moving > 0) "${Math.round(mi / (moving / 3600))}" else MainActivity.DASH
    f[4].text = "${Math.round(ts.maxOfOrNull { it.topMph } ?: 0.0)}"
    f[5].text = String.format(Locale.UK, "%.1f", ts.maxOfOrNull { it.miles } ?: 0.0)
    f.forEach { it.textSize = 24f }

    heading("Time at each speed")
    val mins = DoubleArray(15) { i -> (if (i == 14) at[14] + at[15] else at[i]) / 60 }
    bars("Chart of minutes at each speed", mins, Array(15) { "${it * 5}" }, " min", AC)
    note("Minutes moving, by mph. Stopped time is left out.", 13)

    val range = ui.getInt("ddr", 0)
    val days = maxOf(7, listOf(30, 1, 7, 30, 90)[range.coerceIn(0, 4)])
    val d0 = since(1) - 86400000L * (days - 1)
    val per = DoubleArray(days)
    for (t in ts) {
        val i = floor((t.start - d0) / 86400000.0).toInt()
        if (i in 0 until days) per[i] += t.miles
    }
    heading("Miles per day")
    bars("Chart of miles driven each day", per, Array(days) { date(d0 + 86400000L * it, "d MMM") }, " mi", 0xFF2EC4B6.toInt())
    note("The last $days days.", 13)

    heading("Everywhere it has been")
    list.addView(MapView(this, ts, BG))
    speedKey()
    note("Every drive in these dates, coloured by speed. Drag or pinch; double-tap to fit.", 13)
}

private fun MainActivity.bars(what: String, values: DoubleArray, labels: Array<String>, unit: String, colour: Int) =
    list.addView(Charts.Bars(this, values, labels, unit, colour, MU).apply { contentDescription = what })
