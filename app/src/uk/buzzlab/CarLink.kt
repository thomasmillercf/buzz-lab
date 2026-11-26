package uk.buzzlab

import android.content.Context
import android.net.Uri

/**
 * Whether Android Auto (or the car's own Android) is connected, read the way the androidx car app library does,
 * without the library: Android Auto answers a query on its connection provider and broadcasts ACTION when it changes.
 */
object CarLink {
    const val ACTION = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
    private const val URI = "content://androidx.car.app.connection"
    private const val COLUMN = "CarConnectionState"

    /** 0 not connected, 1 the car's own Android, 2 Android Auto from the phone. False if Android Auto isn't installed. */
    fun connected(c: Context): Boolean = runCatching {
        c.contentResolver.query(Uri.parse(URI), arrayOf(COLUMN), null, null, null)?.use { it.moveToFirst() && it.getInt(0) != 0 } ?: false
    }.getOrDefault(false)
}

/**
 * When automatic recording starts and saves around Android Auto. No Android code, so it's tested off the phone.
 * A disconnect only counts once it has lasted [graceMs], so a loose cable or a wireless blip doesn't end a drive.
 */
class CarRule(private val graceMs: Long = 20_000, private val movedMph: Double = 5.0) {
    enum class Act { NONE, START, CONNECTED, SAVE, DISCARD, DISCONNECTED }

    /** Android Auto is connected, as far as recording is concerned. While it is, a long stop doesn't end the drive. */
    var car = false
        private set
    private var lostAt = 0L

    /** How long until a disconnect seen now would count, so the caller can check again then. */
    val grace get() = graceMs

    /**
     * One check. [watching] is automatic recording being on; [recording] a drive in progress; [autoDrive] that drive
     * started by itself; [topMph] its top speed so far.
     */
    fun step(now: Long, connected: Boolean, watching: Boolean, recording: Boolean, autoDrive: Boolean, topMph: Double): Act {
        if (connected) {
            lostAt = 0
            if (car) return Act.NONE
            car = true
            return if (watching && !recording) Act.START else Act.CONNECTED
        }
        if (!car) return Act.NONE
        if (lostAt == 0L) lostAt = now
        if (now - lostAt < graceMs) return Act.NONE
        car = false
        lostAt = 0
        return when {
            !recording || !autoDrive -> Act.DISCONNECTED
            topMph < movedMph -> Act.DISCARD
            else -> Act.SAVE
        }
    }
}
