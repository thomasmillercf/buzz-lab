package uk.buzzlab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records accelerometer at full rate and GPS speed, with the screen off too.
 * It can also watch: GPS only, starting a drive by itself once moving and saving it after a stop.
 * One service does both, because Android won't let an app start location work from the background.
 */
class RecService :
    Service(),
    SensorEventListener,
    LocationListener {
    // per drive
    @Volatile @JvmField
    var discard = false

    @Volatile @JvmField
    var paused = false

    @Volatile @JvmField
    var auto = false

    @Volatile @JvmField
    var km = 0.0

    @Volatile @JvmField
    var topMph = 0.0

    @Volatile @JvmField
    var charts = Live()

    @Volatile @JvmField
    var samples = 0L

    @Volatile @JvmField
    var fixes = 0L

    @Volatile @JvmField
    var startMs = 0L

    @Volatile @JvmField
    var error: String? = null

    @JvmField var meta: JSONObject? = null

    @Volatile private var pausedMs = 0L

    @Volatile private var pauseAt = 0L

    @Volatile private var closed = true
    private var lastFix = 0L
    private var pauses = 0
    private var acc: DataOutputStream? = null
    private var gps: PrintWriter? = null
    private var marks: PrintWriter? = null

    // service
    @Volatile @JvmField
    var mph = Double.NaN

    @Volatile @JvmField
    var watch = false
    private lateinit var st: Store
    private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private var th: HandlerThread? = null
    private var lt: HandlerThread? = null
    private var h: Handler? = null
    private var wl: PowerManager.WakeLock? = null
    private var fast = 0
    private var slowSince = 0L
    private var lastMoving = 0L
    private var interval = -1L
    private val carRule = CarRule()
    private var carWatch: BroadcastReceiver? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val a = if (i == null) WATCH else i.action ?: RECORD
        lastError = null
        try {
            startForeground(1, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            lastError = e.message.toString()
            Log.e("BUZZLAB", "REC fg", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (i == null && !getSharedPreferences("ui", 0).getBoolean("auto", false) && live == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (th == null) {
            st = Store(this)
            sm = getSystemService(SensorManager::class.java)
            lm = getSystemService(LocationManager::class.java)
            th = HandlerThread("rec").also { it.start() }
            h = Handler(th!!.looper)
            svc = this
            listenForCar()
        }
        h!!.post {
            when (a) {
                WATCH -> {
                    watch = true
                    Log.i("BUZZLAB", "AUTO watching")
                    checkCar()
                }
                UNWATCH -> {
                    watch = false
                    Log.i("BUZZLAB", "AUTO off")
                    if (live == null) {
                        stopSelf()
                        return@post
                    }
                }
                RECORD -> if (live == null && i != null) startDrive(i.getStringExtra("car"), i.getStringExtra("place"), i.getStringExtra("tyre"), "")
            }
            gpsRate()
            refresh()
        }
        return START_STICKY
    }

    private fun note(): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("rec", "Recording", NotificationManager.IMPORTANCE_LOW))
        val r = live != null
        val title = when {
            !r -> "Watching for a drive"
            auto -> "Recording drive (started by itself)"
            else -> "Recording drive"
        }
        val text = if (r) {
            "Vibration and speed. Tap to open."
        } else {
            "Starts recording when Android Auto connects or you're above ${START_MPH.toInt()} mph. Turn off in Settings."
        }
        return Notification.Builder(this, "rec").setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle(title).setContentText(text).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
    }

    private fun refresh() {
        runCatching { getSystemService(NotificationManager::class.java).notify(1, note()) }
    }

    /** Once a second while recording; every 5 s while watching and moving lately; every 20 s while still. */
    private fun gpsRate() {
        val want = when {
            live != null -> 1000L
            System.currentTimeMillis() - lastMoving < 120_000 -> 5000L
            else -> 20000L
        }
        if (want == interval) return
        interval = want
        try {
            lm.removeUpdates(this)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, want, 0f, this, th!!.looper)
        } catch (e: SecurityException) {
            lastError = "Location permission is off"
            Log.w("BUZZLAB", "gps $e")
        }
    }

    /** how: "" when started by hand, else what started it: "speed" or "android-auto". */
    private fun startDrive(car: String?, place: String?, tyre: String?, how: String) {
        val byItself = how.isNotEmpty()
        try {
            discard = false
            paused = false
            pausedMs = 0
            km = 0.0
            topMph = 0.0
            lastFix = 0
            pauses = 0
            samples = 0
            fixes = 0
            error = null
            auto = byItself
            charts = Live()
            slowSince = 0
            startMs = System.currentTimeMillis()
            val did = startMs.toString()
            val d = st.driveDir(did).apply { mkdirs() }
            meta = JSONObject().put("id", did).put("start", startMs).put("car", car).put("place", place).put("note", "").put("tyre", tyre)
                .put("recording", true).put("auto", byItself).put("startedBy", how.ifEmpty { "hand" })
                .also { st.saveMeta(it) }
            acc = DataOutputStream(BufferedOutputStream(FileOutputStream(File(d, "acc.bin")), 1 shl 16))
            gps = PrintWriter(BufferedWriter(FileWriter(File(d, "gps.csv")))).apply { println("t_ns,lat,lon,speed_ms,acc_m,bearing") }
            marks = PrintWriter(FileWriter(File(d, "marks.csv")), true).apply { println("t_ns,label") }
            wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "buzzlab:rec").apply { acquire(4 * 3600 * 1000L) }
            closed = false
            sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), 0, 0, h)
            live = this
            Log.i("BUZZLAB", "REC start $did${if (byItself) " auto" else ""}")
            val t = HandlerThread("live", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
            lt = t
            val lh = Handler(t.looper)
            val lv = charts
            lh.post(
                object : Runnable {
                    override fun run() {
                        if (closed) return
                        try {
                            if (!paused) lv.step(mph)
                        } catch (x: Throwable) {
                            Log.w("BUZZLAB", "live $x")
                        }
                        lh.postDelayed(this, 1000)
                    }
                },
            )
            gpsRate()
            refresh()
        } catch (e: Exception) {
            error = e.toString()
            lastError = e.message.toString()
            Log.e("BUZZLAB", "REC fail", e)
            endDrive(true)
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (closed || paused) return
        try {
            val out = acc ?: return
            out.writeLong(e.timestamp)
            for (k in 0..2) out.writeFloat(e.values[k])
            charts.put(e.timestamp, e.values[0], e.values[1], e.values[2])
            if (++samples % 4740 == 0L) {
                out.flush()
                gps?.flush()
            }
        } catch (x: IOException) {
            error = "Storage: ${x.message}"
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onLocationChanged(l: Location) {
        val now = System.currentTimeMillis()
        val v = if (l.hasSpeed()) l.speed * Dsp.MPH else Double.NaN
        if (!paused || live == null) mph = v
        if (v >= STOP_MPH) lastMoving = now
        if (live != null && !closed && !paused) {
            fixes++
            if (l.hasSpeed()) {
                topMph = maxOf(topMph, v)
                val t = l.elapsedRealtimeNanos
                if (lastFix > 0 && t - lastFix < 5_000_000_000L) km += l.speed * (t - lastFix) / 1e12
                lastFix = t
            }
            val speed = if (l.hasSpeed()) l.speed.toString() else ""
            val bearing = if (l.hasBearing()) l.bearing.toString() else ""
            gps?.println("${l.elapsedRealtimeNanos},${l.latitude},${l.longitude},$speed,${l.accuracy},$bearing")
            // While Android Auto is connected, its disconnect ends the drive, so a long wait in traffic doesn't.
            if (auto && !carRule.car) {
                if (v < STOP_MPH) {
                    if (slowSince == 0L) {
                        slowSince = now
                    } else if (now - slowSince > STOP_AFTER_MS) {
                        note(this, "Stopped for ${STOP_AFTER_MS / 60000} minutes, drive saved")
                        endDrive(false)
                    }
                } else {
                    slowSince = 0
                }
            }
        } else if (live == null && watch) {
            fast = if (v >= START_MPH) fast + 1 else 0
            if (fast >= START_FIXES) {
                fast = 0
                note(this, "Above ${START_MPH.toInt()} mph, recording started")
                autoStart("speed")
            }
        }
        checkCar()
        gpsRate()
    }

    /** Hears Android Auto connect and disconnect. Checked again on every GPS fix, in case a broadcast is missed. */
    private fun listenForCar() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                h?.post { checkCar() }
            }
        }
        val f = IntentFilter(CarLink.ACTION)
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
            carWatch = r
        } catch (e: Exception) {
            Log.w("BUZZLAB", "car watch $e")
        }
    }

    /** On the recording thread: start or save around Android Auto. */
    private fun checkCar() {
        val connected = CarLink.connected(this)
        when (carRule.step(System.currentTimeMillis(), connected, watch, live != null, auto, topMph)) {
            CarRule.Act.START -> {
                note(this, "Android Auto connected, recording started")
                autoStart("android-auto")
            }
            CarRule.Act.CONNECTED -> note(this, "Android Auto connected" + if (live != null) ", already recording" else ", not watching")
            CarRule.Act.SAVE -> {
                note(this, "Android Auto disconnected, drive saved")
                endDrive(false)
            }
            CarRule.Act.DISCARD -> {
                note(this, "Android Auto disconnected; the car never moved, so nothing was kept")
                endDrive(true)
            }
            CarRule.Act.DISCONNECTED -> note(this, "Android Auto disconnected")
            CarRule.Act.NONE -> if (!connected && carRule.car) h?.postDelayed({ checkCar() }, carRule.grace + 500)
        }
    }

    /** Starts a drive with the car and place last picked on the Record screen. */
    private fun autoStart(how: String) {
        val ui = getSharedPreferences("ui", 0)
        val c = st.car(ui.getString("car", "").orEmpty()) ?: st.cars().optJSONObject(0) ?: return
        val places = MainActivity.PLACES
        startDrive(c.optString("id"), places[ui.getInt("place", 0).coerceIn(0, places.size - 1)], c.optString("tyre"), how)
    }

    override fun onProviderDisabled(p: String) {}

    override fun onProviderEnabled(p: String) {}

    @Deprecated("Needed below Android 10")
    override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}

    /** Stop taking readings without ending the drive. */
    fun pause() {
        if (paused) return
        pauseAt = System.currentTimeMillis()
        paused = true
        pauses++
        lastFix = 0
        mph = Double.NaN
        try {
            acc?.flush()
            gps?.flush()
        } catch (_: IOException) {
        }
    }

    fun resume() {
        if (!paused) return
        pausedMs += System.currentTimeMillis() - pauseAt
        paused = false
        slowSince = 0
    }

    private fun pausedNow() = pausedMs + if (paused) System.currentTimeMillis() - pauseAt else 0

    fun activeMs() = System.currentTimeMillis() - startMs - pausedNow()

    fun mark(s: String) {
        if (!closed) marks?.println("${SystemClock.elapsedRealtimeNanos()},$s")
    }

    private fun endDrive(dropIt: Boolean) {
        if (live !== this) return
        discard = dropIt
        closed = true
        lt?.quitSafely()
        val app = applicationContext
        try {
            sm.unregisterListener(this)
            acc?.close()
            gps?.close()
            marks?.close()
            val m = meta
            if (m != null && discard) {
                st.delDrive(m.getString("id"))
                Log.i("BUZZLAB", "REC discarded")
            } else if (m != null) {
                m.put("recording", false).put("end", System.currentTimeMillis()).put("pauses", pauses).put("paused_s", pausedNow() / 1000)
                    .put("samples", samples).put("fixes", fixes)
                error?.let { m.put("error", it) }
                st.saveMeta(m)
            }
        } catch (e: Exception) {
            lastError = e.message.toString()
            Log.e("BUZZLAB", "REC close", e)
        }
        wl?.let { if (it.isHeld) it.release() }
        live = null
        paused = false
        if (!discard) {
            try {
                SyncJob.schedule(app, 120000)
            } catch (e: Exception) {
                Log.w("BUZZLAB", "sched $e")
            }
        }
        Log.i("BUZZLAB", "REC stop $samples $fixes")
        gpsRate()
        refresh()
    }

    /** Returns at once; the files are finished on the recording thread, and live is cleared only when they are closed. */
    override fun onDestroy() {
        val t = th
        if (t != null) {
            h!!.post {
                endDrive(discard)
                runCatching { lm.removeUpdates(this) }
                carWatch?.let { runCatching { unregisterReceiver(it) } }
                if (svc === this) svc = null
                t.quitSafely()
            }
        } else if (svc === this) {
            svc = null
        }
        super.onDestroy()
    }

    companion object {
        const val RECORD = "record"
        const val WATCH = "watch"
        const val UNWATCH = "unwatch"
        const val START_MPH = 15.0
        const val STOP_MPH = 3.0
        const val START_FIXES = 3
        const val STOP_AFTER_MS = 180_000L
        const val NO_GPS_MS = 300_000L

        @Volatile @JvmField
        var live: RecService? = null

        @Volatile @JvmField
        var svc: RecService? = null

        @Volatile @JvmField
        var lastError: String? = null

        /** What automatic recording did and why, newest first, last 40 lines. */
        @JvmStatic fun log(c: Context): String = c.getSharedPreferences("ui", 0).getString("autoLog", "").orEmpty()

        @Synchronized
        private fun note(c: Context, s: String) {
            val stamp = SimpleDateFormat("d MMM HH:mm:ss", Locale.UK).format(Date())
            val lines = ("$stamp  $s\n" + log(c)).split("\n").take(40)
            c.getSharedPreferences("ui", 0).edit().putString("autoLog", lines.joinToString("\n", postfix = "\n")).apply()
            Log.i("BUZZLAB", "AUTO $s")
        }

        /** Save or discard the drive. Watching carries on; otherwise the service ends. Safe to call from any thread. */
        @JvmStatic fun endNow(discard: Boolean) {
            val s = svc ?: return
            val h = s.h ?: return
            h.post {
                s.endDrive(discard)
                if (!s.watch) s.stopSelf()
            }
        }
    }
}
