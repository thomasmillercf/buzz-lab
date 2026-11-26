package uk.buzzlab

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import uk.buzzlab.MainActivity.Companion.AC
import uk.buzzlab.MainActivity.Companion.CARD
import uk.buzzlab.MainActivity.Companion.EDGE
import uk.buzzlab.MainActivity.Companion.FG
import uk.buzzlab.MainActivity.Companion.MU
import uk.buzzlab.MainActivity.Companion.NAV
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// The building blocks every page uses. Each adds itself to the page's list unless it says otherwise.

fun MainActivity.dp(d: Int) = (d * resources.displayMetrics.density).toInt()

fun MainActivity.pref(k: String, d: String = "") = ui.getString(k, d) ?: d

fun MainActivity.url() = pref("url")

fun MainActivity.has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

fun MainActivity.toast(s: String, long: Boolean = false) = Toast.makeText(this, s, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

/** On the page's status line if it has one, else as a toast. Safe from any thread. */
fun MainActivity.say(s: String) = runOnUiThread { status?.let { it.text = s } ?: toast(s, true) }

fun MainActivity.version(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

fun MainActivity.copy(label: String, s: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, s))
    toast("Copied")
}

fun MainActivity.send(url: String) =
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Send link"))

fun date(t: Long, pattern: String = "EEE d MMM HH:mm"): String = SimpleDateFormat(pattern, Locale.UK).format(Date(t))

/** Start of a date range: 0 all time, 1 today, 2 last 7 days, 3 last 30, 4 last 90. */
fun since(range: Int): Long {
    val k = Calendar.getInstance()
    for (f in listOf(Calendar.HOUR_OF_DAY, Calendar.MINUTE, Calendar.SECOND, Calendar.MILLISECOND)) k.set(f, 0)
    val day = k.timeInMillis
    return when (range) {
        0 -> 0
        1 -> day
        2 -> day - 86400000L * 6
        3 -> day - 86400000L * 29
        else -> day - 86400000L * 89
    }
}

/** How a drive started: 0 any, 1 automatic only, 2 started by hand only. */
fun startedAs(m: JSONObject, k: Int) = k == 0 || (k == 1) == m.optBoolean("auto")

fun MainActivity.carName(id: String): String = st.car(id)?.optString("name") ?: "Unknown car"

fun MainActivity.cars(): List<JSONObject> = st.cars().objects()

fun names(cars: List<JSONObject>) = cars.map { c -> c.optString("name") + c.optString("tyre").let { if (it.isEmpty()) "" else "  ($it)" } }

fun idx(cars: List<JSONObject>, id: String) = cars.indexOfFirst { it.optString("id") == id }.coerceAtLeast(0)

fun MainActivity.findDrive(id: String) = st.list().firstOrNull { it.optString("id") == id }

fun MainActivity.place() = MainActivity.PLACES[ui.getInt("place", 0).coerceIn(MainActivity.PLACES.indices)]

// ---------- title bar

/** The one title area. Tabs use it for something useful instead of repeating the tab's name. */
fun MainActivity.top(title: String, back: Boolean = false): TextView {
    bar.removeAllViews()
    if (back) icon(R.drawable.i_back, "Back") { back() }
    val t = TextView(this).apply {
        text = title
        textSize = 20f
        setTextColor(FG)
        typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(8), 0, dp(8), 0)
    }
    bar.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
    return t
}

fun MainActivity.icon(res: Int, what: String, r: () -> Unit): ImageButton {
    val b = ImageButton(this).apply {
        setImageResource(res)
        setColorFilter(AC)
        background = ripple(ColorDrawable(0))
        contentDescription = what
        setOnClickListener {
            tap(it)
            r()
        }
    }
    bar.addView(b, LinearLayout.LayoutParams(dp(48), dp(48)))
    return b
}

/** The ⋮ menu. */
fun MainActivity.overflow(items: Map<String, () -> Unit>) {
    lateinit var b: ImageButton
    b = icon(R.drawable.i_more, "More") { popup(b, items) }
}

fun MainActivity.popup(at: View, items: Map<String, () -> Unit>) {
    val p = PopupMenu(this, at, Gravity.END)
    for (k in items.keys) p.menu.add(k)
    p.setOnMenuItemClickListener {
        items[it.title.toString()]?.invoke()
        true
    }
    p.show()
}

// ---------- page content

fun MainActivity.text(s: String, size: Int, bold: Boolean = false, col: Int = FG): TextView {
    val t = TextView(this).apply {
        text = s
        textSize = size.toFloat()
        setTextColor(col)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(6), 0, dp(6))
    }
    list.addView(t)
    return t
}

/** Smaller grey text. */
fun MainActivity.note(s: String, size: Int = 14) = text(s, size, col = MU)

fun MainActivity.heading(s: String) = text(s, 18, true)

fun MainActivity.box(c: Int): Drawable {
    val g = GradientDrawable().apply {
        setColor(c)
        cornerRadius = dp(8).toFloat()
        setStroke(dp(1), if (c == CARD) EDGE else c)
    }
    return ripple(g)
}

/** Press effect for every button. */
fun MainActivity.ripple(d: Drawable): Drawable {
    val mask = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(8).toFloat()
    }
    return RippleDrawable(ColorStateList.valueOf(0x66FFFFFF), d, mask)
}

fun tap(v: View) = v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

/** A full-width button with an optional grey second line. Taps give a small buzz. */
fun MainActivity.button(a: String, b: String = "", l: ((View) -> Unit)? = null): Button {
    val x = Button(this)
    x.isAllCaps = false
    if (b.isEmpty()) {
        x.text = a
    } else {
        val s = SpannableString("$a\n$b")
        s.setSpan(RelativeSizeSpan(.84f), a.length, s.length, 0)
        s.setSpan(ForegroundColorSpan(MU), a.length, s.length, 0)
        x.text = s
    }
    x.isSingleLine = false
    x.maxLines = 4
    x.ellipsize = null
    x.textSize = 16f
    x.setTextColor(FG)
    x.gravity = Gravity.START or Gravity.CENTER_VERTICAL
    x.setPadding(dp(14), dp(12), dp(14), dp(12))
    x.background = box(CARD)
    if (l != null) {
        x.setOnClickListener {
            tap(it)
            l(it)
        }
    }
    list.addView(x, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(5)) })
    return x
}

/** The main action on a page: orange, centred. */
fun MainActivity.primary(b: Button): Button {
    b.background = box(AC)
    b.setTextColor(NAV)
    b.gravity = Gravity.CENTER
    return b
}

/** Grey and centred, for Skip, Discard and the like. */
fun quiet(b: Button): Button {
    b.setTextColor(MU)
    b.gravity = Gravity.CENTER
    return b
}

/** Show at once that a press was taken, and block a second press. */
fun MainActivity.working(b: Button, s: String) {
    tap(b)
    b.text = s
    b.isEnabled = false
    b.alpha = .75f
    b.background = box(EDGE)
    b.setTextColor(FG)
}

/** Undo working(). */
fun MainActivity.ready(b: Button, s: String) {
    b.text = s
    b.isEnabled = true
    b.alpha = 1f
    b.background = box(CARD)
}

/** Run then once ok is true, or late if it takes longer than ms. Checks every 60 ms on the screen thread. */
fun MainActivity.until(ms: Long, ok: () -> Boolean, then: () -> Unit, late: () -> Unit) {
    val end = SystemClock.uptimeMillis() + ms
    object : Runnable {
        override fun run() {
            when {
                ok() -> then()
                SystemClock.uptimeMillis() > end -> late()
                else -> wait.postDelayed(this, 60)
            }
        }
    }.run()
}

/** A button that opens a list to choose from. */
fun MainActivity.spinner(options: List<String>, sel: Int, f: (Int) -> Unit): Button {
    var at = sel.coerceIn(0, options.size - 1)
    val b = button("${options[at]}  ▾")
    b.setOnClickListener {
        tap(it)
        val rows = options.mapIndexed { i, o -> (if (i == at) "✓  " else "     ") + o }.toTypedArray()
        val d = AlertDialog.Builder(this).setItems(rows) { _, i ->
            if (i != at) {
                at = i
                b.text = "${options[i]}  ▾"
                f(i)
            }
        }.create()
        d.show()
        rounded(d, true)
    }
    return b
}

/** Dialogs in the card colour with round corners. */
fun MainActivity.rounded(d: AlertDialog, edge: Boolean = false) {
    val g = GradientDrawable().apply {
        setColor(CARD)
        cornerRadius = dp(16).toFloat()
        if (edge) setStroke(dp(1), EDGE)
    }
    d.window?.setBackgroundDrawable(InsetDrawable(g, dp(12)))
}

fun MainActivity.field(label: String, value: String): EditText {
    note(label, 15)
    val x = EditText(this).apply {
        setText(value)
        setTextColor(FG)
        isSingleLine = true
    }
    list.addView(x)
    return x
}

fun MainActivity.checkBox(label: String, on: Boolean = false, add: Boolean = true): CheckBox {
    val c = CheckBox(this).apply {
        text = label
        setTextColor(FG)
        buttonTintList = ColorStateList.valueOf(AC)
        isChecked = on
    }
    if (add) list.addView(c)
    return c
}

fun MainActivity.ask(q: String, r: () -> Unit) {
    AlertDialog.Builder(this).setMessage(q).setPositiveButton("Delete") { _, _ -> r() }.setNegativeButton("Keep", null).show()
}

/** A row the width of the page, for columns or buttons side by side. */
fun MainActivity.row(): LinearLayout {
    val r = LinearLayout(this)
    list.addView(r, LinearLayout.LayoutParams(-1, -2))
    return r
}

/** Lay several things side by side: whatever is added goes into one equal column. */
fun MainActivity.column(row: LinearLayout, add: () -> Unit) {
    val save = list
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val q = LinearLayout.LayoutParams(0, -2, 1f)
    if (row.childCount > 0) q.marginStart = dp(10)
    row.addView(c, q)
    list = c
    try {
        add()
    } finally {
        list = save
    }
}

/** One large figure with its label, as a card. */
fun MainActivity.figure(label: String): TextView {
    val c = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = box(CARD)
        setPadding(dp(14), dp(10), dp(14), dp(12))
    }
    list.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) })
    c.addView(
        TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(MU)
        },
    )
    val v = TextView(this).apply {
        text = MainActivity.DASH
        textSize = 34f
        setTextColor(FG)
        typeface = Typeface.DEFAULT_BOLD
        fontFeatureSettings = "tnum"
    }
    c.addView(v)
    return v
}

/** A row of figure cards, one per label. */
fun MainActivity.figures(vararg labels: String): List<TextView> {
    val r = row()
    return labels.map { l ->
        lateinit var v: TextView
        column(r) { v = figure(l) }
        v
    }
}

/** Two or more equal buttons side by side, such as a tab switch. The caller colours them. */
fun MainActivity.toggles(labels: List<String>, top: Int = 6, bottom: Int = 4): List<Button> {
    val r = row()
    r.setPadding(0, dp(top), 0, dp(bottom))
    return labels.mapIndexed { i, s ->
        val x = Button(this).apply {
            isAllCaps = false
            text = s
            textSize = 15f
            minHeight = dp(44)
            minimumHeight = dp(44)
        }
        val q = LinearLayout.LayoutParams(0, -2, 1f)
        if (i > 0) q.marginStart = dp(8)
        r.addView(x, q)
        x
    }
}

/** Orange for the chosen one. */
fun MainActivity.choose(bs: List<Button>, on: Int) = bs.forEachIndexed { k, b ->
    b.background = box(if (k == on) AC else CARD)
    b.setTextColor(if (k == on) NAV else FG)
}

fun MainActivity.notificationsButton() {
    if (getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) return
    button("Turn on notifications", "They're off, so upload progress can't show") {
        startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
    }
}
