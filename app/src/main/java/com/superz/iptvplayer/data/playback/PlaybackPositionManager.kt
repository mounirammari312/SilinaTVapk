package com.superz.iptvplayer.data.playback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v1.18.2 — PlaybackPositionManager: RESUME WATCHING.
 *
 *  The reference app's com.agon.app.data.PlaybackPositionManager logic
 *  (PlayerViewModel's binge-watching trio: startAutoResume /
 *  startPositionSaveLoop / savePositionNow — save every 5s while
 *  playing, restore on re-open, MIN_RESTORE_POSITION_MS = 5s,
 *  live streams skipped via the duration window), carried over with
 *  ONE deliberate adaptation: the store key.
 *
 *  The reference keys by STREAM URL (its streams ARE urls); Oria's VOD
 *  identity is the STABLE synthetic channel key:
 *    • movies      "vodm:{streamId}"
 *    • episodes    "vode:{episodeId}"
 *    • saved files "local:{fileName}"     (file name, NOT the
 *                  "vodm:local:{i}" grid index — indexes shift when
 *                  saves are added/deleted, names never do)
 *
 *  Because the record must ALSO feed the Continue-Watching row (title,
 *  poster, duration, kind, navigation ids), it stores a full record —
 *  not just the position number — in a small JSON file inside the app's
 *  private storage. File-based (like SavedLibrary) instead of the
 *  reference's DataStore: one read enumerates the whole shelf.
 *
 *  Pure java.io.File API only → plain-JVM unit tests, no Android types
 *  in the store core (the Context adapter is one function).
 * ═══════════════════════════════════════════════════════════════════
 */
object PlaybackPositionManager {

    /** Content kinds (drives the Continue-Watching card's click route). */
    const val KIND_MOVIE = "MOVIE"
    const val KIND_EPISODE = "EPISODE"
    const val KIND_LOCAL = "LOCAL"

    /**
     * Min saved position (ms) worth restoring — the reference's
     * MIN_RESTORE_POSITION_MS (a 3-second blip is a fresh start).
     */
    const val MIN_RESTORE_POSITION_MS = 5_000L

    /** Min duration (ms) for content to be considered VOD — reference value. */
    const val MIN_VOD_DURATION_MS = 60_000L

    /** Upper bound (ms) for VOD duration. Live streams report 24h+ — reference value. */
    const val MAX_VOD_DURATION_MS = 86_400_000L

    /**
     * Watching past this fraction of the duration counts as FINISHED:
     * the record is cleared (the reference's ClearPosition event when
     * the next episode auto-plays — a finished movie replays from 0
     * and leaves the Continue-Watching shelf).
     */
    const val WATCHED_CLEAR_FRACTION = 0.95

    /** Shelf size — the newest N watched items keep a resume point. */
    const val MAX_RECORDS = 30

    /**
     * One resumable item. Everything the Continue-Watching row needs to
     * re-open the content AND re-seek the player:
     *  • [key]      — the player-side identity (see class doc)
     *  • [kind]     — MOVIE / EPISODE / LOCAL (click routing)
     *  • [contentId]— streamId (movies) / episodeId (episodes)
     *  • [seriesId] — the owning series (episodes → series info page)
     *  • [path]     — the saved file's absolute path (LOCAL only)
     *  • [posterUrl]— movie poster / episode thumbnail (may be null)
     */
    data class PositionRecord(
        val key: String,
        val playlistId: Long,
        val title: String,
        val posterUrl: String?,
        val kind: String,
        val contentId: Long,
        val seriesId: Long?,
        val path: String?,
        val positionMs: Long,
        val durationMs: Long,
        val updatedAtMs: Long
    )

    // ── Context adapter ──────────────────────────────────────────

    /** The app-private store file: filesDir/playback_positions.json. */
    fun storeFile(context: Context): File =
        File(context.filesDir, "playback_positions.json")

    // ── Store API (pure File — JVM testable) ─────────────────────

    /**
     * Upserts one record and trims the shelf to [MAX_RECORDS] (newest
     * by updatedAt). Atomic: the new JSON is written to a temp file
     * then renamed over the store, so a crash mid-write never corrupts
     * the previous state.
     */
    @Synchronized
    fun save(file: File, record: PositionRecord) {
        val records = loadMap(file).toMutableMap()
        // keep the caller's explicit zero-durations honest: a record with
        // no measured duration cannot render a progress bar or be safely
        // restored — keep the old duration when the new one is unset.
        val previous = records[record.key]
        val merged = if (previous != null && record.durationMs <= 0L) {
            record.copy(durationMs = previous.durationMs)
        } else record
        records[record.key] = merged
        trimAndWrite(file, records)
    }

    /** The saved position for a key (0 when never watched / cleared). */
    @Synchronized
    fun position(file: File, key: String): Long =
        loadMap(file)[key]?.positionMs ?: 0L

    /**
     * v1.19.12 — the FULL record for a key (null when never watched /
     * cleared). The open-time resume bake needs the stored DURATION too
     * (the watched-fraction verdict: a stop point at >= 95% of the old
     * runtime means the movie was finished — open fresh, not parked at
     * the credits), which a bare position number cannot express.
     */
    @Synchronized
    fun record(file: File, key: String): PositionRecord? =
        loadMap(file)[key]

    /** Drops one record (finished watching / user cleared it). */
    @Synchronized
    fun clear(file: File, key: String) {
        val records = loadMap(file).toMutableMap()
        if (records.remove(key) != null) trimAndWrite(file, records)
    }

    /**
     * The Continue-Watching shelf: newest-first, only records with a
     * meaningful in-progress position (>= MIN_RESTORE_POSITION_MS and
     * not past the watched fraction).
     */
    @Synchronized
    fun recent(file: File, limit: Int = MAX_RECORDS): List<PositionRecord> =
        loadMap(file).values
            .filter { it.positionMs >= MIN_RESTORE_POSITION_MS }
            .filter { it.durationMs <= 0L || it.positionMs < (it.durationMs * WATCHED_CLEAR_FRACTION).toLong() }
            .sortedByDescending { it.updatedAtMs }
            .take(limit)

    // ── (De)serialization ────────────────────────────────────────

    private fun loadMap(file: File): Map<String, PositionRecord> {
        if (!file.exists()) return emptyMap()
        return try {
            val text = file.readText()
            if (text.isBlank()) return emptyMap()
            val arr = JSONArray(text)
            val out = LinkedHashMap<String, PositionRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val r = fromJson(o) ?: continue
                out[r.key] = r
            }
            out
        } catch (_: Throwable) {
            // corrupt/foreign file — start the shelf clean rather than crash
            emptyMap()
        }
    }

    private fun trimAndWrite(file: File, records: Map<String, PositionRecord>) {
        val kept = records.values
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_RECORDS)
        try {
            val arr = JSONArray()
            kept.forEach { arr.put(toJson(it)) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { fos ->
                fos.write(arr.toString().toByteArray(Charsets.UTF_8))
                fos.flush()
            }
            if (!tmp.renameTo(file)) {
                // rename can fail across odd mounts — fall back to a copy
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (_: Throwable) {
            // a failed shelf write must NEVER take playback down
        }
    }

    private fun toJson(r: PositionRecord): JSONObject = JSONObject()
        .put("key", r.key)
        .put("pl", r.playlistId)
        .put("title", r.title)
        .put("poster", r.posterUrl ?: "")
        .put("kind", r.kind)
        .put("cid", r.contentId)
        .put("sid", r.seriesId ?: -1L)
        .put("path", r.path ?: "")
        .put("pos", r.positionMs)
        .put("dur", r.durationMs)
        .put("ts", r.updatedAtMs)

    private fun fromJson(o: JSONObject): PositionRecord? {
        val key = o.optString("key")
        if (key.isBlank()) return null
        val sid = o.optLong("sid", -1L)
        return PositionRecord(
            key = key,
            playlistId = o.optLong("pl", -1L),
            title = o.optString("title", "").ifBlank { key },
            posterUrl = o.optString("poster", "").ifBlank { null },
            kind = o.optString("kind", KIND_MOVIE),
            contentId = o.optLong("cid", 0L),
            seriesId = if (sid > 0L) sid else null,
            path = o.optString("path", "").ifBlank { null },
            positionMs = o.optLong("pos", 0L),
            durationMs = o.optLong("dur", 0L),
            updatedAtMs = o.optLong("ts", 0L)
        )
    }
}
