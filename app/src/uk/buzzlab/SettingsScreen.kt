package uk.buzzlab

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import org.json.JSONObject
import uk.buzzlab.MainActivity.Companion.AC
import uk.buzzlab.MainActivity.Companion.FG
import uk.buzzlab.MainActivity.Companion.MU
import uk.buzzlab.MainActivity.Companion.ON

fun MainActivity.settings() {
    page("Settings") { show("home") }
    val server = SyncJob.ready(this)
    val cs = cars()
    section("Cars")
    if (cs.isEmpty()) button("Add a car") { editCar(null) } else button("Cars and tyre sizes", cs.joinToString(", ") { it.optString("name") }) { carsPage() }

    section("Server")
    button("Sync", syncLine().replaceFirst(Regex("^\\S+\\s+"), "")) { syncPage { settings() } }
    when {
        signedIn() -> note("Signed in as ${pref("email")}", 15)
        server -> button("Server settings", url()) { syncSetup() }
        Scenes.SERVER.isNotEmpty() -> button("Sign in with Google", "Offline only now. Sign in to back up drives and see them on the web.") { signIn() }
        else -> button("Server settings", "Not set up. Drives stay on this phone.") { syncSetup() }
    }
    if (!signedIn() && server && Scenes.SERVER.isNotEmpty()) button("Sign in with Google instead", "Use your Google account instead of a password") { signIn() }
    if (server) button("Download drives from the server", "Any that aren't on this phone, for a new phone or after deleting") { offerRestore(false) }
    if (server) quiet(button("Sign out") { signOut() })
    if (server) {
        section("Sharing")
        button("Share results", "Make a link to drives you choose") { sharePage(null, null, null) { settings() } }
        button("Links already made", "See or cancel them") { linksPage { settings() } }
    }

    section("Your data")
    button("Full export", "Everything on this phone, including positions and plates") { fullExport() }
    if (cs.isNotEmpty()) button("Import a phyphox zip") { pickImport() }

    section("Recording")
    val auto = ui.getBoolean("auto", false)
    val ab = button(
        "Record automatically: ${if (auto) "on" else "off"}",
        if (auto) {
            "Watching with GPS. Tap to turn off."
        } else {
            "Starts when Android Auto connects, or once you're above ${RecService.START_MPH.toInt()} mph. Saves when Android Auto disconnects, " +
                "or after ${RecService.STOP_AFTER_MS / 60000} minutes stopped. Uses the car and phone position on Home."
        },
    ) { autoToggle() }
    if (auto) ab.background = box(ON)
    if (auto) {
        val wanted = ui.getBoolean("autoBoot", false)
        val on = wanted && has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val why = when {
            on -> "Also after app updates. Tap to turn off."
            wanted -> "Needs location set to Allow all the time. Tap to set it."
            else -> "Otherwise open the app once after the phone restarts."
        }
        val bb = button("Start again after a restart: ${if (on) "on" else "off"}", why) { bootToggle() }
        if (on) bb.background = box(ON)
    }
    if (auto || RecService.log(this).isNotEmpty()) {
        button("Automatic recording log", "When it started and saved drives, and why") {
            AlertDialog.Builder(this).setTitle("Automatic recording").setMessage(RecService.log(this).ifEmpty { "Nothing yet." })
                .setPositiveButton("Close", null).show()
        }
    }
    val free = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    val bt = button(
        "Battery: ${if (free) "unrestricted" else "may be restricted"}",
        if (free) "Android won't stop recording or watching to save power." else "Android may stop long recordings or watching. Tap to allow it to run.",
    ) { battery() }
    if (!free && auto) bt.setTextColor(AC)

    section("Feel")
    button("Calibrate", if (sp.contains("at")) "Last done ${date(sp.getLong("at", 0))}" else "Needed once, about 5 minutes") {
        show("feel")
        askCalibrate()
    }

    section("App")
    notificationsButton()
    if (server) button("Get the latest version") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url() + "/app.apk"))) }
    note("Buzz Lab ${version()}")
}

private fun MainActivity.section(s: String) = text(s, 14, true, MU).apply { isAccessibilityHeading = true }

private fun MainActivity.autoToggle() {
    if (ui.getBoolean("auto", false)) {
        ui.edit().putBoolean("auto", false).apply()
        if (RecService.svc != null) startService(Intent(this, RecService::class.java).setAction(RecService.UNWATCH))
        toast("Automatic recording off")
        settings()
        return
    }
    if (cars().isEmpty()) {
        toast("Add a car first.", true)
        return
    }
    val msg = "Buzz Lab will record when Android Auto connects, or when you drive above ${RecService.START_MPH.toInt()} mph, as " +
        "${carName(pref("car"))}, ${place()}. A notification stays while it's watching, and it uses some battery. " +
        "With Android Auto it saves the drive within half a minute of the car turning off. After the phone restarts, open the app once to start watching again."
    AlertDialog.Builder(this).setTitle("Record automatically?").setMessage(msg).setPositiveButton("Turn on") { _, _ ->
        val need = ArrayList<String>()
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) need += listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) need += Manifest.permission.POST_NOTIFICATIONS
        ui.edit().putBoolean("auto", true).apply()
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), MainActivity.ASK_AUTO)
        } else {
            startWatching()
            settings()
        }
    }.setNegativeButton("Cancel", null).show()
}

/** Asks Android to let the app run unrestricted, or opens its settings if it already is. */
private fun MainActivity.battery() {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
        runCatching { startActivity(details) }
        return
    }
    try {
        @Suppress("BatteryLife")
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    } catch (_: Exception) {
        runCatching {
            startActivity(details)
            toast("Open Battery and choose Unrestricted.", true)
        }
    }
}

private fun MainActivity.bootToggle() {
    val bg = has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    if (ui.getBoolean("autoBoot", false) && bg) {
        ui.edit().putBoolean("autoBoot", false).apply()
        settings()
        return
    }
    ui.edit().putBoolean("autoBoot", true).apply()
    if (bg) {
        settings()
        return
    }
    val msg = "To start watching by itself after a restart, Android needs location for Buzz Lab set to \"Allow all the time\". " +
        "It's used only to watch for a drive while automatic recording is on. On the next screen, choose Allow all the time."
    AlertDialog.Builder(this).setTitle("Allow all the time?").setMessage(msg)
        .setPositiveButton("Continue") { _, _ -> requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), MainActivity.ASK_BACKGROUND) }
        .setNegativeButton("Cancel") { _, _ ->
            ui.edit().putBoolean("autoBoot", false).apply()
            settings()
        }.show()
}

/** Watch for drives if automatic recording is on and allowed. Does nothing if already watching. */
fun MainActivity.startWatching() {
    if (!ui.getBoolean("auto", false) || !has(Manifest.permission.ACCESS_FINE_LOCATION)) return
    if (RecService.svc?.watch == true) return
    try {
        startForegroundService(Intent(this, RecService::class.java).setAction(RecService.WATCH))
    } catch (e: Exception) {
        toast("Couldn't start watching: ${e.message}", true)
    }
}

private fun MainActivity.carsPage() {
    page("Cars") { settings() }
    for (c in cars()) button(c.optString("name"), c.optString("tyre").ifEmpty { "No tyre size" }) { editCar(c) }
    button("Add a car") { editCar(null) }
}

fun MainActivity.editCar(c0: JSONObject?) {
    val back: () -> Unit = if (cars().isEmpty()) ({ show("home") }) else ({ carsPage() })
    page(if (c0 == null) "Add a car" else "Edit car", back)
    if (c0 != null) {
        overflow(
            mapOf(
                "Delete" to {
                    ask("Delete ${c0.optString("name")}? Its drives are kept.") {
                        runCatching { st.delCar(c0.optString("id")) }
                        carsPage()
                    }
                },
            ),
        )
    }
    val n = field("Name, such as ${Scenes.CAR_EXAMPLE}", c0?.optString("name").orEmpty())
    val reg = field("Reg plate (kept on this phone, left out of exports)", c0?.optString("reg").orEmpty())
    reg.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
    note("Tyre size, read it off the sidewall", 15)
    val t = AutoCompleteTextView(this).apply {
        setAdapter(ArrayAdapter(this@editCar, android.R.layout.simple_dropdown_item_1line, Scenes.TYRES))
        threshold = 1
        setText(c0?.optString("tyre").orEmpty())
        setTextColor(FG)
        hint = Scenes.TYRES[0]
        setHintTextColor(MU)
    }
    list.addView(t)
    val commonSizeButtons = toggles(Scenes.TYRES.asList())
    val noneChosen = -1
    choose(commonSizeButtons, noneChosen)
    commonSizeButtons.forEach { sizeButton ->
        sizeButton.setOnClickListener {
            tap(sizeButton)
            t.setText(sizeButton.text)
            t.dismissDropDown()
        }
    }
    note("${Scenes.TYRE_NOTE} Looking sizes up from a reg plate needs a paid data service, so it isn't connected.")
    status = text("", 15)
    primary(
        button("Save") {
            val name = n.text.toString().trim()
            val tyre = t.text.toString().trim()
            if (name.isEmpty()) {
                say("Give the car a name.")
                return@button
            }
            if (tyre.isNotEmpty() && Store.circ(tyre) == 0.0) {
                say("Write the tyre size like ${Scenes.TYRES[0]}.")
                return@button
            }
            try {
                val c = c0 ?: JSONObject().put("id", "c${System.currentTimeMillis()}")
                c.put("name", name).put("reg", reg.text.toString().trim()).put("tyre", tyre)
                st.putCar(c)
                ui.edit().putString("car", c.getString("id")).apply()
                carsPage()
            } catch (x: Exception) {
                say(x.toString())
            }
        },
    )
}
