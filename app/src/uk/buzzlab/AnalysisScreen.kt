package uk.buzzlab

import android.util.Log
import org.json.JSONObject
import kotlin.concurrent.thread

private val SPEEDS = listOf("10 to 80 mph", "10 to 30 mph", "30 to 50 mph", "50 to 80 mph")
private val SPEED_RANGES = listOf(10 to 80, 10 to 30, 30 to 50, 50 to 80)

/** Averages and History, switched at the top. */
fun MainActivity.segment() {
    val at = ui.getInt("atab", 0)
    val bs = toggles(listOf("Averages", "History"), 2, 8)
    choose(bs, at)
    bs.forEachIndexed { k, b ->
        b.setOnClickListener {
            tap(it)
            if (ui.getInt("atab", 0) == k) return@setOnClickListener
            ui.edit().putInt("atab", k).apply()
            pos.remove("analysis")
            sv.scrollTo(0, 0)
            show("analysis")
        }
    }
}

/** Every matching drive of a car added together, optionally against a second car. */
fun MainActivity.analysis() {
    if (ui.getInt("atab", 0) == 1) {
        historyList()
        return
    }
    top("Results")
    segment()
    val cs = cars()
    if (cs.isEmpty()) {
        text("Add a car on Home first.", 16)
        return
    }
    val c = cs[idx(cs, pref("acar"))]
    val k = minOf(ui.getInt("cmp", 0), cs.size)
    val o = if (k == 0) null else cs[k - 1]
    top(c.optString("name") + (o?.let { " and " + it.optString("name") } ?: ""))
    icon(R.drawable.i_info, "Reading the charts") { chartGuide { show("analysis") } }
    if (SyncJob.ready(this)) icon(R.drawable.i_share, "Share") { shareAnalysis() }
    val nm = cs.map { it.optString("name") }
    val pair = row()
    column(pair) {
        note("Car", 13)
        spinner(nm, idx(cs, pref("acar"))) {
            ui.edit().putString("acar", cs[it].optString("id")).apply()
            show("analysis")
        }
    }
    column(pair) {
        note("Compare with", 13)
        spinner(listOf("No comparison") + nm, k) {
            ui.edit().putInt("cmp", it).apply()
            show("analysis")
        }
    }
    val ap = ui.getInt("ap", 0)
    val how = when (ui.getInt("ah", 0)) {
        1 -> ", automatic"
        2 -> ", started by me"
        else -> ""
    }
    val where = if (ap == 0) "any phone position" else MainActivity.PLACES[ap - 1]
    button("${MainActivity.DATES[ui.getInt("dr", 0)]}, ${SPEEDS[ui.getInt("sr", 0)]}, $where$how  ▾") { filters() }.textSize = 14f
    button("Feel it on the phone", "Play this buzz at each speed, through the motor") {
        ui.edit().remove("fdrive").apply()
        show("feel")
    }
    val stv = text("Working...", 15)
    status = stv
    val (lo, hi) = SPEED_RANGES[ui.getInt("sr", 0)]
    thread {
        try {
            val n = IntArray(2)
            val a = sum(c, n, 0)
            val b = o?.let { sum(it, n, 1) }
            runOnUiThread {
                if (status !== stv) return@runOnUiThread
                val other = if (b == null) "" else "; ${o?.optString("name")}: ${n[1]} drives, ${b.count(lo, hi) * 2} s"
                stv.text = "${n[0]} drives, ${a.count(lo, hi) * 2} s of steady driving$other"
                stats(a, b, o?.optString("name"), c.optString("tyre"), lo, hi)
                if (b != null) note("Different roads and phone positions change these numbers. Compare the same route and position for a fair result.")
            }
        } catch (x: Throwable) {
            Log.e("BUZZLAB", "an", x)
            say("Couldn't work that out: ${x.message}")
        }
    }
}

private fun MainActivity.filters() {
    page("Filters") { show("analysis") }
    note("Dates")
    spinner(MainActivity.DATES, ui.getInt("dr", 0)) { ui.edit().putInt("dr", it).apply() }
    note("Speeds")
    spinner(SPEEDS, ui.getInt("sr", 0)) { ui.edit().putInt("sr", it).apply() }
    note("Phone position")
    spinner(listOf("Any phone position") + MainActivity.PLACES, ui.getInt("ap", 0)) { ui.edit().putInt("ap", it).apply() }
    note("How it started")
    spinner(MainActivity.HOW, ui.getInt("ah", 0)) { ui.edit().putInt("ah", it).apply() }
    note("For a fair comparison, use one phone position.")
    primary(button("Show results") { show("analysis") })
}

/** A link that opens on the same view: car, comparison, phone position and speeds. */
private fun MainActivity.shareAnalysis() {
    val cs = cars()
    val c = cs[idx(cs, pref("acar"))]
    val k = minOf(ui.getInt("cmp", 0), cs.size)
    val ap = ui.getInt("ap", 0)
    val o = if (k == 0) null else cs[k - 1]
    val (lo, hi) = SPEED_RANGES[ui.getInt("sr", 0)]
    val ds = matching(c).toMutableList()
    if (o != null && o.optString("id") != c.optString("id")) ds += matching(o)
    try {
        val view = JSONObject().put("car", c.optString("name")).put("with", o?.optString("name").orEmpty())
            .put("place", if (ap == 0) "" else MainActivity.PLACES[ap - 1]).put("lo", lo).put("hi", hi)
        val title = c.optString("name") + (o?.let { " and " + it.optString("name") } ?: "")
        shareThese(ds, title, view) { show("analysis") }
    } catch (x: Exception) {
        toast(x.toString(), true)
    }
}

/** Drives of a car that pass the date, phone position and start choices on Analysis. */
fun MainActivity.matching(c: JSONObject): List<JSONObject> {
    val from = since(ui.getInt("dr", 0))
    val ap = ui.getInt("ap", 0)
    val ah = ui.getInt("ah", 0)
    return st.list().filter {
        it.optString("car") == c.optString("id") &&
            !it.optBoolean("recording") &&
            it.optLong("start") >= from &&
            (ap == 0 || it.optString("place") == MainActivity.PLACES[ap - 1]) &&
            startedAs(it, ah)
    }
}

/** Adds up the matching drives; n[at] counts the ones that could be read. */
fun MainActivity.sum(c: JSONObject, n: IntArray, at: Int): Dsp {
    val d = Dsp()
    for (m in matching(c)) {
        try {
            d.add(Dsp.load(st.driveDir(m.getString("id"))))
            n[at]++
        } catch (x: Exception) {
            Log.w("BUZZLAB", "skip ${m.optString("id")} $x")
        }
    }
    return d
}
