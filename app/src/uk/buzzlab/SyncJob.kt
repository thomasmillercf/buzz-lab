package uk.buzzlab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Sends new drives to the server by itself: after a recording, when the app opens, and again later if it failed. */
class SyncJob : JobService() {
    @Volatile private var stop = false

    override fun onStartJob(p: JobParameters): Boolean {
        stop = false
        if (Build.VERSION.SDK_INT >= 34 && p.isUserInitiatedJob) {
            try {
                setNotification(p, NP, progressNotification(this, -1), JOB_END_NOTIFICATION_POLICY_REMOVE)
            } catch (e: Exception) {
                log(this, "Notification: ${why(e)}")
            }
        }
        Thread {
            val again = try {
                !run(this) { stop }
            } catch (e: Throwable) {
                Log.e("BUZZLAB", "SYNC", e)
                true
            }
            jobFinished(p, again && !stop)
        }.start()
        return true
    }

    override fun onStopJob(p: JobParameters): Boolean {
        stop = true
        return true
    }

    fun interface Stop {
        fun now(): Boolean
    }

    companion object {
        const val ID = 77
        const val USER = 78
        private const val NP = 2
        private const val NR = 3
        private val LOCK = Any()
        private var lastNotice = 0L

        /** Live progress, empty when idle. */
        @Volatile @JvmField
        var now = ""

        @JvmStatic fun ui(c: Context): SharedPreferences = c.getSharedPreferences("ui", 0)

        @JvmStatic fun ready(c: Context) = ui(c).getString("url", "").orEmpty().isNotEmpty()

        @JvmStatic fun pending(m: JSONObject, url: String?) = !m.optBoolean("recording") && m.optString("syncedPos") != url && !m.has("syncErr")

        @JvmStatic fun waiting(c: Context): Int {
            val url = ui(c).getString("url", "")
            return Store(c).list().count { pending(it, url) }
        }

        /** Ask Android to run the sync once there's a connection. Safe to call often. */
        @JvmStatic @JvmOverloads
        fun schedule(c: Context, wait: Long = 0) {
            if (!ready(c) || waiting(c) == 0) return
            val js = c.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(ID) != null) return
            val network = if (ui(c).getBoolean("wifi", false)) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
            js.schedule(
                JobInfo.Builder(ID, ComponentName(c, SyncJob::class.java)).setRequiredNetworkType(network)
                    .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).setMinimumLatency(wait).setPersisted(true).build(),
            )
        }

        /** "Sync now": Android keeps this going after the app is left. False if it couldn't be started. */
        @JvmStatic fun userNow(c: Context): Boolean {
            if (Build.VERSION.SDK_INT < 34) return false
            return try {
                val js = c.getSystemService(JobScheduler::class.java)
                js.cancel(ID)
                val url = ui(c).getString("url", "")
                val st = Store(c)
                val bytes = st.list().filter { pending(it, url) }.sumOf { File(st.driveDir(it.optString("id")), "acc.bin").length() }
                val network = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
                js.schedule(
                    JobInfo.Builder(USER, ComponentName(c, SyncJob::class.java)).setUserInitiated(true).setRequiredNetwork(network)
                        .setEstimatedNetworkBytes(JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), max(1L, bytes)).build(),
                ) == JobScheduler.RESULT_SUCCESS
            } catch (e: Exception) {
                log(c, "Background upload not available: ${why(e)}")
                false
            }
        }

        // ---------- notifications

        private fun builder(c: Context, title: String, text: String): Notification.Builder {
            c.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("sync", "Uploads", NotificationManager.IMPORTANCE_LOW))
            val open = Intent(c, MainActivity::class.java).putExtra("tab", "sync").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return Notification.Builder(c, "sync").setSmallIcon(android.R.drawable.stat_sys_upload).setColor(0xFFFF803A.toInt())
                .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(c, 9, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }

        private fun progressNotification(c: Context, pct: Int) =
            builder(c, "Uploading drives", now.ifEmpty { "Starting" }).setOngoing(true).setProgress(100, max(0, pct), pct < 0).build()

        /** pct below 0 means not known yet. At most two updates a second. */
        private fun progress(c: Context, s: String, pct: Int) {
            now = s
            val t = SystemClock.elapsedRealtime()
            if (pct in 0..99 && t - lastNotice < 500) return
            lastNotice = t
            runCatching { c.getSystemService(NotificationManager::class.java).notify(NP, progressNotification(c, pct)) }
        }

        private fun result(c: Context, title: String, text: String) {
            runCatching {
                c.getSystemService(NotificationManager::class.java).notify(
                    NR,
                    builder(c, title, text).setSmallIcon(android.R.drawable.stat_sys_upload_done).setAutoCancel(true).setTimeoutAfter(6 * 3600 * 1000L).build(),
                )
            }
        }

        private fun done(c: Context) {
            now = ""
            runCatching { c.getSystemService(NotificationManager::class.java).cancel(NP) }
        }

        // ---------- status and log

        private fun note(c: Context, s: String) {
            ui(c).edit().putString("syncMsg", s).putLong("syncAt", System.currentTimeMillis()).commit()
            log(c, s)
        }

        /** Newest first, last 40 lines. */
        @JvmStatic @Synchronized
        fun log(c: Context, s: String) {
            val stamp = SimpleDateFormat("d MMM HH:mm:ss", Locale.UK).format(Date())
            val lines = ("$stamp  $s\n" + ui(c).getString("syncLog", "")).split("\n").take(40)
            ui(c).edit().putString("syncLog", lines.joinToString("\n", postfix = "\n")).commit()
            Log.i("BUZZLAB", "SYNC $s")
        }

        @JvmStatic fun why(x: Throwable): String {
            var s = x.javaClass.simpleName + (x.message?.let { ": $it" } ?: "")
            val cause = x.cause
            if (cause != null && cause !== x) s += " / " + cause.javaClass.simpleName + (cause.message?.let { ": $it" } ?: "")
            return s.take(220)
        }

        /** The phone's own key once signed in with Google, else the typed user name and password. */
        @JvmStatic fun auth(ui: SharedPreferences): String = ui.getString("tok", "").orEmpty().takeIf { it.isNotEmpty() }?.let { "Bearer $it" }
            ?: "Basic " + Base64.encodeToString("${ui.getString("su", "")}:${ui.getString("sp", "")}".toByteArray(), Base64.NO_WRAP)

        /** Each step on its own line, so a failure shows where it happened. */
        @JvmStatic fun test(c: Context): String = buildString {
            val ui = ui(c)
            val url = ui.getString("url", "").orEmpty()
            val cm = c.getSystemService(ConnectivityManager::class.java)
            val net = cm.getNetworkCapabilities(cm.activeNetwork)
            val kinds = if (net == null) {
                "NONE"
            } else {
                listOf(
                    NetworkCapabilities.TRANSPORT_VPN to "VPN",
                    NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
                    NetworkCapabilities.TRANSPORT_CELLULAR to "mobile data",
                )
                    .filter { net.hasTransport(it.first) }.joinToString(" ") { it.second }
            }
            append("Network for this app: $kinds\n")
            val saver = cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
            append("Data Saver limits this app: ${if (saver) "YES" else "no"}\n")
            if (url.isEmpty()) return@buildString append("No server set up.").let { }
            val loginSaved = ui.getString("tok", "").orEmpty().isNotEmpty() ||
                (ui.getString("su", "").orEmpty().isNotEmpty() && ui.getString("sp", "").orEmpty().isNotEmpty())
            append("Server: $url\nLogin saved: ${if (loginSaved) "yes" else "NO"}\n")
            try {
                append("Address found: ${InetAddress.getByName(URL(url).host).hostAddress}\n")
            } catch (e: Exception) {
                return@buildString append("Address lookup FAILED: ${why(e)}").let { }
            }
            for ((path, what) in listOf("/healthz" to "Server answers", "/api/cars" to "Login accepted")) {
                try {
                    val h = URL(url + path).openConnection() as HttpURLConnection
                    h.connectTimeout = 10000
                    h.readTimeout = 15000
                    h.instanceFollowRedirects = false
                    if (path.startsWith("/api")) h.setRequestProperty("Authorization", auth(ui))
                    val code = h.responseCode
                    h.disconnect()
                    val hint = when (code) {
                        401 -> " (wrong user name or password)"
                        429 -> " (too many wrong passwords, wait 10 minutes)"
                        else -> ""
                    }
                    append("$what: ${if (code == 200) "yes" else "NO, code $code$hint"}\n")
                    if (code != 200) return@buildString
                } catch (e: Exception) {
                    return@buildString append("$what: FAILED, ${why(e)}").let { }
                }
            }
            append("Everything works.")
        }

        // ---------- the upload

        /** True when finished, or when retrying can't help; false to try again later. */
        @JvmStatic fun run(c: Context, stop: Stop): Boolean = synchronized(LOCK) {
            val st = Store(c)
            val ui = ui(c)
            val url = ui.getString("url", "").orEmpty()
            if (url.isEmpty()) return true
            var ok = 0
            var bad = 0
            var at = 0
            val total = waiting(c)
            if (total > 0) log(c, "Started, $total waiting")
            val zip = File(c.cacheDir, "sync.zip")
            try {
                for (m in st.list()) {
                    if (stop.now()) {
                        note(c, "Paused, ${waiting(c)} waiting")
                        return false
                    }
                    if (!pending(m, url)) continue
                    at++
                    progress(c, "Preparing $at of $total", -1)
                    try {
                        FileOutputStream(zip).use { Io.export(st, listOf(m), it, true) }
                    } catch (e: Exception) {
                        flag(st, m, "syncErr", "Couldn't read this drive: ${e.message}")
                        bad++
                        continue
                    }
                    val label = "Sending $at of $total"
                    progress(c, label, 0)
                    when (val code = post(c, "$url/api/upload", ui, zip, label)) {
                        200 -> {
                            flag(st, m, "syncedPos", url)
                            ok++
                            log(c, "Sent drive $at of $total")
                        }
                        401, 403 -> {
                            note(c, "Wrong user name or password")
                            result(c, "Upload stopped", "Wrong user name or password")
                            return true
                        }
                        400, 413 -> {
                            flag(st, m, "syncErr", "Server refused this drive ($code)")
                            bad++
                        }
                        else -> {
                            note(c, "Server said $code, will try again")
                            result(c, "Upload paused", "Server said $code. It will try again.")
                            return false
                        }
                    }
                }
                note(
                    c,
                    when {
                        bad > 0 -> "Synced $ok, $bad had a problem"
                        ok > 0 -> "Synced $ok ${if (ok == 1) "drive" else "drives"}"
                        else -> "Everything is synced"
                    },
                )
                if (ok > 0 || bad > 0) result(c, if (bad > 0) "Some drives had a problem" else "Upload finished", ui.getString("syncMsg", "").orEmpty())
                true
            } catch (e: Exception) {
                note(c, "No connection, will try again. ${why(e)}")
                result(c, "Upload paused", "No connection. It will try again.")
                false
            } finally {
                zip.delete()
                done(c)
            }
        }

        /** Re-reads the drive's details before changing them, so nothing else written meanwhile is lost. */
        private fun flag(st: Store, m: JSONObject, key: String, value: String) {
            try {
                val dir = st.driveDir(m.getString("id"))
                if (!dir.exists()) return
                st.saveMeta(JSONObject(Store.read(File(dir, "meta.json")).orEmpty()).put(key, value))
            } catch (e: Exception) {
                Log.w("BUZZLAB", "flag $e")
            }
        }

        private fun post(c: Context, url: String, ui: SharedPreferences, f: File, label: String): Int {
            val h = URL(url).openConnection() as HttpURLConnection
            try {
                h.connectTimeout = 15000
                h.readTimeout = 120000
                h.instanceFollowRedirects = false
                h.requestMethod = "POST"
                h.doOutput = true
                h.setFixedLengthStreamingMode(f.length())
                h.setRequestProperty("Content-Type", "application/zip")
                h.setRequestProperty("Authorization", auth(ui))
                val size = max(1L, f.length())
                h.outputStream.use { out ->
                    FileInputStream(f).use { input ->
                        val buf = ByteArray(65536)
                        var sent = 0L
                        while (true) {
                            val k = input.read(buf)
                            if (k <= 0) break
                            out.write(buf, 0, k)
                            sent += k
                            val pct = (100 * sent / size).toInt()
                            progress(c, "$label, $pct% of ${String.format(Locale.UK, "%.1f MB", size / 1e6)}", min(99, pct))
                        }
                    }
                }
                progress(c, "$label, waiting for the server", -1)
                return h.responseCode
            } finally {
                h.disconnect()
            }
        }
    }
}
