package uk.buzzlab

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import uk.buzzlab.MainActivity.Companion.AC
import uk.buzzlab.MainActivity.Companion.FG
import uk.buzzlab.MainActivity.Companion.MU
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.concurrent.thread

private const val SHORTEST_MS = 30000

private fun JSONObject.shareable() = !optBoolean("recording") && optLong("end") - optLong("start") >= SHORTEST_MS

/** Share these drives, if the server has them. */
fun MainActivity.shareThese(ds: List<JSONObject>, title: String, view: JSONObject?, back: () -> Unit) {
    if (!SyncJob.ready(this)) {
        toast("Set up the server first, in the menu on Home.", true)
        return
    }
    val long = ds.filter { it.shareable() }
    val (on, off) = long.partition { it.optString("syncedPos") == url() }
    if (on.isEmpty()) {
        toast(if (off.isNotEmpty()) "Not on the server yet. Sync, then share." else "Nothing here is long enough to share.", true)
        return
    }
    if (off.isNotEmpty()) toast("${off.size} not on the server yet, so left out.", true)
    sharePage(on.mapTo(LinkedHashSet()) { it.optString("id") }, title, view, back)
}

/** One request to the server; the answer arrives on the screen thread as (code, body). Code 0 means no connection. */
fun MainActivity.api(method: String, path: String, body: String?, done: (Int, String) -> Unit) {
    thread {
        var code = 0
        var out = ""
        try {
            val c = URL(url() + path).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.requestMethod = method
            c.setRequestProperty("Authorization", SyncJob.auth(ui))
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            code = c.responseCode
            out = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            c.disconnect()
        } catch (x: Exception) {
            out = SyncJob.why(x)
        }
        runOnUiThread { done(code, out) }
    }
}

fun problem(code: Int, body: String): String = when (code) {
    0 -> "No connection: $body"
    401 -> "Wrong user name or password."
    else -> runCatching { JSONObject(body).getString("error") }.getOrDefault("Server said $code")
}

/** Choose drives, title, PIN and how long the link lasts. */
fun MainActivity.sharePage(pre: Set<String>? = null, title: String? = null, view: JSONObject? = null, back: () -> Unit = { show("home") }) {
    page("Share results", back)
    note(
        "Makes a web link to the results you tick. It shows charts, comparisons and the drive list. It never shows positions, plates, notes or raw readings. " +
            "Anyone with the link can open it, so add a PIN for anything you care about.",
        15,
    )
    val ti = field("Title", title ?: "Vibration results")
    val pin = pinField(field("PIN (optional, 4 or more characters)", ""))
    note("Link works for", 15)
    var days = 30
    val choices = listOf(7, 30, 90)
    spinner(choices.map { "$it days" }, 1) { days = choices[it] }
    heading("Drives to include")
    val boxes = LinkedHashMap<String, CheckBox>()
    var left = 0
    for (m in st.list()) {
        if (m.optBoolean("recording")) continue
        if (m.optString("syncedPos") != url()) {
            left++
            continue
        }
        if (!m.shareable()) continue
        val id = m.optString("id")
        val c = checkBox("${carName(m.optString("car"))}, ${date(m.optLong("start"))}\n${m.optString("place")}", pre?.contains(id) == true)
        c.textSize = 15f
        c.setPadding(dp(6), dp(8), 0, dp(8))
        boxes[id] = c
    }
    if (boxes.isEmpty()) text("No drives are on the server yet. Sync first.", 15, col = AC)
    if (left > 0) note("$left ${if (left == 1) "drive isn't" else "drives aren't"} on the server yet, so can't be shared.")
    primary(
        button("Make link") { v ->
            val ids = JSONArray()
            boxes.filterValues { it.isChecked }.keys.forEach { ids.put(it) }
            val p = pin.text.toString().trim()
            if (ids.length() == 0) {
                toast("Tick at least one drive.")
                return@button
            }
            if (!pinOk(p)) return@button
            val b = v as Button
            working(b, "Making link...")
            val body = JSONObject().put("title", ti.text.toString().trim()).put("drives", ids).put("days", days).put("pin", p)
                .put("view", view ?: JSONObject.NULL).toString()
            api("POST", "/api/shares", body) { code, o ->
                if (code != 200) {
                    ready(b, "Make link")
                    primary(b)
                    toast(problem(code, o), true)
                    return@api
                }
                runCatching { shared(url() + JSONObject(o).getString("path"), p.isNotEmpty()) }.onFailure { toast(it.toString(), true) }
            }
        },
    ).gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
    button("Links already made") { linksPage() }
}

private fun pinField(e: EditText): EditText {
    e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    return e
}

private fun MainActivity.pinOk(p: String): Boolean {
    if (p.isEmpty() || p.length >= 4) return true
    toast("The PIN needs 4 or more characters.")
    return false
}

/** Share one drive in one step. The route map is optional because it shows where you drove. */
fun MainActivity.shareOne(m: JSONObject) {
    if (m.optString("syncedPos") != url()) {
        toast("This drive isn't on the server yet. Sync, then share.", true)
        return
    }
    val v = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }
    val route = checkBox("Include the route map\nShows where you drove, which may include home", add = false)
    v.addView(route)
    val pin = pinField(EditText(this)).apply {
        hint = "PIN (optional)"
        setHintTextColor(MU)
        setTextColor(FG)
    }
    v.addView(pin)
    v.addView(
        TextView(this).apply {
            text = "Works for 30 days. Anyone with the link can open it."
            setTextColor(MU)
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        },
    )
    val d = AlertDialog.Builder(this).setTitle("Share this drive").setView(v).setPositiveButton("Make link", null).setNegativeButton("Cancel", null).create()
    d.show()
    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { x ->
        val p = pin.text.toString().trim()
        if (!pinOk(p)) return@setOnClickListener
        x.isEnabled = false
        val title = "${carName(m.optString("car"))}, ${date(m.optLong("start"), "d MMMM yyyy")}"
        val body = JSONObject().put("title", title).put("drives", JSONArray().put(m.optString("id"))).put("days", 30).put("pin", p)
            .put("route", route.isChecked).toString()
        api("POST", "/api/shares", body) { code, o ->
            d.dismiss()
            if (code != 200) {
                toast(problem(code, o), true)
                return@api
            }
            try {
                val link = url() + JSONObject(o).getString("path")
                shared(link, p.isNotEmpty())
                send(link)
            } catch (e: Exception) {
                toast(e.toString(), true)
            }
        }
    }
}

/** The new link, shown once. */
private fun MainActivity.shared(link: String, pin: Boolean) {
    page("Link ready") { sharePage() }
    text(link, 15).setTextIsSelectable(true)
    text("This is the only time the full link is shown, so send or copy it now." + if (pin) " Send the PIN separately." else "", 15, col = AC)
    button("Send link") { send(link) }
    button("Copy link") { copy("link", link) }
    button("Open it to check") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
    button("Done") { linksPage() }
}

/** Links made so far. Tap one to cancel it. */
fun MainActivity.linksPage(back: () -> Unit = { sharePage() }) {
    linksBack = back
    page("Links", back)
    val w = note("Loading...", 15)
    api("GET", "/api/shares", null) { code, o ->
        if (w.parent == null) return@api
        if (code != 200) {
            w.text = problem(code, o)
            return@api
        }
        try {
            val all = JSONObject(o).getJSONArray("shares").objects()
            w.text = if (all.isEmpty()) "No links yet." else "Tap a link to cancel it."
            for (s in all) {
                val expires = s.getString("expires")
                val old = Instant.parse(expires).isBefore(Instant.now())
                val name = s.getString("title") + (if (s.getBoolean("has_pin")) "  (PIN)" else "") + if (s.optBoolean("route_map")) "  (map)" else ""
                val about = "${s.getJSONArray("drives").length()} drives, opened ${s.getInt("opens")}×, " + if (old) "expired" else "until ${expires.take(10)}"
                button(name, about) {
                    AlertDialog.Builder(this).setMessage("Cancel this link? Nobody will be able to open it.")
                        .setPositiveButton("Cancel link") { _, _ ->
                            api("DELETE", "/api/shares/${s.optInt("id")}", null) { c, r ->
                                toast(if (c == 204) "Link cancelled" else problem(c, r))
                                linksPage(linksBack)
                            }
                        }.setNegativeButton("Keep", null).show()
                }
            }
        } catch (x: Exception) {
            w.text = x.toString()
        }
    }
}
