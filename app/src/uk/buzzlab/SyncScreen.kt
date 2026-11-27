package uk.buzzlab

import android.Manifest
import android.app.job.JobScheduler
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.widget.Button
import android.widget.TextView
import uk.buzzlab.MainActivity.Companion.AC
import uk.buzzlab.MainActivity.Companion.FG
import kotlin.concurrent.thread

/** One line for Home and Settings. */
fun MainActivity.syncLine(): String {
    if (!SyncJob.ready(this)) return "○  Server not set up. Drives stay on this phone."
    if (SyncJob.now.isNotEmpty()) return "↻  ${SyncJob.now}"
    val n = SyncJob.waiting(this)
    if (n == 0) return "✓  All drives are on the server"
    val m = pref("syncMsg")
    return "•  $n ${if (n == 1) "drive" else "drives"} waiting" + if (m.isEmpty()) "" else "\n$m"
}

/** Status of every drive, a connection test and the log. Updates while open. */
fun MainActivity.syncPage(back: () -> Unit = { show("home") }) {
    syncBack = back
    page("Sync", back)
    onSync = true
    val t = text(syncLine(), 18, true)
    note("App version ${version()}")
    notificationsButton()
    if (SyncJob.ready(this)) button("Sync now") { sync() } else button("Set up server") { syncSetup() }
    val res = TextView(this).apply {
        setTextColor(FG)
        textSize = 15f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }
    if (SyncJob.ready(this)) {
        button("Test connection", "Checks each step and says which one fails") {
            val b = it as Button
            working(b, "Testing...")
            res.text = ""
            thread {
                val o = SyncJob.test(this)
                SyncJob.log(this, "Test: " + o.trim().replace("\n", " | "))
                runOnUiThread {
                    res.text = o
                    ready(b, "Test connection again")
                }
            }
        }
    }
    list.addView(res)
    heading("Drives")
    val ds = st.list()
    if (ds.isEmpty()) note("None yet.", 15)
    val serverIsSetUp = SyncJob.ready(this)
    for (m in ds) {
        val uploadFailed = serverIsSetUp && m.has("syncErr")
        val waitingToUpload = serverIsSetUp && SyncJob.pending(m, url())
        val (mark, status) = when {
            !serverIsSetUp -> "○  " to "  on this phone only"
            uploadFailed -> "✕  " to "\n     ${m.optString("syncErr")}"
            waitingToUpload -> "•  " to "  waiting"
            else -> "✓  " to "  on the server"
        }
        val drive = carName(m.optString("car")) + ", " + date(m.optLong("start"))
        val needsAttention = uploadFailed || waitingToUpload
        text(mark + drive + status, 15, col = if (needsAttention) AC else FG)
    }
    heading("Log")
    val lg = note(pref("syncLog", "Nothing yet."), 13)
    lg.setTextIsSelectable(true)
    button("Copy status and log", "To paste to Claude. Holds no password or positions.") {
        copy("sync", "Version ${version()}\n${syncLine()}\n${res.text}\n${pref("syncLog")}")
    }
    var last = ""
    tick.postDelayed(
        object : Runnable {
            override fun run() {
                if (sub == null || t.parent == null) return
                t.text = syncLine()
                val l = pref("syncLog")
                if (l != last) {
                    last = l
                    lg.text = l.ifEmpty { "Nothing yet." }
                }
                tick.postDelayed(this, 700)
            }
        },
        300,
    )
}

fun MainActivity.syncSetup() {
    page("Server") { settings() }
    val u = field("Address, starting https://", pref("url"))
    val n = field("User name", pref("su"))
    val w = field("Password", pref("sp"))
    w.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    val wifi = checkBox("Sync on Wi-Fi only", ui.getBoolean("wifi", false))
    status = text("", 15)
    primary(
        button("Save") {
            val a = u.text.toString().trim().trimEnd('/')
            if (!a.startsWith("https://")) {
                say("The address must start with https:// so the password isn't sent in the open.")
                return@button
            }
            ui.edit().putString("url", a).putString("su", n.text.toString().trim()).putString("sp", w.text.toString())
                .putBoolean("wifi", wifi.isChecked).commit()
            getSystemService(JobScheduler::class.java).cancel(SyncJob.ID)
            settings()
        },
    )
    quiet(button("Cancel") { settings() })
}

/** Retry drives that failed before, then upload in the background if Android allows, else here. */
fun MainActivity.sync() {
    for (m in st.list()) {
        if (m.has("syncErr")) {
            runCatching {
                m.remove("syncErr")
                st.saveMeta(m)
            }
        }
    }
    if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS) && !ui.getBoolean("askedN2", false)) {
        ui.edit().putBoolean("askedN2", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), MainActivity.ASK_SYNC)
    }
    if (SyncJob.waiting(this) == 0) {
        toast("Everything is already on the server.")
        return
    }
    if (SyncJob.userNow(this)) {
        toast("Uploading. You can leave the app.")
        return
    }
    toast("Uploading, keep the app open.")
    thread {
        if (!SyncJob.run(this) { false }) SyncJob.schedule(this)
        runOnUiThread {
            toast(pref("syncMsg"), true)
            if (tab == "home" && sub == null && RecService.live == null) {
                show("home")
            } else if (inSub && sub != null && onSync) {
                syncPage(syncBack)
            }
        }
    }
}
