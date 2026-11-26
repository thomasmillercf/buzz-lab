package uk.buzzlab

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import org.json.JSONArray
import org.json.JSONObject
import uk.buzzlab.MainActivity.Companion.AC
import uk.buzzlab.MainActivity.Companion.CARD
import uk.buzzlab.MainActivity.Companion.FG
import uk.buzzlab.MainActivity.Companion.NAV
import uk.buzzlab.MainActivity.Companion.RED
import java.util.Locale
import kotlin.concurrent.thread

/** Plays the measured buzz through the phone's haptic motor, at the strength calibration found. */
fun MainActivity.feel() {
    val first = Scenes.ALL[0].key
    top(
        when {
            !sp.contains(first) -> "Not calibrated"
            sp.contains("at") -> "Calibrated " + date(sp.getLong("at", 0), "d MMM")
            else -> "Calibrated, older version"
        },
    )
    overflow(
        linkedMapOf(
            "Calibrate" to { askCalibrate() },
            "Calibration details" to {
                AlertDialog.Builder(this).setMessage(sp.getString("report", "No calibration saved.")).setPositiveButton("Close", null).show()
            },
        ),
    )
    note("Hold the phone the way it was calibrated.", 15)
    status = text(if (AudioManager.isHapticPlaybackSupported()) "Ready." else "This phone can't play haptic audio.", 15)
    if (!sp.contains(first)) primary(button("Calibrate", "About 5 minutes, phone left still") { calibrate() })
    for (s in Scenes.ALL) button(s.name, "${s.rate} pulses a second") { play(s, it as Button) }
    val clipsHere = Scenes.CLIPS.filterKeys { clips.has(it) }
    if (clipsHere.isNotEmpty()) {
        text("From the recordings", 20, true)
        note("Real 10-second waveforms, turned into buzz pulses.", 15)
        for ((k, name) in clipsHere) button("Recording: $name") { playClip(k, it as Button) }
    }
    val iv = text("Intensity ${ui.getInt("pct", 100)}%", 16, true)
    val tint = ColorStateList.valueOf(AC)
    val sb = SeekBar(this).apply {
        progressTintList = tint
        thumbTintList = tint
        max = 95
        progress = ui.getInt("pct", 100) - 5
        setPadding(dp(20), dp(12), dp(20), dp(12))
        setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(x: SeekBar, i: Int, user: Boolean) {
                    iv.text = "Intensity ${i + 5}%"
                }

                override fun onStartTrackingTouch(x: SeekBar) {}

                override fun onStopTrackingTouch(x: SeekBar) {
                    ui.edit().putInt("pct", x.progress + 5).apply()
                    replay()
                }
            },
        )
    }
    list.addView(sb)
    note("100% is the strongest this motor reached in calibration.")
    spk = checkBox("Add speaker flutter").apply { textSize = 16f }
    button("Stop") { stopPlaying() }.apply {
        background = box(RED)
        setTextColor(Color.WHITE)
    }
    report = null
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

private fun MainActivity.flutter() = if (spk?.isChecked == true) .25 * pct() else 0.0

private fun MainActivity.play(s: Scene, b: Button) {
    if (busy) {
        say("Calibrating. Tap Stop first.")
        return
    }
    val c = saved(s.key)
    if (c == null) {
        say("Calibrate first.")
        return
    }
    val cv = curve(s.curve)
    var level = 0.0
    if (cv == null) {
        c.gain *= pct()
    } else {
        level = pct() * Cal.top(cv) * s.level / .70
        c.gain = Cal.gainFor(cv, level)
    }
    val q = c.spec(1.0)
    q.spk = flutter()
    startPlaying(Engine.renderLoop(q), b, Engine.SR)
    replayLast = { play(s, b) }
    say("${s.name}: ${s.rate} a second" + if (cv == null) "" else String.format(Locale.UK, ", about %.2f m/s²", level))
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
        say("Recording playing: $k")
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
            runOnUiThread { report?.text = out }
            say("Calibrated.")
            Log.i("BUZZLAB", "DONE cal")
        } catch (_: InterruptedException) {
            say("Calibration stopped. Nothing saved.")
            Log.i("BUZZLAB", "DONE cancelled")
        } catch (x: Throwable) {
            say("Calibration failed: $x")
            Log.e("BUZZLAB", "FAIL", x)
        } finally {
            busy = false
        }
    }
}
