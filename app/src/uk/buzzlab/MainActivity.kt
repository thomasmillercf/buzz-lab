package uk.buzzlab

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/**
 * The one screen. It holds the shared state; each tab and page is drawn by its own file
 * (HomeScreen, SyncScreen, SettingsScreen, HistoryScreen, AnalysisScreen, DriveScreen,
 * DrivingScreen, ShareScreen, Transfer, FeelScreen), with the building blocks in Ui.kt.
 */
class MainActivity : Activity() {
    lateinit var engine: Engine
    lateinit var st: Store

    /** Calibration results. */
    lateinit var sp: SharedPreferences

    /** Choices, server details and sync status. */
    lateinit var ui: SharedPreferences

    lateinit var root: LinearLayout
    lateinit var bar: LinearLayout
    lateinit var sv: ScrollView

    /** Where the current page adds its views. column() points it at a column for a moment. */
    lateinit var list: LinearLayout

    /** Buttons pinned under the page, such as Stop while recording. */
    lateinit var act: LinearLayout
    lateinit var foot: TextView
    lateinit var nav: LinearLayout

    var status: TextView? = null
    var report: TextView? = null
    var liveLine: TextView? = null

    val tick = Handler(Looper.getMainLooper())
    val wait = Handler(Looper.getMainLooper())

    /** The tab on show: home, analysis, driving or feel. */
    var tab = "home"

    /** Back for the page on show, or null on a tab itself. */
    var sub: (() -> Unit)? = null
    var inSub = false
    var pageTitle = ""
    val pos = HashMap<String, Int>()

    /** Drives chosen on History, or null when not choosing. */
    var picked: MutableSet<String>? = null

    var onSync = false
    var syncBack: () -> Unit = { show("home") }
    var linksBack: () -> Unit = { sharePage() }

    // feel
    @Volatile var busy = false

    @Volatile var cancel = false
    var playing: AudioTrack? = null
    var lit: Button? = null
    var clips = JSONObject()
    var replayLast: (() -> Unit)? = null

    // export and import, remembered while the file picker is open
    var expIds = ""
    var expPos = false
    var expFull = false
    var impCar = ""
    var impPlace = ""
    var impTyre = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (b == null && Build.VERSION.SDK_INT >= 31) holdSplash()
        engine = Engine(this)
        st = Store(this)
        sp = getSharedPreferences("cal", 0)
        ui = getSharedPreferences("ui", 0)
        clips = runCatching { JSONObject(assets.open("clips.json").use { it.readBytes().decodeToString() }) }.getOrDefault(JSONObject())
        takeServerFile()
        for (m in st.list()) {
            if (m.optBoolean("recording") && RecService.live == null) {
                runCatching { st.saveMeta(m.put("recording", false).put("error", "Recording ended unexpectedly")) }
            }
        }
        layout()
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { if (!back()) finish() }
        startWatching()
        show(b?.getString("tab", "home") ?: "home")
        if (b == null) go(intent)
        if (b == null && !ui.getBoolean("welcomed", false) && intent?.data == null) {
            // people who set a server up before this screen existed have already chosen
            if (SyncJob.ready(this) || st.list().isNotEmpty()) ui.edit().putBoolean("welcomed", true).apply() else welcome()
        }
    }

    /** The logo stays up at least SPLASH_MS on a cold start. */
    private fun holdSplash() {
        val up = SystemClock.uptimeMillis()
        val decor = window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (SystemClock.uptimeMillis() - up < SPLASH_MS) return false
                    decor.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
        Handler(Looper.getMainLooper()).postDelayed({ decor.invalidate() }, SPLASH_MS + 10)
    }

    /** A server.json pushed over adb sets up the server once, then is deleted. */
    private fun takeServerFile() {
        val f = File(filesDir, "server.json")
        if (!f.exists()) return
        try {
            val o = JSONObject(Store.read(f).orEmpty())
            ui.edit().putString("url", o.getString("url")).putString("su", o.getString("user")).putString("sp", o.getString("pass")).commit()
        } catch (_: Exception) {
        } finally {
            f.delete()
        }
    }

    private fun layout() {
        val p = dp(16)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, dp(4), p, dp(30))
        }
        sv = ScrollView(this).apply { addView(list) }
        bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(4), dp(4))
            minimumHeight = dp(60)
        }
        act = LinearLayout(this).apply {
            setPadding(p, dp(6), p, dp(8))
            setBackgroundColor(BG)
            visibility = View.GONE
        }
        foot = TextView(this).apply {
            text = "Set it up while parked. A passenger should use the phone while moving."
            textSize = 13f
            setTextColor(MU)
            gravity = Gravity.CENTER
            setPadding(p, dp(8), p, dp(12))
            visibility = View.GONE
        }
        nav = LinearLayout(this).apply { setBackgroundColor(NAV) }
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(act, LinearLayout.LayoutParams(-1, -2))
        root.addView(foot, LinearLayout.LayoutParams(-1, -2))
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        @Suppress("DEPRECATION")
        root.setOnApplyWindowInsetsListener { v, ins ->
            v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, 0)
            nav.setPadding(0, 0, 0, ins.systemWindowInsetBottom)
            ins
        }
        for ((tag, label, icon) in TABS) {
            val x = Button(this).apply {
                setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0)
                compoundDrawablePadding = dp(2)
                isAllCaps = false
                text = label
                this.tag = tag
                textSize = 13f
                minHeight = dp(64)
                setPadding(0, dp(8), 0, dp(6))
                setOnClickListener { show(tag) }
            }
            nav.addView(x, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        intent = i
        go(i)
    }

    override fun onPause() {
        super.onPause()
        if (tab == "feel") stopPlaying()
        tick.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        SyncJob.schedule(this)
        sendDeletes()
        if (tab == "home" && sub == null) {
            show("home")
        } else if (inSub && onSync) {
            syncPage(syncBack)
        }
    }

    /** Coming back from Android's own settings screens. */
    override fun onRestart() {
        super.onRestart()
        if (sub != null && pageTitle == "Settings") settings()
    }

    @Deprecated("Used below Android 13")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (!back()) super.onBackPressed()
    }

    fun back(): Boolean {
        if (picked != null && sub == null) {
            picked = null
            show("analysis")
            return true
        }
        sub?.let {
            sub = null
            it()
            return true
        }
        if (tab != "home") {
            show("home")
            return true
        }
        return false
    }

    override fun onSaveInstanceState(b: Bundle) {
        super.onSaveInstanceState(b)
        b.putString("tab", tab)
    }

    /** Show a tab, scrolled to where it was left. */
    fun show(t: String) {
        if (busy && t != "feel") {
            say("Calibrating. Tap Stop first.")
            return
        }
        if (!inSub && list.childCount > 0) pos[tab] = sv.scrollY
        if (t != "analysis") picked = null
        inSub = false
        tab = t
        sub = null
        pageTitle = ""
        tick.removeCallbacksAndMessages(null)
        if (t == "feel") {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            stopPlaying()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        for (i in 0 until nav.childCount) {
            val x = nav.getChildAt(i) as Button
            val on = x.tag == t
            x.setTextColor(if (on) AC else MU)
            x.compoundDrawableTintList = ColorStateList.valueOf(if (on) AC else MU)
            x.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            x.isSelected = on
            x.background = ripple(ColorDrawable(NAV))
        }
        clear()
        when (t) {
            "home" -> home()
            "analysis" -> analysis()
            "driving" -> driving()
            else -> feel()
        }
        val y = pos[t] ?: 0
        sv.post { sv.scrollTo(0, y) }
    }

    /** Start a page inside a tab, with its own Back. */
    fun page(title: String, up: () -> Unit) {
        if (!inSub) pos[tab] = sv.scrollY
        inSub = true
        onSync = false
        pageTitle = title
        clear()
        sub = up
        sv.scrollTo(0, 0)
        top(title, true)
    }

    private fun clear() {
        act.visibility = View.GONE
        foot.visibility = View.GONE
        list.removeAllViews()
        status = null
        report = null
        liveLine = null
    }

    @Deprecated("Still the simplest way to get a file from the system picker")
    override fun onActivityResult(q: Int, r: Int, i: Intent?) {
        val u = i?.data
        if (r != RESULT_OK || u == null) return
        transferDone(q, u)
    }

    override fun onRequestPermissionsResult(q: Int, p: Array<String>, g: IntArray) {
        when (q) {
            ASK_BACKGROUND -> {
                if (!has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                    toast("Location wasn't set to Allow all the time, so it won't start by itself after a restart.", true)
                }
                if (sub != null) settings()
            }
            ASK_AUTO -> {
                if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    startWatching()
                } else {
                    ui.edit().putBoolean("auto", false).apply()
                    toast("Precise location is needed for automatic recording.", true)
                }
                if (sub != null) settings()
            }
            ASK_RECORD -> {
                if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    startRec(cars(), null)
                } else {
                    toast("Precise location is needed for speed. Nothing is recorded without it.", true)
                }
            }
        }
    }

    companion object {
        const val USAGE = AudioAttributes.USAGE_GAME
        const val SPLASH_MS = 400L

        // App colours
        const val BG = 0xFF021B29.toInt()
        const val CARD = 0xFF043349.toInt()
        const val FG = 0xFFF2F0EA.toInt()
        const val MU = 0xFF9DB6C3.toInt()
        const val AC = 0xFFFF803A.toInt()

        /** Text on orange, and the nav bar. */
        const val NAV = 0xFF01131D.toInt()

        /** A chosen or switched-on row. */
        const val ON = 0xFF0B4E6E.toInt()
        const val EDGE = 0xFF14506C.toInt()
        const val RED = 0xFFD4220D.toInt()
        const val DASH = "–"

        const val ASK_RECORD = 1
        const val ASK_SYNC = 5
        const val ASK_AUTO = 7
        const val ASK_BACKGROUND = 8
        const val PICK_IMPORT = 2
        const val PICK_EXPORT = 3

        private val TABS = listOf(
            Triple("home", "Home", R.drawable.i_home),
            Triple("analysis", "Analysis", R.drawable.i_chart),
            Triple("driving", "Driving", R.drawable.i_drive),
            Triple("feel", "Feel", R.drawable.i_feel),
        )

        val PLACES = arrayOf("Seat rail, taped", "Console, taped", "Console, loose", "Steering column cover, taped", "Rear seat rail, taped", "Other")
        val DATES = listOf("All time", "Today", "Last 7 days", "Last 30 days", "Last 90 days")
        val HOW = listOf("Started any way", "Started by itself", "Started by me")
    }
}
