package uk.buzzlab

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import uk.buzzlab.MainActivity.Companion.AC
import uk.buzzlab.MainActivity.Companion.CARD
import uk.buzzlab.MainActivity.Companion.FG
import uk.buzzlab.MainActivity.Companion.NAV
import uk.buzzlab.MainActivity.Companion.RED
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min

/** One speed range of a car or drive: its main buzz at 20 to 50 Hz and how strong it was. */
class Band(val lo: Int, val hi: Int, val rate: Int, val level: Double, val secs: Int) {
    val label get() = "$lo to $hi mph"
}

/** The buzz in each 10 mph range with at least 6 s of steady driving, slowest first. */
fun bands(d: Dsp): List<Band> = (10 until 80 step 10).mapNotNull { lo ->
    val n = d.count(lo, lo + 10)
    val p = d.mean(lo, lo + 10)
    if (n < 3 || p == null) null else Band(lo, lo + 10, Dsp.peak(p, 20, 50), Dsp.rms(p, 20, 51), n * 2)
}

/**
 * Plays what was measured through the phone's haptic motor, at the strength calibration found.
 * The source is what Analysis shows (its car, comparison and filters) or one drive, chosen from that drive's page.
 */
fun MainActivity.feel() {
    val calibrated = sp.contains(Scenes.ALL[0].key)
    overflow(
        linkedMapOf(
            (if (calibrated) "Calibrate again" else "Calibrate") to { askCalibrate() },
            "Calibration details" to {
                AlertDialog.Builder(this).setMessage(sp.getString("report", "No calibration saved.")).setPositiveButton("Close", null).show()
            },
            (if (ui.getBoolean("spk", false)) "Speaker flutter: on" else "Speaker flutter: off") to {
                ui.edit().putBoolean("spk", !ui.getBoolean("spk", false)).apply()
                toast(if (ui.getBoolean("spk", false)) "Speaker flutter on" else "Speaker flutter off")
                show("feel")
            },
        ),
    )
    if (!AudioManager.isHapticPlaybackSupported()) {
        top("Feel")
        text("This phone can't play haptic audio, so Feel can't work on it.", 16)
        return
    }
    if (!calibrated) {
        top("Feel")
        heading("Calibrate first")
        note(
            "Feel plays the vibration your drives recorded through the phone's motor. First it measures how strongly this phone can buzz, " +
                "using its own accelerometer. Put the phone where you'll hold it and leave it still.",
            15,
        )
        status = text("", 15)
        primary(button("Calibrate", "About 5 minutes") { calibrate() })
        controls()
        return
    }
    val at = sp.getLong("at", 0)
    note((if (at > 0) "Calibrated " + date(at, "d MMM") else "Calibrated, older version") + ". Hold the phone the way it was calibrated.", 13)
    val one = pref("fdrive").takeIf { it.isNotEmpty() }?.let { findDrive(it) }
    if (one != null) feelDrive(one) else feelCars()
    status = text("Tap something to feel it.", 15)
    heading("For comparison")
    for (s in Scenes.ALL) {
        button(s.name, String.format(Locale.UK, "%d a second, %.2f m/s²", s.rate, s.level)) {
            buzz(s.name, s.key, s.curve, s.rate, s.level, it as Button)
        }
    }
    for ((k, name) in Scenes.CLIPS.filterKeys { clips.has(it) }) button("Recording: $name", "10 real seconds, as recorded") { playClip(k, it as Button) }
    controls()
}

/** The car and comparison from Analysis, with its filters. */
private fun MainActivity.feelCars() {
    val cs = cars()
    if (cs.isEmpty()) {
        top("Feel")
        heading("Your drives")
        note("Add a car on Home and record a drive, then feel it here.", 15)
        return
    }
    val c = cs[idx(cs, pref("acar"))]
    val k = minOf(ui.getInt("cmp", 0), cs.size)
    val o = if (k == 0 || cs[k - 1].optString("id") == c.optString("id")) null else cs[k - 1]
    top(c.optString("name") + (o?.let { " and " + it.optString("name") } ?: ""))
    val nm = cs.map { it.optString("name") }
    val pair = row()
    column(pair) {
        note("Car", 13)
        spinner(nm, idx(cs, pref("acar"))) {
            ui.edit().putString("acar", cs[it].optString("id")).apply()
            show("feel")
        }
    }
    column(pair) {
        note("Compare with", 13)
        spinner(listOf("No comparison") + nm, k) {
            ui.edit().putInt("cmp", it).apply()
            show("feel")
        }
    }
    val ap = ui.getInt("ap", 0)
    val where = if (ap == 0) "any phone position" else MainActivity.PLACES[ap - 1]
    note("${MainActivity.DATES[ui.getInt("dr", 0)]}, $where. Change these on Analysis.", 13)
    heading("By speed")
    val w = note("Working...", 15)
    thread {
        try {
            val n = IntArray(2)
            val a = bands(sum(c, n, 0))
            val b = o?.let { bands(sum(it, n, 1)) }
            runOnUiThread { if (w.parent != null) speedRows(w, n, c.optString("name"), a, o?.optString("name"), b) }
        } catch (x: Throwable) {
            Log.e("BUZZLAB", "feel", x)
            runOnUiThread { w.text = "Couldn't read the drives: ${x.message}" }
        }
    }
}

/** One drive, opened from its page. */
private fun MainActivity.feelDrive(m: JSONObject) {
    val name = carName(m.optString("car"))
    top("$name, " + date(m.optLong("start"), "d MMM HH:mm"))
    note("One drive" + m.optString("place").let { if (it.isEmpty()) "" else ", $it" } + ".", 15)
    quiet(
        button("Feel all $name drives instead") {
            ui.edit().remove("fdrive").putString("acar", m.optString("car")).apply()
            show("feel")
        },
    )
    heading("By speed")
    val w = note("Working...", 15)
    thread {
        try {
            val a = bands(Dsp.load(st.driveDir(m.optString("id"))))
            runOnUiThread { if (w.parent != null) speedRows(w, intArrayOf(1, 0), name, a, null, null) }
        } catch (x: Throwable) {
            Log.e("BUZZLAB", "feel", x)
            runOnUiThread { w.text = "Couldn't read this drive: ${x.message}" }
        }
    }
}

/** A button per speed, or a pair of them side by side when comparing. Goes where the Working note was. */
private fun MainActivity.speedRows(w: TextView, n: IntArray, name: String, a: List<Band>, otherName: String?, b: List<Band>?) {
    val save = list
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    save.addView(box, save.indexOfChild(w) + 1, LinearLayout.LayoutParams(-1, -2))
    save.removeView(w)
    list = box
    try {
        if (a.isEmpty() && b.isNullOrEmpty()) {
            note(
                if (n[0] + n[1] ==
                    0
                ) {
                    "No drives match. Record one, or widen the filters on Analysis."
                } else {
                    "Not enough steady driving yet. Each speed needs 6 seconds."
                },
                15,
            )
            return
        }
        val strongest = a.maxByOrNull { it.level }
        note("Tap a speed to feel its main buzz between 20 and 50 Hz, at the strength measured in the car.", 14)
        if (b == null) {
            for (x in a) {
                val tag = if (x === strongest && a.size > 1) ", strongest" else ""
                button(x.label, describe(x) + tag) { buzz("$name, ${x.label}", null, null, x.rate, x.level, it as Button) }
            }
            return
        }
        for (lo in (a.map { it.lo } + b.map { it.lo }).distinct().sorted()) {
            val x = a.firstOrNull { it.lo == lo }
            val y = b.firstOrNull { it.lo == lo }
            text("$lo to ${lo + 10} mph", 15, true)
            val r = row()
            for ((who, v) in listOf(name to x, otherName.orEmpty() to y)) {
                column(r) {
                    if (v == null) {
                        quiet(button(who, "No steady driving"))
                    } else {
                        button(who, String.format(Locale.UK, "%d a second, %.2f m/s²", v.rate, v.level)) {
                            buzz("$who, ${v.label}", null, null, v.rate, v.level, it as Button)
                        }
                    }
                }
            }
        }
        note("Different roads and phone positions change these. Compare the same route and position for a fair feel.")
    } finally {
        list = save
    }
}

private fun describe(x: Band): String {
    val t = if (x.secs >= 120) "${x.secs / 60} min" else "${x.secs} s"
    return String.format(Locale.UK, "%d a second, %.2f m/s², from %s", x.rate, x.level, t)
}

/** Intensity, speaker flutter and Stop, pinned under the page so they stay in reach. */
private fun MainActivity.controls() {
    act.removeAllViews()
    act.visibility = View.VISIBLE
    act.gravity = Gravity.CENTER_VERTICAL
    val iv = TextView(this).apply {
        text = "${ui.getInt("pct", 100)}%"
        textSize = 15f
        setTextColor(FG)
        minWidth = dp(48)
    }
    val tint = ColorStateList.valueOf(AC)
    val sb = SeekBar(this).apply {
        progressTintList = tint
        thumbTintList = tint
        max = 95
        progress = ui.getInt("pct", 100) - 5
        contentDescription = "Intensity. 100% is the strongest this motor reached in calibration."
        setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(x: SeekBar, i: Int, user: Boolean) {
                    iv.text = "${i + 5}%"
                }

                override fun onStartTrackingTouch(x: SeekBar) {}

                override fun onStopTrackingTouch(x: SeekBar) {
                    ui.edit().putInt("pct", x.progress + 5).apply()
                    replay()
                }
            },
        )
    }
    val stop = Button(this).apply {
        isAllCaps = false
        text = "Stop"
        textSize = 16f
        background = box(RED)
        setTextColor(Color.WHITE)
        setOnClickListener {
            tap(it)
            stopPlaying()
            say("Stopped.")
        }
    }
    act.addView(iv, LinearLayout.LayoutParams(-2, -2))
    act.addView(sb, LinearLayout.LayoutParams(0, -2, 1f))
    act.addView(stop, LinearLayout.LayoutParams(dp(96), dp(52)))
}

fun MainActivity.askCalibrate() {
    AlertDialog.Builder(this).setMessage("Put the phone where you'll use it and leave it still for about 5 minutes.")
        .setPositiveButton("Start") { _, _ -> calibrate() }.setNegativeButton("Cancel", null).show()
}

fun MainActivity.stopPlaying() {
    playing?.let {
        runCatching { it.stop() }
        it.release()
    }
    playing = null
    lit?.let {
        it.background = box(CARD)
        it.setTextColor(FG)
    }
    lit = null
    cancel = true
}

private fun MainActivity.startPlaying(pcm: ShortArray, b: Button, loopFrames: Int) {
    stopPlaying()
    playing = engine.track(pcm, MainActivity.USAGE).apply {
        setLoopPoints(0, loopFrames, -1)
        play()
    }
    lit = b
    b.background = box(AC)
    b.setTextColor(NAV)
}

/** Saved settings for one scene, or null before calibration. */
private fun MainActivity.saved(k: String): Cal.Best? = try {
    val o = JSONObject(sp.getString(k, "").orEmpty())
    Cal.Best().apply {
        rate = o.getDouble("rate")
        carrier = o.getDouble("carrier")
        duty = o.getDouble("duty")
        brake = o.getDouble("brake")
        bg = o.getDouble("bg")
        gain = o.getDouble("gain")
    }
} catch (_: Exception) {
    null
}

private fun MainActivity.pct() = ui.getInt("pct", 100) / 100.0

private fun MainActivity.curve(k: String): JSONArray? = runCatching { JSONArray(sp.getString(k, "")) }.getOrNull()

private fun MainActivity.flutter() = if (ui.getBoolean("spk", false)) .25 * pct() else 0.0

/**
 * Loops a buzz at rate pulses a second. level is the strength in the car, in m/s²; 0.70 plays as the strongest the phone reached,
 * since the motor can't get near what the car does. key is a saved scene, or null to borrow the nearest tuned one.
 */
private fun MainActivity.buzz(label: String, key: String?, curveKey: String?, rate: Int, level: Double, b: Button) {
    if (busy) {
        say("Calibrating. Tap Stop first.")
        return
    }
    val near = Scenes.ALL.take(2).minByOrNull { abs(it.rate - rate) } ?: Scenes.ALL[0]
    val c = saved(key ?: near.key)
    if (c == null) {
        say("Calibrate first.")
        return
    }
    c.rate = rate.toDouble()
    val share = level / REF
    val cv = curve(curveKey ?: near.curve)
    if (cv == null) {
        // a saved scene's gain already allows for its level
        c.gain *= pct() * if (key == null) min(1.0, share) else 1.0
    } else {
        c.gain = Cal.gainFor(cv, pct() * Cal.top(cv) * min(1.0, share))
    }
    val q = c.spec(1.0)
    q.spk = flutter()
    startPlaying(Engine.renderLoop(q), b, Engine.SR)
    replayLast = { buzz(label, key, curveKey, rate, level, b) }
    val capped = if (share > 1) ". Stronger than the phone can play, so at full" else ""
    say(String.format(Locale.UK, "%s: %d a second, %.2f m/s² in the car%s.", label, rate, level, capped))
}

private fun MainActivity.playClip(k: String, b: Button) {
    if (busy) return
    val c = saved(Scenes.ALL[0].key)
    if (c == null) {
        say("Calibrate first.")
        return
    }
    val cv = curve("curve_a")
    try {
        val gain = if (cv == null) c.gain * pct() else Cal.gainFor(cv, pct() * Cal.top(cv))
        val pcm = Engine.renderClip(clips.getJSONObject(k), c.carrier, gain, flutter())
        startPlaying(pcm, b, pcm.size / 3)
        replayLast = { playClip(k, b) }
        say("Recording playing: ${Scenes.CLIPS[k] ?: k}")
    } catch (x: Exception) {
        say("Could not play: $x")
    }
}

/** After the slider moves, play the same thing again at the new strength. */
private fun MainActivity.replay() {
    if (playing != null) replayLast?.invoke()
}

/** Tunes the pulses against the phone's own accelerometer, then saves each scene's settings and a report. About 5 minutes. */
fun MainActivity.calibrate() {
    if (busy) return
    stopPlaying()
    busy = true
    cancel = false
    thread {
        var ok = false
        val t0 = System.currentTimeMillis()
        val c = Cal(
            engine,
            MainActivity.USAGE,
            object : Cal.Log {
                override fun line(s: String) {
                    Log.i("BUZZLAB", "CAL $s")
                    say(s)
                }

                override fun cancelled() = cancel
            },
        )
        try {
            val scenes = Scenes.ALL
            val a = c.tune(scenes[0].rate.toDouble())
            val w = c.tune(scenes[1].rate.toDouble())
            val ca = c.curve(a)
            val cw = c.curve(w)
            val top = Cal.top(ca)
            val r = LinkedHashMap<String, Cal.Best>()
            r[scenes[0].key] = a
            r[scenes[1].key] = w
            for (s in scenes.drop(2)) {
                r[s.key] = Cal.copy(a).apply {
                    rate = s.rate.toDouble()
                    if (s.level != .70) gain = Cal.gainFor(ca, top * s.level / .70)
                }
            }
            val out = StringBuilder("asked  got    level   depth\n")
            val ed = sp.edit()
            for ((key, b) in r) {
                val m = c.test(b.spec(2.5))
                val o = JSONObject().put("rate", b.rate).put("carrier", b.carrier).put("duty", b.duty).put("brake", b.brake).put("bg", b.bg).put("gain", b.gain)
                    .put("got", m.peakHz).put("rms", m.rms).put("depth", m.depth).put("flutter", m.flutter)
                ed.putString(key, o.toString())
                out.append(String.format(Locale.UK, "%3.0f/s  %5.2f  %.3f   %.2f  %s\n", b.rate, m.peakHz, m.rms, m.depth, key))
                Log.i("BUZZLAB", "CAL FINAL $key $o")
            }
            out.append(
                String.format(
                    Locale.UK,
                    "\nTarget level 0.70 m/s2, phone about %.2f.\nBuzz is %d Hz pulsed, not a true %d Hz.\n%s pulses were tuned, not matched in strength.\n%d tests, %d s.",
                    top,
                    a.carrier.toInt(),
                    scenes[0].rate,
                    scenes[0].name,
                    c.runs,
                    (System.currentTimeMillis() - t0) / 1000,
                ),
            )
            ed.putLong("at", System.currentTimeMillis()).putString("curve_a", ca.toString()).putString("curve_w", cw.toString())
            ed.putString("report", out.toString())
            ed.commit()
            say("Calibrated.")
            ok = true
            Log.i("BUZZLAB", "DONE cal")
        } catch (_: InterruptedException) {
            say("Calibration stopped. Nothing saved.")
            Log.i("BUZZLAB", "DONE cancelled")
        } catch (x: Throwable) {
            say("Calibration failed: $x")
            Log.e("BUZZLAB", "FAIL", x)
        } finally {
            busy = false
            if (ok) runOnUiThread { if (tab == "feel" && !inSub) show("feel") }
        }
    }
}

/** Car strength that plays as the strongest the phone reached: the reference console buzz. */
private const val REF = .70
