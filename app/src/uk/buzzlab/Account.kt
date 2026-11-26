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
        }
    }
}

/** Back to offline only: drives stay on the phone and nothing more is sent. */
fun MainActivity.signOut() {
    AlertDialog.Builder(this)
        .setMessage("Sign out? Drives stay on this phone and nothing more is uploaded. Drives already on the server stay there.")
        .setPositiveButton("Sign out") { _, _ ->
            val base = url()
            val tok = pref("tok")
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
            getSystemService(JobScheduler::class.java).cancel(SyncJob.ID)
            ui.edit().remove("url").remove("tok").remove("email").remove("su").remove("sp").commit()
            toast("Signed out. Offline only.")
            settings()
        }
        .setNegativeButton("Stay signed in", null)
        .show()
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
