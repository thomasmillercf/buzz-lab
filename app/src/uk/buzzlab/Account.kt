package uk.buzzlab

import android.app.Activity
import android.app.AlertDialog
import android.app.job.JobScheduler
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

// Signing the app in with Google, through the phone's browser. The browser hands back a one-time
// code (buzzlab://signin), which only this phone can swap for its key: it alone holds the verifier.
// Signed out, the app is offline only and nothing leaves the phone.

/** Signed in with Google rather than a typed user name and password. */
fun MainActivity.signedIn() = pref("tok").isNotEmpty()

/** On Home while offline: an invitation to sign in, unless put off with Not now. True when shown. */
fun MainActivity.signInCard(): Boolean {
    if (SyncJob.ready(this) || Scenes.SERVER.isEmpty() || System.currentTimeMillis() < ui.getLong("cardUntil", 0)) return false
    text("", 6)
    button("Sign in with Google", "You're offline. Sign in to back up your drives, see them on the web and share results.") { signIn() }
        .setTextColor(MainActivity.AC)
    quiet(
        button("Not now") {
            ui.edit().putLong("cardUntil", System.currentTimeMillis() + 14 * 86_400_000L).apply()
            toast("You can sign in any time in Settings.")
            show("home")
        },
    )
    return true
}

/** First start: sign in, or stay offline. */
fun MainActivity.welcome() {
    page("Welcome") { done(false) }
    text("Buzz Lab", 28, true)
    note("Records how your car vibrates, and plays it back so you can feel it again.", 16)
    text("", 8)
    if (Scenes.SERVER.isNotEmpty()) {
        button("Sign in with Google", "Back up drives to your server and see them on the web") { signIn() }
    } else {
        button("Set up a server", "Back up drives to your own server and see them on the web") {
            done(false)
            syncSetup()
        }
    }
    button("Skip, offline only", "Everything stays on this phone. You can sign in later in Settings.") { done(false) }
}

private fun MainActivity.done(signedIn: Boolean) {
    ui.edit().putBoolean("welcomed", true).apply()
    if (signedIn) settings() else show("home")
}

private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

/** Opens the browser at the server's app sign-in page, remembering the verifier for the way back. */
fun MainActivity.signIn() {
    val base = url().ifEmpty { Scenes.SERVER }
    if (base.isEmpty()) {
        syncSetup()
        return
    }
    val v = b64(ByteArray(32).also { SecureRandom().nextBytes(it) })
    val c = b64(MessageDigest.getInstance("SHA-256").digest(v.toByteArray()))
    ui.edit().putString("pkce", v).putString("pkceUrl", base).putBoolean("welcomed", true).apply()
    val name = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    val u = Uri.parse("$base/auth/app").buildUpon().appendQueryParameter("c", c).appendQueryParameter("name", name).build()
    // A Custom Tab: Chrome's own sign-in, with the saved Google account, shown over the app. Google refuses
    // sign-in inside a WebView. These are the Custom Tabs extras, set by hand because the app has no
    // support library; a browser without Custom Tabs ignores them and opens normally.
    val i = Intent(Intent.ACTION_VIEW, u)
        .putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
        .putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", MainActivity.BG)
        .putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
        .putExtra("androidx.browser.customtabs.extra.SHARE_STATE", 2)
    try {
        startActivity(i)
    } catch (_: Exception) {
        toast("No web browser found to sign in with.", true)
    }
}

/** The browser came back with buzzlab://signin?code=... */
fun MainActivity.finishSignIn(back: Uri) {
    val code = back.getQueryParameter("code").orEmpty()
    val v = pref("pkce")
    val base = pref("pkceUrl")
    if (code.isEmpty() || v.isEmpty() || base.isEmpty()) {
        toast("That sign-in had expired. Try again from Settings.", true)
        return
    }
    toast("Signing in…")
    thread {
        var err = ""
        var o = JSONObject()
        try {
            val h = URL("$base/auth/app/token").openConnection() as HttpURLConnection
            h.connectTimeout = 15000
            h.readTimeout = 30000
            h.instanceFollowRedirects = false
            h.requestMethod = "POST"
            h.doOutput = true
            h.setRequestProperty("Content-Type", "application/json")
            h.outputStream.use { it.write(JSONObject().put("code", code).put("verifier", v).toString().toByteArray()) }
            val ok = h.responseCode == 200
            val body = (if (ok) h.inputStream else h.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            h.disconnect()
            o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
            if (!ok) err = o.optString("error").ifEmpty { "Server said ${h.responseCode}" }
        } catch (x: Exception) {
            err = "No connection: ${SyncJob.why(x)}"
        }
        runOnUiThread {
            if (err.isNotEmpty()) {
                toast("Sign-in didn't work. ${err.replaceFirstChar { it.uppercase() }}", true)
                return@runOnUiThread
            }
            // the phone's own key replaces any typed password
            ui.edit().putString("url", base).putString("tok", o.optString("token")).putString("email", o.optString("email"))
                .remove("su").remove("sp").remove("pkce").remove("pkceUrl").putBoolean("welcomed", true).commit()
            toast("Signed in as ${o.optString("email")}", true)
            SyncJob.schedule(this)
            settings()
            offerRestore(true)
        }
    }
}

/** Offers to download drives that are on the server but not on this phone. quiet skips "nothing to do". */
fun MainActivity.offerRestore(quiet: Boolean) {
    api("GET", "/api/drives?min_mph=0&max_mph=80", null) { code, body ->
        if (code != 200) {
            if (!quiet) toast(problem(code, body), true)
            return@api
        }
        val have = st.list().map { it.optString("id") }.toSet()
        val all = runCatching { JSONObject(body).getJSONArray("drives").objects() }.getOrDefault(emptyList())
        val ids = all.filter { it.optBoolean("has_raw") && it.optString("id") !in have }.map { it.optString("id") }
        if (ids.isEmpty()) {
            if (!quiet) toast("This phone already has every drive on the server.")
            return@api
        }
        val n = if (ids.size == 1) "1 drive is" else "${ids.size} drives are"
        AlertDialog.Builder(this)
            .setMessage("$n on the server but not on this phone. Download them? Readings, routes and marks all come back.")
            .setPositiveButton("Download") { _, _ -> restoreAll(ids) }
            .setNegativeButton("Not now", null)
            .show()
    }
}

private fun MainActivity.restoreAll(ids: List<String>) {
    page("Download drives") { settings() }
    status = text("Starting…", 15)
    val base = url()
    val auth = SyncJob.auth(ui)
    thread {
        var ok = 0
        var why = ""
        for ((i, id) in ids.withIndex()) {
            say("Downloading ${i + 1} of ${ids.size}…")
            try {
                val h = URL("$base/api/drives/$id/raw.zip").openConnection() as HttpURLConnection
                h.connectTimeout = 15000
                h.readTimeout = 120000
                h.instanceFollowRedirects = false
                h.setRequestProperty("Authorization", auth)
                if (h.responseCode != 200) throw IOException("server said ${h.responseCode}")
                h.inputStream.use { Restore.drive(st, it, base) }
                h.disconnect()
                ok++
            } catch (x: Exception) {
                why = SyncJob.why(x)
                SyncJob.log(this, "restore $id: $why")
            }
        }
        val left = ids.size - ok
        say(if (left == 0) "Done. $ok downloaded." else "$ok downloaded, $left didn't: $why. Try again from Settings.")
        runOnUiThread { toast(if (left == 0) "Drives downloaded" else "$left drives didn't download", left > 0) }
    }
}

/** Back to offline only. Asks whether to keep this phone's drives, or delete them too. Copies on the server stay. */
fun MainActivity.signOut() {
    val n = st.list().size
    val notUp = st.list().count { !it.optBoolean("recording") && it.optString("syncedPos") != url() }
    val b = AlertDialog.Builder(this)
        .setTitle("Sign out")
        .setMessage(
            "Nothing more is uploaded after this. Drives already on the server stay there." +
                if (n > 0) "\n\nKeep the $n ${if (n == 1) "drive" else "drives"} on this phone, or delete ${if (n == 1) "it" else "them"}?" else "",
        )
        .setPositiveButton(if (n > 0) "Keep on phone" else "Sign out") { _, _ -> endSession(false) }
        .setNegativeButton("Cancel", null)
    if (n > 0) b.setNeutralButton("Delete from phone") { _, _ -> confirmWipe(n, notUp) }
    b.show()
}

private fun MainActivity.confirmWipe(n: Int, notUp: Int) {
    if (RecService.live != null) {
        toast("A drive is recording. Stop it first, then sign out.", true)
        return
    }
    val lost = if (notUp >
        0
    ) {
        " $notUp of them ${if (notUp == 1) "isn't" else "aren't"} on the server and will be lost for good."
    } else {
        " They're all on the server."
    }
    AlertDialog.Builder(this)
        .setMessage("Delete all $n drives and your cars from this phone?$lost This can't be undone. Calibration is kept.")
        .setPositiveButton("Delete and sign out") { _, _ -> endSession(true) }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun MainActivity.endSession(wipe: Boolean) {
    val base = url()
    val tok = pref("tok")
    if (tok.isNotEmpty()) {
        thread {
            runCatching {
                val h = URL("$base/auth/app/signout").openConnection() as HttpURLConnection
                h.connectTimeout = 10000
                h.readTimeout = 10000
                h.requestMethod = "POST"
                h.setRequestProperty("Authorization", "Bearer $tok")
                h.responseCode
                h.disconnect()
            }
        }
    }
    getSystemService(JobScheduler::class.java).cancel(SyncJob.ID)
    ui.edit().remove("url").remove("tok").remove("email").remove("su").remove("sp").commit()
    if (wipe) {
        for (m in st.list()) st.delDrive(m.optString("id"))
        for (c in st.cars().objects()) st.delCar(c.optString("id"))
        toast("Signed out. Drives and cars deleted from this phone.", true)
        show("home")
    } else {
        toast("Signed out. Offline only.")
        settings()
    }
}

/**
 * Where the browser lands with buzzlab://signin. It hands the code to the app already open, closing the
 * sign-in tab that sits above it, instead of starting a second copy on top.
 */
class SignInReturn : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        startActivity(
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(intent?.data)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
