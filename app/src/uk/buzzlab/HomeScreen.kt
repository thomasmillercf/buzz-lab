package uk.buzzlab

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.location.LocationManager
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import org.json.JSONObject
import uk.buzzlab.MainActivity.Companion.AC
import uk.buzzlab.MainActivity.Companion.DASH
import uk.buzzlab.MainActivity.Companion.FG
import uk.buzzlab.MainActivity.Companion.MU
import uk.buzzlab.MainActivity.Companion.ON
import uk.buzzlab.MainActivity.Companion.RED
import java.util.Locale

/** Home: pick the car and phone position and start recording. While recording, the live screen instead. */
fun MainActivity.home() {
    val r = RecService.live
    top(if (r != null) "Recording" else "Buzz Lab")
    if (r != null) {
        recording(r)
        return
    }
    lateinit var more: ImageButton
    more = icon(R.drawable.i_more, "More") { homeMenu(more) }
    val cs = cars()
    if (cs.isEmpty()) {
        text("Add a car to start.", 16)
        button("Add a car") { editCar(null) }
        return
    }
    note("Car", 15)
    spinner(names(cs), idx(cs, pref("car"))) { ui.edit().putString("car", cs[it].optString("id")).apply() }
    note("Phone position", 15)
    spinner(MainActivity.PLACES.asList(), ui.getInt("place", 0)) { ui.edit().putInt("place", it).apply() }
    primary(button("Start recording") { startRec(cs, it as Button) }).apply {
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        minHeight = dp(72)
    }
    foot.visibility = View.VISIBLE
    if (ui.getBoolean("auto", false)) {
        val watching = RecService.svc?.watch == true
        text(
            if (watching) {
                "◉  Automatic recording is on. It starts when Android Auto connects, or once you're above ${RecService.START_MPH.toInt()} mph."
            } else {
                "Automatic recording is on but not running. Open Settings to turn it back on."
            },
            14,
            col = if (RecService.svc != null) MU else AC,
        )
    }
    askAboutLastDrive()
    if (signInCard()) return // offline: offer sign-in in place of the sync line
    val sy = button(syncLine()) { syncPage() }
    sy.textSize = 14f
    SyncJob.schedule(this)
    tick.postDelayed(
        object : Runnable {
            override fun run() {
                if (sub != null || tab != "home" || RecService.live != null) return
                sy.text = syncLine()
                tick.postDelayed(this, 1000)
            }
        },
        1000,
    )
}

/** A drive that recorded by itself hasn't been asked about yet. */
private fun MainActivity.askAboutLastDrive() {
    val m = st.list().firstOrNull { !it.optBoolean("recording") } ?: return
    val long = m.optLong("end") - m.optLong("start") >= 60000
    if (!m.optBoolean("auto") || m.has("feel") || m.optBoolean("feelSkipped") || !long) return
    val b = button(
        "How did your last drive feel?",
        "${carName(m.optString("car"))}, ${date(m.optLong("start"))}. It started and saved by itself.",
    ) { rate(m) { show("home") } }
    b.background = box(ON)
}

private fun MainActivity.homeMenu(at: View) {
    val items = LinkedHashMap<String, () -> Unit>()
    if (SyncJob.ready(this) && st.list().isNotEmpty()) items["Share results"] = { sharePage() }
    items["Settings"] = { settings() }
    popup(at, items)
}

/** Permissions first, then start the recorder and wait to hear that it started. */
fun MainActivity.startRec(cs: List<JSONObject>, b: Button?) {
    val need = ArrayList<String>()
    if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) need += listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS) && !ui.getBoolean("askedN", false)) {
        need += Manifest.permission.POST_NOTIFICATIONS
        ui.edit().putBoolean("askedN", true).apply()
    }
    if (need.isNotEmpty()) {
        requestPermissions(need.toTypedArray(), MainActivity.ASK_RECORD)
        return
    }
    if (!getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        toast("Turn on location first.", true)
        return
    }
    val c = cs[idx(cs, pref("car"))]
    val ri = Intent(this, RecService::class.java).putExtra("car", c.optString("id")).putExtra("tyre", c.optString("tyre"))
        .putExtra("place", place()).putExtra("note", "")
    if (b != null) working(b, "Starting...") else toast("Starting...")
    RecService.lastError = null
    wait.postDelayed({ startForegroundService(ri) }, 40)
    until(6000, { RecService.live != null || RecService.lastError != null }, {
        val er = if (RecService.live == null) RecService.lastError else null
        show("home")
        toast(if (er == null) "Recording started" else "Recording didn't start: $er", er != null)
    }, {
        show("home")
        toast("Recording didn't start. Check location is on and allowed.", true)
    })
}

/** While recording: speed and live charts. After Stop: totals, with Resume, Save and Discard. */
private fun MainActivity.recording(rs: RecService) {
    val stopped = rs.paused
    val title = top(if (stopped) "Stopped" else "Recording")
    title.setTextColor(if (stopped) FG else AC)
    val fv = if (stopped) figures("Time", "Miles") + figures("Top speed, mph", "Strongest, Hz") else figures("Speed, mph", "Strongest, Hz")
    val line = note(if (stopped) "Nothing is being recorded. Resume carries on in the same drive." else "Waiting for GPS")
    liveLine = line
    val seg = toggles(listOf(if (stopped) "Average" else "Now", "By speed"))
    val ln = Charts.Line(this, null, null, true, MU, false)
    val hm = Charts.Heat(this, rs.charts.sum, Store.circ(rs.meta?.optString("tyre")), true, MU)
    list.addView(ln)
    list.addView(hm)
    val cap = note("", 13)
    val pick = {
        val v = ui.getInt("liveView", 0)
        choose(seg, v)
        val c = rs.charts
        val any = c.sum.n.any { it >= 3 }
        ln.visibility = if (v == 0 && c.now != null) View.VISIBLE else View.GONE
        hm.visibility = if (v == 1 && any) View.VISIBLE else View.GONE
        cap.text = when {
            v == 1 && any -> "Brighter is stronger. The saved results come from the full recording."
            v == 1 -> "Fills in once you hold a steady speed above 5 mph for a few seconds."
            c.now == null -> "The chart starts a few seconds after recording begins."
            stopped -> "Grey is the average while steady. Orange is the last reading."
            else -> "Orange is now, grey is the average while steady."
        }
    }
    seg.forEachIndexed { k, b ->
        b.setOnClickListener {
            tap(it)
            ui.edit().putInt("liveView", k).apply()
            pick()
        }
    }
    if (!stopped) note("For passengers only while moving.", 13)
    act.removeAllViews()
    act.visibility = View.VISIBLE
    var main: Button? = null
    if (!stopped) {
        column(act) {
            main = big(
                button("Stop") { _ ->
                    RecService.live?.pause()
                    show("home")
                },
            ).apply {
                background = box(RED)
                setTextColor(Color.WHITE)
            }
        }
    } else {
        column(act) {
            big(
                button("Resume") {
                    RecService.live?.resume()
                    show("home")
                },
            )
        }
        column(act) { main = big(primary(button("Save") { save(it as Button) })) }
        quiet(button("Discard") { discard(it as Button) })
    }
    var seen = -1L
    tick.post(
        object : Runnable {
            override fun run() {
                val r = RecService.live ?: return
                if (liveLine == null) return
                val s = r.activeMs() / 1000
                val tm = String.format(Locale.UK, "%d:%02d", s / 60, s % 60)
                val c = r.charts
                if (stopped) {
                    fv[0].text = tm
                    fv[1].text = String.format(Locale.UK, "%.1f", r.km * .6214)
                    fv[2].text = if (r.topMph > 0) "${Math.round(r.topMph)}" else DASH
                    fv[3].text = c.avg?.let { "${Dsp.peak(it, 15, 200)}" } ?: if (c.now != null) "${c.peak}" else DASH
                } else {
                    title.text = "●  $tm"
                    fv[0].text = if (r.mph.isNaN()) DASH else "${Math.round(r.mph)}"
                    if (main?.isEnabled == true) {
                        val got = if (r.fixes == 0L) {
                            "Waiting for GPS. Vibration is recording."
                        } else {
                            String.format(Locale.UK, "%,d readings, %,d GPS fixes", r.samples, r.fixes)
                        }
                        line.text = got + (r.error?.let { "\n$it" } ?: "")
                    }
                }
                if (c.version != seen) {
                    seen = c.version
                    if (!stopped && c.now != null) fv[1].text = "${c.peak}"
                    ln.a = if (stopped && c.avg != null) null else c.now
                    ln.b = c.avg
                    if (stopped && ln.b == null) ln.a = c.now
                    pick()
                    ln.invalidate()
                    hm.invalidate()
                }
                if (!stopped) tick.postDelayed(this, 1000)
            }
        },
    )
}

private fun MainActivity.big(b: Button): Button {
    b.minHeight = dp(64)
    b.gravity = Gravity.CENTER
    return b
}

/** Finish the drive, then ask how it felt if it was long enough to count. */
private fun MainActivity.save(b: Button) {
    working(b, "Saving...")
    liveLine?.text = "Saving..."
    tick.removeCallbacksAndMessages(null)
    val id = RecService.live?.meta?.optString("id").orEmpty()
    wait.postDelayed({ RecService.endNow(false) }, 40)
    until(10000, { RecService.live == null }, {
        val m = findDrive(id)
        if (m != null && m.optLong("end") - m.optLong("start") >= 30000) rate(m) { show("home") } else show("home")
    }, {
        show("home")
        toast("Still saving. Check History in a moment.", true)
    })
}

private fun MainActivity.discard(b: Button) {
    AlertDialog.Builder(this).setMessage("Discard this recording? It's deleted from the phone and can't be recovered.")
        .setPositiveButton("Discard") { _, _ ->
            if (RecService.live == null) {
                show("home")
                return@setPositiveButton
            }
            working(b, "Discarding...")
            liveLine?.text = "Discarding..."
            tick.removeCallbacksAndMessages(null)
            RecService.endNow(true)
            until(10000, { RecService.live == null }, {
                show("home")
                toast("Recording discarded")
            }, {
                show("home")
                toast("Still stopping. Check History in a moment.", true)
            })
        }.setNegativeButton("Keep recording", null).show()
}
