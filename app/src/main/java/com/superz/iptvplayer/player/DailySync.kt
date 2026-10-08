package com.superz.iptvplayer.player

import android.util.Log
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.data.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v1.12.4 — the reference app's DAILY channel re-sync, replicated from
 * BaseActivity.getAllChannel's gate (classes7.dex):
 *
 * ```java
 * if (is_edit || liveChannels.size() == 0 ||
 *     !today.equals(Utils.getDate("yyyy-MM-dd", LastPlaylistDate))) {
 *     // full get_live_channels re-download + setLiveChannelsToRealm
 * }
 * ```
 *
 * The reference runs this at every activity start for the CURRENT portal;
 * the net effect is "re-sync the live channel list once per day, at app
 * start". That daily refresh is what keeps its Realm rows (tv_archive flags,
 * category links, logos) perpetually fresh — its Catch-Up screen then just
 * READS the local DB (CatchUpActivity.getCatchUpCategoryModels queries
 * Realm synchronously in onCreate; there is no "loading" state at all).
 *
 * Our equivalent trigger: IPTVApp.onCreate → [launch] (once per process),
 * for the ACTIVE playlist, when its last successful sync wasn't TODAY.
 * PORTAL + XTREAM only (M3U/BROWSER file playlists have no flags to
 * refresh; the reference's gate also only guards portal/panel logins).
 *
 * Failures are silent (best-effort, exactly like the reference — its
 * onFailed_count retry never surfaces in the UI either): the next app start
 * retries, and the manual loading-screen sync remains the user-visible path.
 */
object DailySync {

    private const val TAG = "DailySync"

    /**
     * The gate, verbatim semantics: "the local yyyy-MM-dd of the last
     * successful sync differs from today's". Never synced ⇒ run.
     * Timezone-explicit so the unit tests pin both sides of midnight.
     */
    fun shouldSync(lastSyncAtEpochSec: Long, nowMs: Long, zone: TimeZone): Boolean {
        if (lastSyncAtEpochSec <= 0) return true
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = zone
        val last = fmt.format(Date(lastSyncAtEpochSec * 1000L))
        val today = fmt.format(Date(nowMs))
        return last != today
    }

    /** Fire-and-forget launcher (application scope — survives everything). */
    fun launch(app: IPTVApp) {
        app.applicationScope.launch(Dispatchers.IO) {
            try {
                val pl = app.database.playlistDao().activePlaylist() ?: return@launch
                if (pl.type != "PORTAL" && pl.type != "XTREAM") return@launch
                if (!shouldSync(pl.lastSyncAt, System.currentTimeMillis(), TimeZone.getDefault())) {
                    return@launch
                }
                Log.i(TAG, "daily re-sync due for '${pl.name}' (${pl.type}) — refreshing channel list")
                com.superz.iptvplayer.diagnostics.CrashDiagnostics.breadcrumb("daily-sync start ${pl.type}")
                val count = PlaylistRepository.get(app).syncPlaylist(pl)
                com.superz.iptvplayer.diagnostics.CrashDiagnostics.breadcrumb("daily-sync done channels=$count")
                Log.i(TAG, "daily re-sync done: $count channels")
            } catch (t: Throwable) {
                // Best-effort, reference-verbatim: silent. The loading screen
                // remains the user-visible sync (and error) path.
                Log.w(TAG, "daily re-sync failed (${t.message}) — will retry next start")
                com.superz.iptvplayer.diagnostics.CrashDiagnostics.breadcrumb("daily-sync fail ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }
}
