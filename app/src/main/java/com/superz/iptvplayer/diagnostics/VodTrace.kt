package com.superz.iptvplayer.diagnostics

/**
 * v1.4.3 — in-memory ring buffer of the last raw VOD info API responses
 * (get_vod_info / get_series_info).
 *
 * WHY: the movie info page can end up completely empty for reasons that
 * are invisible from the app side (panel returns an unexpected shape, an
 * HTTP error page, a truncated body…). When the user reports "no info",
 * the recorded raw response is the ground truth we need. The viewer
 * (ui/vod/VodTraceScreen) shows entries with copy/share so the user can
 * send us the exact bytes the panel produced.
 *
 * Credentials in URLs are REDACTED before storage. Entries live only in
 * memory (process lifetime, capped) — nothing is persisted to disk.
 */
object VodTrace {

    data class Entry(
        val id: Long,
        val at: Long,            // epoch ms
        val action: String,      // redacted request URL
        val http: Int?,          // response code when the call completed
        val error: String?,      // transport error tag when it didn't
        val body: String?        // raw body (capped at BODY_CAP chars)
    )

    private const val MAX_ENTRIES = 24
    private const val BODY_CAP = 20_000

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var nextId = 1L

    fun record(url: String, http: Int?, error: String?, body: String?) {
        val capped = body?.let { b ->
            if (b.length > BODY_CAP) {
                b.substring(0, BODY_CAP) + "\n…[truncated ${b.length - BODY_CAP} chars]"
            } else b
        }
        synchronized(lock) {
            entries.addFirst(
                Entry(nextId++, System.currentTimeMillis(), redact(url), http, error, capped)
            )
            while (entries.size > MAX_ENTRIES) entries.removeLast()
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }

    /** Strips username/password query values from an API URL. */
    fun redact(url: String): String = url
        .replace(Regex("([?&])username=[^&]*"), "$1username=***")
        .replace(Regex("([?&])password=[^&]*"), "$1password=***")
}
