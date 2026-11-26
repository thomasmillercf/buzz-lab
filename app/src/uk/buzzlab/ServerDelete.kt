package uk.buzzlab

import org.json.JSONObject

/*
 * Deleting a drive on the phone deletes the server's copy too. If the server can't be reached, the id waits
 * in ui pref `delQ` (comma separated) and is sent again each time the app comes back to the front.
 */

private const val Q = "delQ"
private const val Q_SERVER = "delQUrl"

private fun MainActivity.queued(): MutableSet<String> {
    val server = ui.getString(Q_SERVER, null)
    if (server != null && server != url()) return mutableSetOf()
    return ui.getString(Q, "").orEmpty().split(",").filter { it.isNotEmpty() }.toMutableSet()
}

private fun MainActivity.setQueued(s: Set<String>) {
    ui.edit().putString(Q, s.joinToString(",")).putString(Q_SERVER, url()).commit()
}

/** True if the server holds (or may hold) a copy of this drive. */
fun MainActivity.onServer(m: JSONObject) = SyncJob.ready(this) && m.optString("syncedPos").isNotEmpty()

/** Deletes the drives from the phone and from the server; a drive the server never got just comes back 404. */
fun MainActivity.deleteDrives(ms: List<JSONObject>) {
    val ids = ms.map { it.optString("id") }
    if (SyncJob.ready(this)) setQueued(queued() + ids)
    for (id in ids) st.delDrive(id)
    sendDeletes()
}

/** Sends any deletes still waiting. Quiet: a failure just leaves them queued for next time. */
fun MainActivity.sendDeletes() {
    if (!SyncJob.ready(this)) return
    for (id in queued()) {
        api("DELETE", "/api/drives/$id", null) { code, body ->
            if (code == 204 || code == 404) {
                setQueued(queued() - id)
                SyncJob.log(this, "Deleted $id on the server")
            } else {
                SyncJob.log(this, "Delete $id on the server: ${problem(code, body)}")
            }
        }
    }
}
