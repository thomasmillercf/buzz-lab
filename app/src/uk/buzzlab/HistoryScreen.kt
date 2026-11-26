package uk.buzzlab

import android.app.AlertDialog
import android.content.Intent
import org.json.JSONObject
import uk.buzzlab.MainActivity.Companion.ON

/** The drive list lives on Analysis; a drive's Back returns to it. */
fun MainActivity.history() {
    ui.edit().putInt("atab", 1).apply()
    show("analysis")
}

private fun MainActivity.historyFilters() {
    page("Filter history") { history() }
    val cs = cars()
    note("Car")
    spinner(listOf("All cars") + cs.map { it.optString("name") }, cs.indexOfFirst { it.optString("id") == pref("hcar") } + 1) {
        ui.edit().putString("hcar", if (it == 0) "" else cs[it - 1].optString("id")).apply()
    }
    note("Phone position")
    spinner(listOf("Any phone position") + MainActivity.PLACES, ui.getInt("hp", 0)) { ui.edit().putInt("hp", it).apply() }
    note("Dates")
    spinner(MainActivity.DATES, ui.getInt("hd", 0)) { ui.edit().putInt("hd", it).apply() }
    note("How it started")
    spinner(MainActivity.HOW, ui.getInt("hh", 0)) { ui.edit().putInt("hh", it).apply() }
    primary(button("Show drives") { history() })
    button("Clear filters") {
        ui.edit().remove("hcar").remove("hp").remove("hd").remove("hh").apply()
        history()
    }
}

/** Drives by day, filtered. Holding one starts choosing several to share, export or delete. */
fun MainActivity.historyList() {
    var car = pref("hcar")
    if (car.isNotEmpty() && st.car(car) == null) {
        car = ""
        ui.edit().remove("hcar").apply()
    }
    val hp = ui.getInt("hp", 0)
    val hd = ui.getInt("hd", 0)
    val hh = ui.getInt("hh", 0)
    val from = since(hd)
    val every = st.list()
    val all = every.size
    val ds = every.filter {
        (car.isEmpty() || it.optString("car") == car) &&
            (hp == 0 || it.optString("place") == MainActivity.PLACES[hp - 1]) &&
            it.optLong("start") >= from &&
            startedAs(it, hh)
    }
    val chosen = picked
    if (chosen != null) {
        selectionBar(chosen, every, ds)
    } else {
        top(
            when {
                all == 0 -> "No drives yet"
                ds.size == all -> "$all ${if (all == 1) "drive" else "drives"}"
                else -> "${ds.size} of $all drives"
            },
        )
        if (ds.isNotEmpty() && SyncJob.ready(this)) icon(R.drawable.i_share, "Share") { shareThese(ds, "Vibration results", null) { history() } }
        val o = LinkedHashMap<String, () -> Unit>()
        if (ds.isNotEmpty()) o[if (ds.size == all) "Export all drives" else "Export these ${ds.size}"] = { export(ds) }
        if (cars().isNotEmpty()) o["Import a phyphox zip"] = { pickImport() }
        if (o.isNotEmpty()) overflow(o)
    }
    segment()
    if (all > 0) {
        val f = listOfNotNull(
            if (car.isNotEmpty()) carName(car) else null,
            if (hp > 0) MainActivity.PLACES[hp - 1] else null,
            if (hd > 0) MainActivity.DATES[hd] else null,
            if (hh == 1) {
                "automatic"
            } else if (hh == 2) {
                "started by me"
            } else {
                null
            },
        )
        button((if (f.isEmpty()) "All drives" else f.joinToString(", ")) + "  ▾") { historyFilters() }.textSize = 14f
    }
    if (all == 0) {
        note("Record a drive on Home and it appears here.", 15)
    } else if (ds.isEmpty()) {
        note("Nothing matches. Tap the filter above to change it.", 15)
    }
    var day = ""
    for (m in ds) {
        val d = date(m.optLong("start"), "EEEE d MMMM")
        if (d != day) {
            day = d
            text(d, 14, true, MainActivity.MU)
        }
        driveRow(m)
    }
    if (picked == null && ds.isNotEmpty()) note("Hold a drive to select several.", 13)
}

private fun MainActivity.selectionBar(chosen: MutableSet<String>, every: List<JSONObject>, shown: List<JSONObject>) {
    val sel = every.filter { it.optString("id") in chosen }
    top("${sel.size} selected")
    val cx = icon(R.drawable.i_close, "Cancel selection") {
        picked = null
        show("analysis")
    }
    bar.removeView(cx)
    bar.addView(cx, 0)
    if (sel.isNotEmpty()) {
        if (SyncJob.ready(this)) icon(R.drawable.i_share, "Share selected") { shareThese(sel, "Vibration results", null) { history() } }
        icon(R.drawable.i_delete, "Delete selected") {
            val what = "${sel.size} ${if (sel.size == 1) "drive" else "drives"}"
            ask("Delete $what from this phone? This can't be undone. Copies already on the server stay.") {
                for (m in sel) st.delDrive(m.optString("id"))
                toast("Deleted ${sel.size}")
                picked = null
                show("analysis")
            }
        }
    }
    val o = LinkedHashMap<String, () -> Unit>()
    if (sel.isNotEmpty()) o["Export selected"] = { export(sel) }
    o["Select all shown"] = {
        shown.forEach { chosen.add(it.optString("id")) }
        show("analysis")
    }
    o["Select none"] = {
        chosen.clear()
        show("analysis")
    }
    overflow(o)
}

private fun MainActivity.driveRow(m: JSONObject) {
    val start = m.optLong("start")
    val secs = maxOf(0, (m.optLong("end", start) - start) / 1000)
    val len = (if (m.optBoolean("auto")) "Automatic, " else "") + if (secs < 60) "$secs s" else "${Math.round(secs / 60.0)} min"
    val sync = when {
        !SyncJob.ready(this) -> ""
        m.has("syncErr") -> "\n" + m.optString("syncErr")
        SyncJob.pending(m, url()) -> ", waiting to sync"
        else -> ""
    }
    val id = m.optString("id")
    val chosen = picked
    val on = chosen != null && id in chosen
    val tick = if (chosen == null) {
        ""
    } else if (on) {
        "☑  "
    } else {
        "☐  "
    }
    val problem = if (m.has("error")) " (problem: ${m.optString("error")})" else ""
    val b = button("$tick${carName(m.optString("car"))}   ${date(start, "HH:mm")}", "$len, ${m.optString("place")}$problem$sync") {
        val now = picked
        if (now == null) {
            drive(m)
        } else {
            if (!now.remove(id)) now.add(id)
            show("analysis")
        }
    }
    if (on) b.background = box(ON)
    b.setOnLongClickListener {
        tap(it)
        (picked ?: LinkedHashSet<String>().also { s -> picked = s }).add(id)
        show("analysis")
        true
    }
}

/** Imports go to the car and phone position chosen on Home. */
fun MainActivity.pickImport() {
    val cs = cars()
    val c = cs[idx(cs, pref("car"))]
    impCar = c.optString("id")
    impTyre = c.optString("tyre")
    impPlace = place()
    AlertDialog.Builder(this).setMessage("Import as ${c.optString("name")}, $impPlace? Change these on Home first if they're wrong.")
        .setPositiveButton("Choose zip") { _, _ ->
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip"),
                MainActivity.PICK_IMPORT,
            )
        }.setNegativeButton("Cancel", null).show()
}
