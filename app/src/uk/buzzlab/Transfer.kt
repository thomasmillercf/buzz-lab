package uk.buzzlab

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import kotlin.concurrent.thread

// Export and import through the system file picker, and the developer hooks used over adb.

/** Positions are optional: they show routes and home. */
fun MainActivity.export(ds: List<JSONObject>) {
    expIds = ds.joinToString(",") { it.optString("id") }
    AlertDialog.Builder(this).setTitle("Include where you drove?")
        .setMessage("Positions show your routes and home. Speed is always included, and the stats don't need positions.")
        .setPositiveButton("Leave out") { _, _ -> saveAs(false, "buzzlab-") }
        .setNegativeButton("Include") { _, _ -> saveAs(true, "buzzlab-") }.show()
}

fun MainActivity.fullExport() {
    val msg = "Everything on this phone: all drives with positions and marks, cars with plates, calibration and the sync log. " +
        "The server password is left out. Keep the file private."
    AlertDialog.Builder(this).setTitle("Full export").setMessage(msg).setPositiveButton("Export") { _, _ ->
        expIds = ""
        saveAs(true, "buzzlab-full-", full = true)
    }.setNegativeButton("Cancel", null).show()
}

/** Ask where to save; transferDone() writes the file. */
private fun MainActivity.saveAs(positions: Boolean, prefix: String, full: Boolean = false) {
    expFull = full
    expPos = positions
    val name = prefix + date(System.currentTimeMillis(), "yyyy-MM-dd-HHmm") + ".zip"
    @Suppress("DEPRECATION")
    startActivityForResult(
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE, name),
        MainActivity.PICK_EXPORT,
    )
}

/** Calibration, app details and the sync log, for the full export. Never the password. */
private fun MainActivity.extras(): Map<String, String> {
    val x = LinkedHashMap<String, String>()
    try {
        val c = JSONObject()
        for ((k, v) in sp.all) c.put(k, v)
        x["calibration.json"] = c.toString(1)
        x["app.json"] = JSONObject().put("version", version()).put("server", url()).put("exported", System.currentTimeMillis()).toString(1)
        x["sync-log.txt"] = pref("syncLog")
    } catch (e: Exception) {
        Log.w("BUZZLAB", "extras $e")
    }
    return x
}

/** A file was chosen: export to it or import from it. */
fun MainActivity.transferDone(q: Int, u: Uri) {
    toast(if (q == MainActivity.PICK_EXPORT) "Exporting..." else "Importing...")
    thread {
        try {
            if (q == MainActivity.PICK_EXPORT) {
                val out = contentResolver.openOutputStream(u) ?: error("Couldn't open the file")
                if (expFull) {
                    val l = st.list().filter { !it.optBoolean("recording") && File(st.driveDir(it.optString("id")), "acc.bin").length() > 0 }
                    Io.export(st, l, out, true, extras())
                    say("Full export saved, ${l.size} ${if (l.size == 1) "drive." else "drives."}")
                } else {
                    val ids = expIds.split(",").toSet()
                    val l = st.list().filter { it.optString("id") in ids }
                    Io.export(st, l, out, expPos)
                    say("Exported ${l.size} ${if (l.size == 1) "drive." else "drives."}")
                }
            } else {
                val input = contentResolver.openInputStream(u) ?: error("Couldn't open the file")
                Io.importPhyphox(st, input, impCar, impPlace, "", impTyre)
                runOnUiThread { history() }
                say("Imported.")
            }
        } catch (x: Throwable) {
            Log.e("BUZZLAB", "io", x)
            say("That didn't work: ${x.message}")
        }
    }
}

// ---------- developer hooks, used over adb; they only touch this app's own files

fun MainActivity.go(i: Intent?) {
    if (i == null) return
    val back = i.data
    if (i.action == Intent.ACTION_VIEW && back?.scheme == "buzzlab" && back.host == "signin") {
        i.data = null // once only, even if the screen is rebuilt
        finishSignIn(back)
        return
    }
    val tab = i.getStringExtra("tab")
    if (tab == "sync") {
        if (RecService.live == null) {
            show("home")
            syncPage()
        }
        return
    }
    if (tab != null) show(tab)
    when {
        i.getBooleanExtra("cal", false) -> {
            show("feel")
            calibrate()
        }
        i.getStringExtra("imp") != null -> devImport(i)
        i.getBooleanExtra("exp", false) -> thread {
            try {
                FileOutputStream(File(filesDir, "exp.zip")).use { Io.export(st, st.list(), it, false) }
                Log.i("BUZZLAB", "EXP ok")
            } catch (x: Throwable) {
                Log.e("BUZZLAB", "EXP fail", x)
            }
        }
        i.getStringExtra("dump") != null -> devDump(i.getStringExtra("dump").orEmpty())
        i.getStringExtra("plan") != null -> testRig(i.getStringExtra("plan").orEmpty(), i.getIntExtra("usage", MainActivity.USAGE))
    }
}

private fun MainActivity.devImport(i: Intent) {
    thread {
        try {
            val f = File(filesDir, File(i.getStringExtra("imp").orEmpty()).name)
            FileInputStream(f).use {
                Io.importPhyphox(st, it, i.getStringExtra("car"), i.getStringExtra("place"), i.getStringExtra("note"), i.getStringExtra("tyre"))
            }
            f.delete()
            Log.i("BUZZLAB", "IMP ok")
            runOnUiThread { show("home") }
        } catch (x: Throwable) {
            Log.e("BUZZLAB", "IMP fail $x")
        }
    }
}

/** Prints a drive's key numbers to the log, for checking the analysis. */
private fun MainActivity.devDump(id: String) {
    thread {
        try {
            val t = System.currentTimeMillis()
            val d = Dsp.load(st.driveDir(File(id).name))
            val p = d.mean(25, 35) ?: error("no steady driving at 25 to 35 mph")
            Log.i(
                "BUZZLAB",
                String.format(
                    Locale.UK,
                    "DUMP %s ms=%d n=%d rms25-35=%.3f rms38-48=%.3f peak=%d fa=%.2f",
                    id,
                    System.currentTimeMillis() - t,
                    d.count(25, 35),
                    Dsp.rms(p, 25, 35),
                    Dsp.rms(p, 38, 48),
                    Dsp.peak(p, 15, 100),
                    d.foreAft(25, 35),
                ),
            )
        } catch (x: Throwable) {
            Log.e("BUZZLAB", "DUMP", x)
        }
    }
}

/** Plays each spec in a comma-separated plan and saves what the accelerometer felt to run.bin. */
private fun MainActivity.testRig(plan: String, usage: Int) {
    if (busy) return
    busy = true
    cancel = false
    thread {
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(File(filesDir, "run.bin")))).use { o ->
                val ps = plan.split(",")
                o.writeInt(ps.size)
                ps.forEachIndexed { k, p ->
                    say("${k + 1}/${ps.size} $p")
                    val r = engine.run(Engine.Spec.parse(p), usage)
                    synchronized(r) {
                        o.writeUTF(p)
                        o.writeInt(r.v.size)
                        for (j in r.v.indices) {
                            o.writeLong(r.t[j])
                            for (c in 0..2) o.writeFloat(r.v[j][c])
                        }
                    }
                    Thread.sleep(250)
                }
                o.flush()
                Log.i("BUZZLAB", "DONE ${ps.size}")
            }
            say("Test rig done.")
        } catch (x: Throwable) {
            Log.e("BUZZLAB", "FAIL", x)
            say("Failed: $x")
        } finally {
            busy = false
        }
    }
}
