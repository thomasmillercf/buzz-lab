package uk.buzzlab

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Starts watching again after a phone restart or an app update, when the user has turned that on.
 * Android lets a location service start from these two events only with location set to "Allow all the time".
 */
class Boot : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val ui = c.getSharedPreferences("ui", 0)
        if (!ui.getBoolean("auto", false) || !ui.getBoolean("autoBoot", false)) return
        if (c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.i("BUZZLAB", "AUTO not restarted: needs Allow all the time")
            return
        }
        try {
            c.startForegroundService(Intent(c, RecService::class.java).setAction(RecService.WATCH))
            Log.i("BUZZLAB", "AUTO restarted after ${i.action}")
        } catch (e: Exception) {
            Log.w("BUZZLAB", "AUTO restart refused: $e")
        }
    }
}
