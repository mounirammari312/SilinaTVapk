package com.superz.iptvplayer.data.xtream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────
// Xtream VOD (movies + series) wire models.
// Same defensive conventions as XtreamStream: numeric fields arrive
// as strings on many panels (lenient Json handles that), and fields
// that panels sometimes send as NUMBERS (rating) are deliberately
// OMITTED at list level — they are parsed tolerantly by
// [VodInfoParser] on the info endpoints instead.
// ─────────────────────────────────────────────────────────────────

@Serializable
data class XtreamVodStream(
    val num: Int? = null,
    val name: String = "",
    @SerialName("stream_id") val streamId: Long = 0L,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null
)

@Serializable
data class XtreamSeries(
    val num: Int? = null,
    val name: String = "",
    @SerialName("series_id") val seriesId: Long = 0L,
    @SerialName("cover") val cover: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("plot") val plot: String? = null
)

// ─────────────────────────────────────────────────────────────────
// Parsed info models (get_vod_info / get_series_info).
// ─────────────────────────────────────────────────────────────────

data class VodMovieInfo(
    val name: String?,
    val poster: String?,
    val plot: String?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val releaseDate: String?,
    val duration: String?,
    val rating: String?,
    val streamId: Long?,
    val containerExtension: String?,
    /** v1.4.5 — reference-app parity (VU IPTV): the panel's TMDB id for
     *  this movie; drives the TMDB /movie/{id}/credits call that fills
     *  the cast row with real headshots ("tmdb_id" inside "info"). */
    val tmdbId: String? = null,
    /** v1.18.0 — the panel's imdb_id for this movie ("imdb_id" inside
     *  "info"/"movie_data", when the panel knows it): passed straight to
     *  the subtitle engine so the search can NEVER resolve the wrong movie
     *  (SubtitleRepository layer 1). Null on panels that don't send it. */
    val imdbId: String? = null,
    /** v1.11.0 — PORTAL (stalker) movies: the row's `cmd` — the create_link
     *  input at play time (MoviePlayerActivity.getMovieUrl). Null on XC. */
    val stalkerCmd: String? = null,
    /** v1.11.0 — PORTAL HTML-mode movies: "movieId|categoryId" for the
     *  single-item file-id lookup (MoviePlayerActivity.getVodItem). Null
     *  on Ministra portals and XC rows. */
    val stalkerItem: String? = null
)

data class VodEpisode(
    val id: Long,
    val season: Int,
    val episodeNumber: Int,
    val title: String,
    val containerExtension: String?,
    val plot: String?,
    val duration: String?,
    val thumbnail: String?,
    /** v1.4.4 — panel-provided absolute playback URL ("direct_source").
     *  Empty/absent on most panels; when present it wins over the
     *  synthesized /series/… path. */
    val directSource: String? = null,
    /** v1.11.0 — PORTAL (stalker) episodes: the SEASON's `cmd` (Ministra)
     *  — create_link's input, paired with [stalkerSeriesNum]. */
    val stalkerCmd: String? = null,
    /** v1.11.0 — the episode's series number (the `series` param of
     *  get_series_cmd / the number inside season.series[]). */
    val stalkerSeriesNum: Int? = null,
    /** v1.11.0 — HTML-mode episodes: "movieId|seasonId|episodeId|category"
     *  for the single-item file-id lookup (SeriesPlayActivity.
     *  getEpisodeItem). Null on Ministra portals and XC rows. */
    val stalkerItem: String? = null
)

data class VodSeason(
    val seasonNumber: Int,
    val episodes: List<VodEpisode>,
    /** v1.4.4 — season artwork from "seasons":[{cover, cover_big}] —
     *  used as the episode-card thumbnail fallback on panels that send
     *  no per-episode stills (admagnun.net does exactly this). */
    val cover: String? = null
)

data class VodSeriesInfo(
    val name: String?,
    val poster: String?,
    val plot: String?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val releaseDate: String?,
    val rating: String?,
    val seasons: List<VodSeason>,
    /** v1.4.5 — reference-app parity: "tmdb_id" inside "info" when the
     *  panel knows the TMDB series id; drives /tv/{id}/credits cast
     *  photos (null on most panels → Wikipedia fallback stays). */
    val tmdbId: String? = null,
    /** v1.18.0 — the panel's imdb_id for the series (rarely present; used
     *  by the subtitle engine's exact-match layer when it is). */
    val imdbId: String? = null
)

/**
 * Tolerant parser for the get_vod_info / get_series_info responses.
 *
 * Panel variance handled:
 *  • movie info nested under "info" (+ optional "movie_data"/"data") — or flat
 *  • v1.4.3 — EVERY metadata field is read from BOTH "info" and
 *    "movie_data" (whichever carries a value wins) — some XUI variants
 *    split fields across the two objects, which previously left the
 *    movie page completely empty.
 *  • v1.4.3 — "info" arriving as a JSON ARRAY (array-of-one) is unwrapped
 *  • v1.4.3 — root JSON arrays pick the element carrying info/seasons
 *  • v1.4.3 — cast/genre arriving as a JSON ARRAY is joined with ", "
 *  • v1.4.3 — last-resort REGEX extraction for responses that are not
 *    parseable JSON at all (unquoted keys, trailing commas, cut-off
 *    bodies): quoted-key patterns per known field name
 *  • poster under movie_image / cover_big / cover / stream_icon
 *  • releaseDate under releasedate / releaseDate / year (camel AND snake)
 *  • duration as "1h 30m" string OR duration_secs number
 *  • rating as number or string
 *  • series seasons as "seasons":[{season_number, episodes:[…]}]
 *    AND/OR "episodes":{"1":[…], "2":[…]} — merged + deduped by episode id
 *  • episode title/num/season arriving as strings or numbers
 *  • any broken piece is skipped, never throws (silent-degradation contract
 *    shared with the EPG parser)
 */
object VodInfoParser {

    /** Returns null only when the response carries no recoverable data. */
    fun parseMovieInfo(raw: String): VodMovieInfo? {
        val root = raw.toJsonObject()
        if (root != null) {
            val info = root.optObjectOrFirst("info") ?: root
            val movieData = root.optObjectOrFirst("movie_data")
                ?: root.optObjectOrFirst("data")
            val parsed = VodMovieInfo(
                name = movieData?.str("name") ?: info.str("name"),
                poster = info.str("movie_image", "cover_big", "cover", "stream_icon")
                    ?: movieData?.str("movie_image", "cover_big", "cover", "stream_icon"),
                // v1.4.5 — reference parity: VU reads info.description for
                // movies (XUI fills description; plot is the fallback).
                plot = info.str("description", "plot")
                    ?: movieData?.str("description", "plot"),
                genre = info.str("genre") ?: movieData?.str("genre"),
                director = info.str("director", "directors") ?: movieData?.str("director", "directors"),
                cast = info.str("cast", "actors") ?: movieData?.str("cast", "actors"),
                releaseDate = info.str("releasedate", "releaseDate", "year")
                    ?: movieData?.str("releasedate", "releaseDate", "year"),
                duration = info.duration() ?: movieData?.duration(),
                rating = info.str("rating", "rating_5based") ?: movieData?.str("rating", "rating_5based"),
                streamId = movieData?.lng("stream_id") ?: info.lng("stream_id"),
                containerExtension = movieData?.str("container_extension")
                    ?: info.str("container_extension"),
                // first ACCEPTABLE value wins (Elvis alone would pick a
                // "0" from info and never reach movie_data's real id)
                tmdbId = firstUsableTmdbId(info.str("tmdb_id"), movieData?.str("tmdb_id")),
                imdbId = firstUsableTmdbId(info.str("imdb_id"), movieData?.str("imdb_id"))
            )
            if (parsed.hasAnyData()) return parsed
        }
        // v1.4.3 — last resort: the body wasn't parseable JSON (or carried
        // nothing through the object path). Pull fields with regexes so a
        // panel with broken-but-present data still fills the info page.
        return regexMovieInfo(raw)
    }

    private fun VodMovieInfo.hasAnyData(): Boolean =
        name != null || poster != null || plot != null || genre != null ||
            director != null || cast != null || releaseDate != null ||
            duration != null || rating != null || streamId != null

    /** Returns null only when the response carries no recoverable data. */
    fun parseSeriesInfo(raw: String): VodSeriesInfo? {
        val root = raw.toJsonObject()
        if (root != null) {
            // v1.4.2 — flat-root fallback: some panels send the series metadata
            // at the TOP level (no "info" wrapper) next to "episodes"/"seasons".
            val info = root.optObjectOrFirst("info") ?: root

        // Episodes arrive in two shapes depending on the panel — merge both.
        // The modern "seasons" array is parsed FIRST so it wins episode-id
        // collisions; the legacy "episodes" map only fills what's missing.
        val bySeason = HashMap<Int, LinkedHashMap<Long, VodEpisode>>()

        // Shape B (modern): "seasons": [ { "season_number": 1, "episodes": […] } ]
        // v1.4.4 — the season objects also carry artwork (cover/cover_big)
        // even when they DON'T carry episode arrays (admagnun.net sends
        // season metadata + covers here and the actual episodes only in
        // the legacy "episodes" map) — the cover is captured regardless.
        val seasonCovers = HashMap<Int, String>()
        root.optJSONArray("seasons")?.let { seasons ->
            for (s in 0 until seasons.length()) {
                val seasonObj = seasons.optJSONObject(s) ?: continue
                val seasonNo = seasonObj.int("season_number", "season") ?: continue
                seasonObj.str("cover_big", "cover")?.let { cover ->
                    seasonCovers[seasonNo] = cover
                }
                val arr = seasonObj.optJSONArray("episodes") ?: continue
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    parseEpisode(obj, seasonNo)?.let { ep ->
                        bySeason.getOrPut(seasonNo) { LinkedHashMap() }
                            .putIfAbsent(ep.id, ep)
                    }
                }
            }
        }

        // Shape A (legacy): "episodes": { "1": […], "2": […] }
        root.optJSONObject("episodes")?.let { map ->
            for (key in map.keys()) {
                val seasonNo = key.toIntOrNull() ?: continue
                val arr = map.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    parseEpisode(obj, seasonNo)?.let { ep ->
                        bySeason.getOrPut(seasonNo) { LinkedHashMap() }
                            .putIfAbsent(ep.id, ep)
                    }
                }
            }
        }

        val seasons = bySeason.entries
            .filter { it.value.isNotEmpty() }
            .sortedBy { it.key }
            .map { (seasonNo, eps) ->
                VodSeason(
                    seasonNumber = seasonNo,
                    episodes = eps.values.sortedWith(
                        compareBy({ it.episodeNumber }, { it.id })
                    ),
                    cover = seasonCovers[seasonNo]
                )
            }

        val parsed = VodSeriesInfo(
            name = info.str("name"),
            poster = info.str("cover", "movie_image", "cover_big", "stream_icon"),
            plot = info.str("plot", "description"),
            genre = info.str("genre"),
            director = info.str("director", "directors"),
            cast = info.str("cast", "actors"),
            releaseDate = info.str("releasedate", "releaseDate", "year"),
            rating = info.str("rating", "rating_5based"),
            seasons = seasons,
            tmdbId = firstUsableTmdbId(info.str("tmdb_id")),
            imdbId = firstUsableTmdbId(info.str("imdb_id"))
        )
        if (parsed.hasAnyData()) return parsed
        }
        // v1.4.3 — broken JSON (root unparseable) or an object that parsed
        // but yielded nothing: regex-rescue the metadata so the info page
        // still shows fields + plot (episodes can't be salvaged this way).
        val rx = regexFields(raw) ?: return null
        return VodSeriesInfo(
            name = rx["name"],
            poster = rx["poster"],
            plot = rx["plot"],
            genre = rx["genre"],
            director = rx["director"],
            cast = rx["cast"],
            releaseDate = rx["releaseDate"],
            rating = rx["rating"],
            seasons = emptyList()
        )
    }

    private fun VodSeriesInfo.hasAnyData(): Boolean =
        name != null || poster != null || plot != null || genre != null ||
            director != null || cast != null || releaseDate != null ||
            rating != null || seasons.isNotEmpty()

    // ── episode ──

    /** First non-blank, non-zero, non-"null" candidate. */
    private fun firstUsableTmdbId(vararg candidates: String?): String? =
        candidates
            .mapNotNull { c -> c?.trim() }
            .firstOrNull { c -> c.isNotEmpty() && c != "0" && c != "null" }

    private fun parseEpisode(obj: JSONObject, fallbackSeason: Int): VodEpisode? {
        val id = obj.lng("id") ?: return null
        val epNum = obj.int("episode_num", "episode_number") ?: 0
        val inner = obj.optObjectOrFirst("info")
        val title = obj.str("title")
            ?: inner?.str("title")
            ?: "Episode ${epNum.takeIf { it > 0 } ?: id}"
        return VodEpisode(
            id = id,
            season = obj.int("season") ?: fallbackSeason,
            episodeNumber = epNum,
            title = title,
            containerExtension = obj.str("container_extension")
                ?: inner?.str("container_extension"),
            plot = inner?.str("plot") ?: obj.str("plot"),
            duration = inner?.duration() ?: obj.duration(),
            thumbnail = inner?.str("movie_image", "cover_big", "cover", "stream_icon")
                ?: obj.str("movie_image", "cover_big", "cover", "stream_icon"),
            directSource = obj.str("direct_source")?.takeIf { it.startsWith("http") }
        )
    }

    // ── tolerant getters ──

    /** JSONObject for key; a JSON ARRAY value is unwrapped to its first
     *  object element (v1.4.3: some panels wrap info in an array). */
    private fun JSONObject.optObjectOrFirst(key: String): JSONObject? = when (val v = opt(key)) {
        is JSONObject -> v
        is JSONArray -> {
            for (i in 0 until v.length()) {
                v.optJSONObject(i)?.let { return it }
            }
            null
        }
        else -> null
    }

    private fun String.toJsonObject(): JSONObject? {
        // Direct object parse.
        try {
            return JSONObject(this)
        } catch (e: Exception) { /* keep trying */ }
        // v1.4.3 — array root: pick the element that carries the payload.
        if (trim().startsWith("[")) {
            try {
                val arr = JSONArray(this)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.has("info") || o.has("movie_data") ||
                        o.has("seasons") || o.has("episodes")) return o
                }
                return arr.optJSONObject(0)
            } catch (ignore: Exception) { /* fall through to salvage */ }
        }
        // Some panels wrap or prefix junk — try to salvage the object body.
        val start = indexOf('{')
        val end = lastIndexOf('}')
        if (start in 0 until end) {
            return try {
                JSONObject(substring(start, end + 1))
            } catch (e2: Exception) { null }
        }
        return null
    }

    // ── v1.4.3 regex last-resort extraction (broken/non-JSON bodies) ──

    /** Regex-extracts movie fields from an unparseable body. */
    private fun regexMovieInfo(raw: String): VodMovieInfo? {
        if ('{' !in raw && '[' !in raw) return null
        val rx = regexFields(raw) ?: return null
        return VodMovieInfo(
            name = rx["name"],
            poster = rx["poster"],
            plot = rx["plot"],
            genre = rx["genre"],
            director = rx["director"],
            cast = rx["cast"],
            releaseDate = rx["releaseDate"],
            duration = rx["duration"]?.let { d ->
                // plain-seconds strings are formatted like the object path
                if (d.length <= 7 && d.all { it.isDigit() }) formatSecs(d.toLong()) else d
            },
            rating = rx["rating"],
            streamId = rx["streamId"]?.toLongOrNull(),
            containerExtension = rx["containerExtension"],
            tmdbId = rx["tmdbId"],
            imdbId = rx["imdbId"]
        )
    }

    /** Field-name aliases → extraction regexes; null when nothing found. */
    private fun regexFields(raw: String): Map<String, String>? {
        if (!raw.contains(":")) return null
        fun rx(vararg keys: String): String? {
            for (k in keys) {
                // quoted key + quoted value ("plot": "…")
                val pattern = "\"" + Regex.escape(k) +
                    "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""
                val m = Regex(pattern).find(raw) ?: continue
                val v = unescapeJson(m.groupValues[1])
                if (v.isNotEmpty()) return v
            }
            return null
        }
        val out = LinkedHashMap<String, String>()
        rx("name").let { if (it != null) out["name"] = it }
        rx("movie_image", "cover_big", "cover", "stream_icon").let { if (it != null) out["poster"] = it }
        rx("description", "plot").let { if (it != null) out["plot"] = it }
        rx("genre").let { if (it != null) out["genre"] = it }
        rx("director", "directors").let { if (it != null) out["director"] = it }
        rx("cast", "actors", "cast_members").let { if (it != null) out["cast"] = it }
        rx("releasedate", "releaseDate", "year").let { if (it != null) out["releaseDate"] = it }
        rx("duration").let { if (it != null) out["duration"] = it }
        rx("rating", "rating_5based").let { if (it != null) out["rating"] = it }
        rx("stream_id").let { if (it != null) out["streamId"] = it }
        rx("container_extension").let { if (it != null) out["containerExtension"] = it }
        rx("tmdb_id").let { if (it != null && it != "0") out["tmdbId"] = it }
        rx("imdb_id").let { if (it != null && it != "0" && it != "null") out["imdbId"] = it }
        return if (out.isEmpty()) null else out
    }

    /** Un-escapes the minimal JSON escapes we can encounter in values. */
    private fun unescapeJson(s: String): String = s
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\/", "/")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .trim()

    /** First non-blank string among the keys (scalars coerced; arrays
     *  of strings joined with ", " — v1.4.3: cast/genre as JSON arrays). */
    private fun JSONObject.str(vararg keys: String): String? {
        for (k in keys) {
            when (val v = opt(k)) {
                null, JSONObject.NULL, is JSONObject -> {}
                is JSONArray -> {
                    val parts = ArrayList<String>()
                    for (i in 0 until v.length()) {
                        val s = v.optJSONObject(i)?.str("name")
                            ?: v.optString(i, "").trim()
                        if (s.isNotEmpty() && s != "null") parts.add(s)
                    }
                    if (parts.isNotEmpty()) return parts.joinToString(", ")
                }
                else -> {
                    val s = v.toString().trim()
                    if (s.isNotEmpty() && s != "null") return s
                }
            }
        }
        return null
    }

    private fun JSONObject.lng(vararg keys: String): Long? {
        for (k in keys) {
            val v = optString(k, "").trim()
            if (v.isNotEmpty()) {
                v.toLongOrNull()?.let { return it }
                // "123.0" style numbers
                v.toDoubleOrNull()?.let { return it.toLong() }
            }
        }
        return null
    }

    private fun JSONObject.int(vararg keys: String): Int? = lng(*keys)?.toInt()

    /** duration string, or duration_secs formatted as "1h 23m".
     *  v1.4.2 — some panels send PLAIN SECONDS ("5400") in the duration
     *  field; detected and formatted instead of showing a raw number. */
    private fun JSONObject.duration(): String? {
        val raw = str("duration")
        if (raw != null) {
            if (raw.length <= 7 && raw.all { it.isDigit() }) {
                val secs = raw.toLongOrNull() ?: 0L
                return if (secs > 0) formatSecs(secs) else null
            }
            return raw
        }
        val secs = lng("duration_secs", "duration_seconds") ?: return null
        if (secs <= 0) return null
        return formatSecs(secs)
    }

    private fun formatSecs(secs: Long): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            m > 0 -> "${m}m"
            else -> "<1m"
        }
    }
}
