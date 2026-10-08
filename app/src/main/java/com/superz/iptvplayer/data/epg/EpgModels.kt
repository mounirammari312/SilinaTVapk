package com.superz.iptvplayer.data.epg

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One EPG program entry (already decoded + epoch-converted).
 *
 * Xtream panels send `start`/`end` as "yyyy-MM-dd HH:mm:ss" strings; the
 * de-facto convention (XUI panels) is UTC — converted to epoch ms here and
 * rendered in the device's local timezone downstream.
 */
data class EpgProgram(
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String? = null,
    /** v1.12.0 — stalker get_simple_data_table rows: the compound program
     *  file id ("55164434_368515") that feeds the catch-up cmd
     *  "auto /media/<id>.ts". On XC rows this is the listing's own "id"
     *  (v1.12.1 — the row click gate; the play path branches on playlist
     *  type). Null on bulk rows. */
    val fileId: String? = null,
    /** v1.12.0 — the row's mark_archive flag (1 = recorded; the reference's
     *  clock icon on Catch-Up rows). v1.12.1 — XC rows: has_archive == 1
     *  (CatchDetailRecyclerAdapter's image_clock visibility). */
    val markArchive: Boolean = false,
    /** v1.12.1 — XC catch-up: the RAW panel "start" string
     *  ("yyyy-MM-dd HH:mm:ss") kept verbatim — the timeshift URL passes it
     *  back re-formatted (CatchUpEpg.getStartForUrl). Null on stalker rows. */
    val startRaw: String? = null,
    /** v1.18.0 — TimeMachine rows: the READY-MADE catch-up URL
     *  (live/…/ID.m3u8?duration=X&start=Y) built by TimeMachineEngine.
     *  Non-null rows are playable DIRECTLY (no timeshift/create_link);
     *  null on every panel-sourced row. */
    val catchupUrl: String? = null
) {
    fun isNow(nowMs: Long = System.currentTimeMillis()): Boolean =
        startMs <= nowMs && nowMs < endMs

    fun isUpcoming(nowMs: Long = System.currentTimeMillis()): Boolean = startMs >= nowMs

    /** v1.12.0 — a Catch-Up playable row: has a file id AND is archived. */
    val catchUpPlayable: Boolean get() = fileId != null && markArchive

    /** v1.18.0 — a TimeMachine playable row (its catch-up URL is ready). */
    val timeMachinePlayable: Boolean get() = catchupUrl != null
}

/**
 * Tolerant Xtream EPG JSON parsing.
 *
 * Handles ALL response shapes seen in the wild:
 *  • get_simple_data_table → {"epg_listings":[...]} (sometimes nested one
 *    level under "epg_data")
 *  • get_short_epgs → {"epg_listings_map":{"<stream_id>":{"epg_listings":[...]}}}
 *    OR a bare {"<stream_id>":[...]} map OR a flat epg_listings array
 *    (single-stream call).
 *  • Titles/descriptions are Base64-encoded UTF-8 on most panels, plain on
 *    a few — decode falls back to the raw string when Base64 fails.
 */
object EpgParser {

    private const val UTC_PATTERN = "yyyy-MM-dd HH:mm:ss"

    /** Decode a (probably) Base64 EPG text field; never throws.
     * Uses java.util.Base64 MIME decoder: available since API 26 (= our
     * minSdk) AND runs identically on the JVM for unit tests. */
    fun decodeBase64Text(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            val bytes = Base64.getMimeDecoder().decode(raw.trim())
            val decoded = String(bytes, Charsets.UTF_8)
            if (decoded.isBlank()) raw.trim() else decoded.trim()
        } catch (_: Throwable) {
            // Not valid Base64 — some panels send plain text.
            raw.trim()
        }
    }

    /** "2026-10-03 21:00:00" → epoch ms (UTC convention). 0 on failure. */
    fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            val fmt = SimpleDateFormat(UTC_PATTERN, Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(raw.trim())?.time ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    /** One epg_listings JSON object → EpgProgram (null when unusable).
     *
     *  v1.12.5 — reference-verbatim times: XCCatchUpDetailActivity buckets
     *  rows by `Long.parseLong(getStart_timestamp()) * 1000` (the TRUE
     *  epoch), NOT by parsing the "start" string — the string is the
     *  timeshift-URL source only (CatchUpEpg.getStartForUrl reformats
     *  getStart()). start_timestamp/stop_timestamp are therefore the
     *  PRIMARY times here; the string parse stays as the fallback for
     *  panels that omit the epoch fields. A missing/garbage end clamps to
     *  +30 min so the row SURVIVES (the reference's gson never drops rows;
     *  our old `end <= start → drop` erased whole days on panels that send
     *  only start_timestamp). */
    private fun parseListing(obj: JSONObject): EpgProgram? {
        val startRaw = obj.optString("start").trim()
        val tsStart = obj.optString("start_timestamp").trim().toLongOrNull()
        val tsStop = obj.optString("stop_timestamp").trim().toLongOrNull()
        val start = tsStart?.times(1000L)?.takeIf { it > 0 } ?: parseTime(startRaw)
        if (start <= 0L) return null
        val end = tsStop?.times(1000L)?.takeIf { it > 0 } ?: parseTime(obj.optString("end"))
        val title = decodeBase64Text(obj.optString("title"))
        if (title.isEmpty()) return null
        val safeEnd = if (end > start) end else start + 30 * 60 * 1000L
        val desc = decodeBase64Text(obj.optString("description")).ifBlank { null }
        // v1.12.1 — XC catch-up fields (get_simple_data_table listings):
        //  "id" (the row click key) + has_archive (the clock icon) + the raw
        //  start string (the timeshift URL source). Harmless on short-EPG
        //  rows — those consumers ignore them.
        val fileId = obj.optString("id").takeIf { it.isNotBlank() }
        val markArchive = obj.optInt("has_archive", 0) == 1
        return EpgProgram(
            start, safeEnd, title, desc,
            fileId = fileId,
            markArchive = markArchive,
            startRaw = startRaw.takeIf { it.isNotBlank() }
        )
    }

    private fun parseListingArray(arr: org.json.JSONArray?): List<EpgProgram> {
        if (arr == null) return emptyList()
        val out = ArrayList<EpgProgram>(arr.length())
        for (i in 0 until arr.length()) {
            val el = arr.opt(i) ?: continue
            if (el is JSONObject) {
                parseListing(el)?.let { out += it }
            }
        }
        return out.sortedBy { it.startMs }
    }

    /**
     * Full table for one stream (get_simple_data_table).
     * Tolerates the "epg_data" nesting some panels add.
     */
    fun parseFullResponse(body: String?): List<EpgProgram> {
        val json = body?.trim()?.takeIf { it.startsWith("{") } ?: return emptyList()
        return try {
            val root = JSONObject(json)
            val listings = root.optJSONArray("epg_listings")
                ?: root.optJSONObject("epg_data")?.optJSONArray("epg_listings")
            parseListingArray(listings)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Short EPG for many streams (get_short_epgs) → streamId → programs.
     * Handles the map form, a bare per-id array form, and the flat
     * single-stream form.
     */
    fun parseShortResponse(body: String?): Map<Long, List<EpgProgram>> {
        val json = body?.trim()?.takeIf { it.startsWith("{") } ?: return emptyMap()
        return try {
            val root = JSONObject(json)
            val out = HashMap<Long, List<EpgProgram>>()

            // Form 1: {"epg_listings_map": { "<id>": {...} | [...] }}
            val map = root.optJSONObject("epg_listings_map")
            if (map != null) {
                val keys = map.keys()
                while (keys.hasNext()) {
                    val k = keys.next() ?: continue
                    val id = k.toLongOrNull() ?: continue
                    val v = map.opt(k) ?: continue
                    val programs = when (v) {
                        is JSONObject -> parseListingArray(v.optJSONArray("epg_listings") ?: v.optJSONArray("listings"))
                        is org.json.JSONArray -> parseListingArray(v)
                        else -> emptyList()
                    }
                    if (programs.isNotEmpty()) out[id] = programs
                }
                return out
            }

            // Form 2: flat {"epg_listings":[...]} (single stream / older panels).
            // channel_id inside each listing carries the stream id.
            val listings = root.optJSONArray("epg_listings")
            if (listings != null) {
                val byChannel = HashMap<Long, MutableList<EpgProgram>>()
                for (i in 0 until listings.length()) {
                    val el = listings.opt(i) as? JSONObject ?: continue
                    val id = el.optString("channel_id").toLongOrNull() ?: continue
                    parseListing(el)?.let { byChannel.getOrPut(id) { ArrayList() }.add(it) }
                }
                for ((id, list) in byChannel) out[id] = list.sortedBy { it.startMs }
            }
            out
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    /** The program airing at `nowMs` (null when none covers the moment). */
    fun currentProgram(programs: List<EpgProgram>, nowMs: Long = System.currentTimeMillis()): EpgProgram? {
        return programs.firstOrNull { it.isNow(nowMs) }
    }
}

/** Locale-aware HH:mm display for EPG rows. */
fun Long.toEpgTimeLabel(): String {
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    return fmt.format(Date(this))
}
