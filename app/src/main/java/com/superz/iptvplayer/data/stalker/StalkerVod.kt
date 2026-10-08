package com.superz.iptvplayer.data.stalker

import com.superz.iptvplayer.data.db.Movie
import com.superz.iptvplayer.data.db.SeriesShow
import com.superz.iptvplayer.data.xtream.VodEpisode
import com.superz.iptvplayer.data.xtream.VodMovieInfo
import com.superz.iptvplayer.data.xtream.VodSeason
import com.superz.iptvplayer.data.xtream.VodSeriesInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * v1.11.0 — the Stalker VOD & Series engine, replicated 1:1 from the
 * reference app (vuiptv 1.0.1 / classes7.dex):
 *
 *  • wire models      — Movie.java / SeasonStalker.java /
 *                       SeasonMiddlewareModel.java / EpisodeMiddlewareModel.java
 *                       (every response wrapped in {js:…}, lists in
 *                       {js:{total_items, data:[…]}})
 *  • row mappers      — get_ordered_list rows → Room movies/series rows
 *                       (the row JSON travels in stalkerRow — the info-page
 *                       source: stalker portals have no get_vod_info)
 *  • info builders    — row → VodMovieInfo / VodSeriesInfo (+ seasons in
 *                       BOTH families: Ministra get_season_models with the
 *                       embedded episode numbers, HTML middleware with
 *                       paged episodeMiddleware)
 *  • [StalkerVodRefs] — play-time refs for vodm:/vode: channels (cmd +
 *                       series number / HTML item ids)
 *  • [StalkerMediaHeaders] — the host registry the app-wide OkHttp network
 *                       interceptor consults to stamp the reference's media
 *                       User-Agent ("VU IPTV Player") on stalker traffic.
 *
 * The server drives page size (max_page_items — 14 on the reference's
 * test portal); the reference's pagination guard is page ≤ total/14 + 1.
 */
object StalkerVod {

    /** One get_ordered_list row — Movie.java, tolerant JsonPrimitive ids. */
    @Serializable
    data class StalkerVodRow(
        val id: JsonPrimitive? = null,
        val name: String? = null,
        val category_id: JsonPrimitive? = null,
        val cmd: String? = null,
        val screenshot_uri: String? = null,
        val year: String? = null,
        val age: String? = null,
        val actors: String? = null,
        val director: String? = null,
        val description: String? = null,
        val genres_str: String? = null,
        val rating_imdb: JsonPrimitive? = null,
        val time: JsonPrimitive? = null,
        val is_series: JsonPrimitive? = null,
        val tmdb_id: JsonPrimitive? = null,
        val container_extension: String? = null,
        val direct_source: String? = null
    ) {
        val idString: String? get() = id?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        val categoryId: String? get() = category_id?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        val isSeries: Boolean get() = is_series?.contentOrNull == "1"
        val rating: String? get() = rating_imdb?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        val duration: String? get() = time?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        val tmdbId: String?
            get() = tmdb_id?.contentOrNull
                ?.takeIf { it.isNotBlank() && it != "null" && it != "0" }
    }

    /** {js:{total_items, cur_page, max_page_items, data:[Movie]}}. */
    @Serializable
    data class OrderedListEnvelope(val js: OrderedListBody? = null) {
        @Serializable
        data class OrderedListBody(
            val total_items: JsonPrimitive? = null,
            val max_page_items: JsonPrimitive? = null,
            val data: List<StalkerVodRow>? = null
        ) {
            val totalItems: Int? get() = total_items?.contentOrNull?.toIntOrNull()
            val pageItems: Int? get() = max_page_items?.contentOrNull?.toIntOrNull()
        }
    }

    /** SeasonStalker.java — Ministra season row (episodes EMBEDDED). */
    @Serializable
    data class StalkerSeasonRow(
        val id: JsonPrimitive? = null,
        val name: String? = null,
        val cmd: String? = null,
        val category_id: JsonPrimitive? = null,
        val screenshot_uri: String? = null,
        val series: List<JsonPrimitive>? = null
    ) {
        val idString: String? get() = id?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        val cmdString: String? get() = cmd?.takeIf { it.isNotBlank() }
        val episodeNumbers: List<Int>
            get() = series?.mapNotNull { it.contentOrNull?.trim()?.toIntOrNull() }.orEmpty()
    }

    /** {js:{total_items, data:[SeasonStalker]}}. */
    @Serializable
    data class SeasonsEnvelope(val js: SeasonsBody? = null) {
        @Serializable
        data class SeasonsBody(
            val total_items: JsonPrimitive? = null,
            val data: List<StalkerSeasonRow>? = null
        ) {
            val totalItems: Int? get() = total_items?.contentOrNull?.toIntOrNull()
        }
    }

    /** SeasonMiddlewareModel.java — HTML-mode season row. */
    @Serializable
    data class SeasonMiddlewareRow(
        val id: JsonPrimitive? = null,
        val video_id: JsonPrimitive? = null,
        val name: String? = null,
        val season_number: JsonPrimitive? = null
    ) {
        val idString: String? get() = id?.contentOrNull
        val videoIdString: String? get() = video_id?.contentOrNull
        val seasonNumber: Int? get() = season_number?.contentOrNull?.toIntOrNull()
    }

    @Serializable
    data class SeasonMiddlewareEnvelope(val js: SeasonMiddlewareBody? = null) {
        @Serializable
        data class SeasonMiddlewareBody(val data: List<SeasonMiddlewareRow>? = null)
    }

    /** EpisodeMiddlewareModel.java — HTML-mode episode row. */
    @Serializable
    data class EpisodeMiddlewareRow(
        val id: JsonPrimitive? = null,
        val season_id: JsonPrimitive? = null,
        val name: String? = null,
        val series_number: JsonPrimitive? = null
    ) {
        val idString: String? get() = id?.contentOrNull
        val seasonIdString: String? get() = season_id?.contentOrNull
        val seriesNumber: Int? get() = series_number?.contentOrNull?.toIntOrNull()
    }

    /** {js:{total_items, data:[EpisodeMiddlewareModel]}}. */
    @Serializable
    data class EpisodeMiddlewareEnvelope(val js: EpisodeMiddlewareBody? = null) {
        @Serializable
        data class EpisodeMiddlewareBody(
            val total_items: JsonPrimitive? = null,
            val data: List<EpisodeMiddlewareRow>? = null
        ) {
            val totalItems: Int? get() = total_items?.contentOrNull?.toIntOrNull()
        }
    }

    /** get_episode_item response — js.data[0].id is the media file id. */
    @Serializable
    data class EpisodeItemEnvelope(val js: EpisodeItemBody? = null) {
        @Serializable
        data class EpisodeItemBody(val data: List<EpisodeItemId>? = null) {
            @Serializable
            data class EpisodeItemId(val id: JsonPrimitive? = null) {
                val idString: String? get() = id?.contentOrNull
            }
        }
    }

    // ── JSON (tolerant, like every stalker model) ──

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parseRow(raw: String): StalkerVodRow? = try {
        json.decodeFromString<StalkerVodRow>(raw)
    } catch (e: Exception) {
        null
    }

    // ── Row mappers (Room rows) ────────────────────────────────────

    /** Absolute poster URL (some portals send site-root-relative paths). */
    fun posterUrl(base: String?, uri: String?): String? {
        val u = uri?.takeIf { it.isNotBlank() } ?: return null
        if (u.startsWith("http", true)) return u
        if (u.startsWith("/") && base != null) return base.trimEnd('/') + u
        return u
    }

    /**
     * A movie row → the Room Movie row. `num` keeps the arrival order so
     * "sortby=added" (newest first) survives in the grid. Returns null for
     * rows without a numeric id (unaddressable — the info nav is Long-based).
     */
    fun movieRow(pid: Long, row: StalkerVodRow, num: Int, rowJson: String, base: String?): Movie? {
        val id = row.idString ?: return null
        val numeric = id.toLongOrNull() ?: return null
        return Movie(
            playlistId = pid,
            key = "m:$id",
            num = num,
            name = row.name ?: id,
            poster = posterUrl(base, row.screenshot_uri),
            categoryId = row.categoryId,
            streamId = numeric,
            containerExtension = row.container_extension?.takeIf { it.isNotBlank() },
            stalkerRow = rowJson
        )
    }

    /**
     * A series row → the Room SeriesShow row. Series ids arrive as
     * "46170:46170" (series:video) — the seasons call takes that FULL
     * string (SeriesInfoActivity passes current_series.getId() verbatim),
     * so the key keeps it; seriesId stores the numeric prefix the info-page
     * nav uses.
     */
    fun seriesRow(pid: Long, row: StalkerVodRow, num: Int, rowJson: String, base: String?): SeriesShow? {
        val id = row.idString ?: return null
        val numeric = id.substringBefore(':').toLongOrNull() ?: return null
        return SeriesShow(
            playlistId = pid,
            key = "sr:$id",
            num = num,
            name = row.name ?: id,
            poster = posterUrl(base, row.screenshot_uri),
            categoryId = row.categoryId,
            seriesId = numeric,
            stalkerRow = rowJson
        )
    }

    /** The "*" pseudo category (get_categories row id "*") is skipped —
     *  "All" is the rail's hardcoded chip (selectedCategoryId == null). */
    fun categoryRows(pid: Long, genres: List<StalkerGenre>): List<Pair<String, String>> =
        genres.mapNotNull { g ->
            val id = g.idString ?: return@mapNotNull null
            if (id == "*") return@mapNotNull null
            id to (g.title ?: id)
        }

    // ── Info builders ─────────────────────────────────────────────

    /** Movie.java fields → the info page (the row IS the info source). */
    fun movieInfo(row: StalkerVodRow, streamId: Long?, base: String?): VodMovieInfo =
        VodMovieInfo(
            name = row.name,
            poster = posterUrl(base, row.screenshot_uri),
            plot = row.description?.takeIf { it.isNotBlank() },
            genre = row.genres_str?.takeIf { it.isNotBlank() },
            director = row.director?.takeIf { it.isNotBlank() },
            cast = row.actors?.takeIf { it.isNotBlank() },
            releaseDate = row.year?.takeIf { it.isNotBlank() },
            duration = row.duration,
            rating = row.rating,
            streamId = streamId,
            containerExtension = row.container_extension?.takeIf { it.isNotBlank() },
            tmdbId = row.tmdbId,
            stalkerCmd = row.cmd?.takeIf { it.isNotBlank() },
            stalkerItem = row.idString?.let { id ->
                row.categoryId?.let { cat -> "$id|$cat" }
            }
        )

    /** Series metadata (seasons filled by the caller's family branch). */
    fun seriesInfo(row: StalkerVodRow?, show: SeriesShow?, base: String?, seasons: List<VodSeason>): VodSeriesInfo =
        VodSeriesInfo(
            name = row?.name ?: show?.name,
            poster = posterUrl(base, row?.screenshot_uri) ?: show?.poster,
            plot = row?.description?.takeIf { it.isNotBlank() },
            genre = row?.genres_str?.takeIf { it.isNotBlank() },
            director = row?.director?.takeIf { it.isNotBlank() },
            cast = row?.actors?.takeIf { it.isNotBlank() },
            releaseDate = row?.year?.takeIf { it.isNotBlank() },
            rating = row?.rating,
            seasons = seasons,
            tmdbId = row?.tmdbId
        )

    /**
     * Ministra seasons (SeriesInfoActivity.getSeriesInfo, verbatim):
     * rows are REVERSED (Collections.reverse — newest season first), each
     * season's episodes are the numbers embedded in `series[]`, and the
     * episode title is "Episode N" (EpisodeAdapter, verbatim). The season
     * number comes from the row name ("Season 1"), index as fallback.
     */
    fun ministraSeasons(rows: List<StalkerSeasonRow>): List<VodSeason> {
        val reversed = rows.asReversed()
        return reversed.mapIndexedNotNull { idx, season ->
            val episodes = season.episodeNumbers
            if (episodes.isEmpty()) return@mapIndexedNotNull null
            val seasonNo = seasonNumber(season.name, idx + 1)
            VodSeason(
                seasonNumber = seasonNo,
                episodes = episodes.map { ep ->
                    VodEpisode(
                        // synthetic, stable, unique within the series
                        id = seasonNo * 1_000_000L + ep,
                        season = seasonNo,
                        episodeNumber = ep,
                        title = "Episode $ep",
                        containerExtension = null,
                        plot = null,
                        duration = null,
                        thumbnail = null,
                        stalkerCmd = season.cmdString,
                        stalkerSeriesNum = ep
                    )
                }
            )
        }
    }

    /**
     * HTML-mode seasons (SeriesInfoActivity.getStalkerSeriesInfo →
     * getEpisodeModels): SeasonMiddleware rows + paged EpisodeMiddleware
     * rows per season.
     */
    fun htmlSeasons(
        seasons: List<SeasonMiddlewareRow>,
        episodesBySeason: Map<String, List<EpisodeMiddlewareRow>>,
        movieId: String,
        category: String
    ): List<VodSeason> = seasons.mapIndexedNotNull { idx, season ->
        val seasonId = season.idString ?: return@mapIndexedNotNull null
        val eps = episodesBySeason[seasonId].orEmpty()
        if (eps.isEmpty()) return@mapIndexedNotNull null
        VodSeason(
            seasonNumber = season.seasonNumber ?: seasonNumber(season.name, idx + 1),
            episodes = eps.mapIndexedNotNull { eIdx, ep ->
                val epId = ep.idString ?: return@mapIndexedNotNull null
                VodEpisode(
                    id = epId.toLongOrNull() ?: (idx * 100_000L + eIdx + 1L),
                    season = season.seasonNumber ?: (idx + 1),
                    episodeNumber = ep.seriesNumber ?: (eIdx + 1),
                    title = ep.name ?: "Episode ${ep.seriesNumber ?: (eIdx + 1)}",
                    containerExtension = null,
                    plot = null,
                    duration = null,
                    thumbnail = null,
                    stalkerSeriesNum = ep.seriesNumber,
                    stalkerItem = "$movieId|${ep.seasonIdString ?: seasonId}|$epId|$category"
                )
            }
        )
    }

    /** "Season 12" / "Сезон 3" → 12 / 3; fallback keeps the list stable. */
    fun seasonNumber(name: String?, fallback: Int): Int =
        Regex("(\\d+)").find(name ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: fallback

    /**
     * The reference's pagination guard (ItemActivity):
     * `if (page <= (total_items / 14) + 1) fetch(page)`.
     */
    fun pageAllowed(page: Int, totalItems: Int): Boolean =
        page <= totalItems / 14 + 1
}

/**
 * v1.11.0 — play-time refs for the synthetic vodm:/vode: channels.
 * MovieInfo/SeriesInfo ViewModels register one ref per channel of the
 * player's zap list; PlayerViewModel resolves each through
 * StalkerPlayback.resolveVod at play/retry time (tmp links are created
 * per switch — exactly the reference's create_link-on-every-open).
 */
object StalkerVodRefs {

    /**
     * @param cmd        the create_link input (movie cmd / season cmd)
     * @param seriesNum  the episode's series number (episodes only)
     * @param htmlItem   HTML-mode ids "a|b|c|d" = movieId|seasonId|
     *                   episodeId|category (movies omit season/episode →
     *                   "movieId|0|0|category")
     */
    data class Ref(
        val cmd: String? = null,
        val seriesNum: Int? = null,
        val htmlItem: String? = null
    )

    @Volatile
    private var refs: Map<String, Ref> = emptyMap()

    fun put(key: String, ref: Ref) {
        refs = refs + (key to ref)
    }

    fun get(key: String): Ref? = refs[key]

    /** Test hook. */
    fun reset() {
        refs = emptyMap()
    }
}

/**
 * v1.11.0 — the stalker MEDIA host registry. Registered at URL-resolve
 * time (both the portal host and the tmp play URL's host); the app-wide
 * OkHttp NETWORK interceptor then stamps the reference's media
 * User-Agent on those requests. The reference's media stack sends ONLY
 * a User-Agent ("VU IPTV Player" default, SettingActivity-configurable)
 * — no Cookie, no Referer: tmp links carry their own play_token.
 *
 * The interceptor also propagates scope through redirects (a registered
 * host's 3xx Location target joins the registry) because portals front
 * their CDNs with 302s.
 */
object StalkerMediaHeaders {

    /** The reference's default media UA (SharedPreferenceHelper). */
    const val MEDIA_USER_AGENT = "VU IPTV Player"

    @Volatile
    private var hosts: Set<String> = emptySet()

    fun register(host: String?) {
        if (host.isNullOrBlank()) return
        hosts = hosts + host.lowercase()
    }

    fun registerFromUrl(url: String?) {
        if (url.isNullOrBlank()) return
        runCatching { java.net.URI(url).host }.getOrNull()?.let { register(it) }
    }

    fun isRegistered(host: String?): Boolean =
        host != null && hosts.contains(host.lowercase())

    /** Test hook. */
    fun reset() {
        hosts = emptySet()
    }
}
