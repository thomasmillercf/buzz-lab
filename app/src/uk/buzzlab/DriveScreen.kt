package uk.buzzlab

import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import uk.buzzlab.MainActivity.Companion.AC
import uk.buzzlab.MainActivity.Companion.BG
import uk.buzzlab.MainActivity.Companion.CARD
import uk.buzzlab.MainActivity.Companion.FG
import uk.buzzlab.MainActivity.Companion.MU
import uk.buzzlab.MainActivity.Companion.NAV
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/** Speed ranges asked about after a drive. */
private val FEEL_RANGES = listOf(10 to 30, 30 to 50, 50 to 80)

/** "10 to 30 mph strong, 30 to 50 mph none", or empty if not answered. */
fun feelText(m: JSONObject): String {
    val f = m.optJSONObject("feel") ?: return ""
    return FEEL_RANGES.mapNotNull { (lo, hi) ->
        f.optString("$lo-$hi").takeIf { it.isNotEmpty() }?.let { "$lo to $hi mph ${it.lowercase(Locale.UK)}" }
    }.joinToString(", ")
}

/** One drive: journey, vibration charts, and how it felt. */
fun MainActivity.drive(m: JSONObject) {
    val id = m.optString("id")
    page(carName(m.optString("car"))) { history() }
    if (SyncJob.ready(this)) icon(R.drawable.i_share, "Share") { shareOne(m) }
    val notOnServer = if (m.optString("syncedPos").isEmpty()) " It isn't on the server." else " The copy on the server stays."
    overflow(
        linkedMapOf(
            "How it felt" to { rate(m) { findDrive(id)?.let { drive(it) } ?: history() } },
            "Export" to { export(listOf(m)) },
            "Delete" to {
                ask("Delete this drive? This can't be undone.$notOnServer") {
                    st.delDrive(id)
                    history()
                }
            },
        ),
    )
    val tyre = m.optString("tyre")
    note(
        date(m.optLong("start")) + (if (m.optBoolean("auto")) ", started by itself" else "") + "\n" + m.optString("place") +
            (if (tyre.isEmpty()) "" else "\nTyres $tyre"),
        15,
    )
    val stv = text("Working out the stats...", 15)
    status = stv
    thread {
        try {
            val dir = st.driveDir(id)
            val d = Dsp.load(dir)
            val trip = try {
                Trip.load(dir, m.optLong("start"))
            } catch (x: Throwable) {
                Log.w("BUZZLAB", "trip $x")
                null
            }
            runOnUiThread {
                if (status !== stv) return@runOnUiThread
                if (trip != null && trip.t.size > 1) journey(trip)
                stv.text = String.format(Locale.UK, "%.1f miles, top speed %.0f mph, %d s of steady driving", d.km * .6214, d.maxMph, d.count(0, 80) * 2)
                stats(d, null, null, tyre, 10, 80)
                val marks = Store.read(File(dir, "marks.csv")).orEmpty().split("\n")
                val strong = marks.count { it.endsWith("Strong") }
                val weak = marks.count { it.endsWith("Weak") }
                val gone = marks.count { it.endsWith("Gone") }
                val fe = feelText(m)
                note(if (fe.isEmpty()) "How it felt: not answered. Use the menu above." else "Felt: $fe", 15)
                if (m.optString("note").isNotEmpty()) note("Note: ${m.optString("note")}", 15)
                if (strong + weak + gone > 0) note("Marks while driving: $strong strong, $weak weak, $gone gone", 15)
            }
        } catch (x: Throwable) {
            Log.e("BUZZLAB", "drive", x)
            runOnUiThread { status?.text = "Couldn't read this drive: ${x.message}" }
        }
    }
}

/** Speed over time and the route, for one drive. */
private fun MainActivity.journey(t: Trip) {
    val f = figures("Miles", "Average, mph", "Top, mph")
    f[0].text = String.format(Locale.UK, "%.1f", t.miles)
    f[1].text = "${Math.round(t.avgMoving())}"
    f[2].text = "${Math.round(t.topMph)}"
    f.forEach { it.textSize = 26f }
    heading("Speed")
    list.addView(Charts.Speed(this, t, MU))
    heading("Route")
    list.addView(MapView(this, listOf(t), BG))
    speedKey()
    note("Average leaves out time stopped. Drag or pinch the map; double-tap to fit it again.", 13)
}

/** The colours used for speed on maps and charts. */
fun MainActivity.speedKey() {
    val k = row()
    k.gravity = Gravity.CENTER_VERTICAL
    k.setPadding(0, dp(6), 0, dp(2))
    fun label(s: String) = TextView(this).apply {
        text = s
        setTextColor(MU)
        textSize = 12f
    }
    for (v in listOf(0, 20, 40, 55, 70)) {
        k.addView(View(this).apply { setBackgroundColor(MapView.speedColour(v.toDouble())) }, android.widget.LinearLayout.LayoutParams(dp(18), dp(6)))
        k.addView(label(" $v${if (v == 70) "+" else ""}   "))
    }
    k.addView(label("mph"))
}

/** Heat map, spectrum and a table of bands, for one drive or a set added together. */
fun MainActivity.stats(d: Dsp, other: Dsp?, otherName: String?, tyre: String, lo: Int, hi: Int) {
    val p = d.mean(lo, hi)
    val q = other?.mean(lo, hi)
    if (p == null) {
        text("Not enough steady driving at these speeds.", 16)
        return
    }
    val log = ui.getBoolean("log", false)
    heading("Strength by speed")
    val c = Store.circ(tyre)
    val hm = Charts.Heat(this, d, c, true, MU)
    hm.log = log
    list.addView(hm)
    note(
        "Brighter means stronger. From 0 to 0.35 m/s²/√Hz, or doubling from 0.005 to 0.32 with log strength on. Frequency is on a log scale." +
            (if (c > 0) " The white line is once per wheel turn." else " Add a tyre size for the wheel-turn line.") +
            " Lightly smoothed to cut speckle. Columns need 6 seconds of steady driving.",
    )
    heading("Average spectrum")
    val ln = Charts.Line(this, p, q, true, MU, log)
    list.addView(ln)
    checkBox("Log strength, both charts", log).setOnCheckedChangeListener { _, on ->
        ui.edit().putBoolean("log", on).apply()
        ln.log = on
        hm.log = on
        ln.invalidate()
        hm.invalidate()
    }
    val against = if (q == null) "" else " against ${Dsp.peak(q, 15, 100)} Hz"
    note(
        "Orange: this selection" + (if (q == null) "" else ". Grey: $otherName") + ". Strongest above 15 Hz: ${Dsp.peak(p, 15, 100)} Hz$against" +
            String.format(Locale.UK, ". Along the phone's length at 25 to 35 Hz: %.0f%%.", 100 * d.foreAft(lo, hi)),
    )
    val s = StringBuilder("Hz        m/s2" + (if (q == null) "" else "   other  ratio") + "\n")
    for ((a, b) in listOf(2 to 8, 8 to 20, 25 to 35, 38 to 48, 50 to 80, 80 to 120, 120 to 230)) {
        val r = Dsp.rms(p, a, b)
        s.append(String.format(Locale.UK, "%-8s %5.2f", "$a-$b", r))
        if (q != null) {
            val z = Dsp.rms(q, a, b)
            s.append(String.format(Locale.UK, "   %5.2f  %4.1fx", z, r / z))
        }
        s.append('\n')
    }
    text(s.toString(), 15).typeface = Typeface.MONOSPACE
    if (c > 0) note(String.format(Locale.UK, "Wheel turns: %.1f Hz at 30 mph, %.1f at 50, %.1f at 70.", 30 / Dsp.MPH / c, 50 / Dsp.MPH / c, 70 / Dsp.MPH / c))
}

/** Asked once parked: what was felt, for each speed range the drive covers. */
fun MainActivity.rate(m: JSONObject, after: () -> Unit) {
    page("How did it feel?", after)
    val w = note("Working out your speeds...", 15)
    thread {
        val d = try {
            Dsp.load(st.driveDir(m.optString("id")))
        } catch (x: Throwable) {
            Log.w("BUZZLAB", "rate $x")
            null
        }
        runOnUiThread { if (w.parent != null) feelForm(m, d, w, after) }
    }
}

private fun MainActivity.feelForm(m: JSONObject, d: Dsp?, w: TextView, after: () -> Unit) {
    w.text = "The buzz, at each speed you drove. Skip any you're not sure about."
    val old = m.optJSONObject("feel")
    val pick = LinkedHashMap<String, String>()
    var shown = 0
    for ((lo, hi) in FEEL_RANGES) {
        if (d != null && d.count(lo, hi) < 5) continue
        shown++
        val key = "$lo-$hi"
        old?.optString(key)?.takeIf { it.isNotEmpty() }?.let { pick[key] = it }
        text("$lo to $hi mph", 16, true)
        val r = row()
        val bs = ArrayList<Button>()
        val paint = {
            for (x in bs) {
                val on = x.text.toString() == pick[key]
                x.background = box(if (on) AC else CARD)
                x.setTextColor(if (on) NAV else FG)
            }
        }
        for (v in listOf("Strong", "Weak", "None")) {
            column(r) {
                val b = button(v)
                b.gravity = Gravity.CENTER
                b.setPadding(0, 0, 0, 0)
                b.minHeight = dp(60)
                b.setOnClickListener {
                    tap(it)
                    if (pick[key] == v) pick.remove(key) else pick[key] = v
                    paint()
                }
                bs += b
            }
        }
        paint()
    }
    if (shown == 0) note("There wasn't enough steady driving to ask about.", 15)
    val noteBox = field("Note (optional, stays off shared links)", m.optString("note"))
    primary(
        button("Save") {
            try {
                val n = findDrive(m.optString("id"))
                if (n != null) {
                    val feel = JSONObject()
                    pick.forEach { (k, v) -> feel.put(k, v) }
                    n.put("feel", feel).put("note", noteBox.text.toString().trim())
                    n.remove("syncedPos")
                    n.remove("syncErr")
                    st.saveMeta(n)
                    toast("Saved")
                }
            } catch (e: Exception) {
                toast(e.toString(), true)
            }
            SyncJob.schedule(this)
            after()
        },
    )
    quiet(
        button("Skip") {
            runCatching { findDrive(m.optString("id"))?.let { st.saveMeta(it.put("feelSkipped", true)) } }
            after()
        },
    )
}
