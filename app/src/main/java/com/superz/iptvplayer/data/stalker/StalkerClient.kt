package com.superz.iptvplayer.data.stalker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * v1.5.0 — Stalker-portal engine ("Connect with MAC ID"), replicated from
 * the reference app's remote layer (APIService.java + RetroClass.java +
 * BaseActivity.getToken/getProfile + EditPortalActivity.checkPortalUrl).
 *
 * The reference talks to portals over two endpoint families and tries them
 * in a FIXED order:
 *  • modern:  {base}/portal.php?…                       with Referer {base}/c/
 *  • legacy:  {base}/stalker_portal/server/load.php?…   with Referer
 *             {base}/stalker_portal/c/index.html
 * Every request carries Cookie `mac=<mac>;stb_lang=en;timezone=<device tz>`
 * (GioTVApp.time_zone = TimeZone.getDefault().getID()) and the handshake's
 * token feeds `Authorization: Bearer <token>` for the remaining calls.
 *
 * This file is NEW code — the existing XC/M3U engine (XtreamClient,
 * LoginViewModel, SmartPlayer, EngineRouter, …) is untouched.
 */

/** Typed failure surfaced to the login form (mirrors XtreamException). */
class StalkerException(message: String) : Exception(message)

/**
 * Pure URL/header builders — the tested contract of the stalker protocol.
 * Every string matches the reference's APIService annotations verbatim.
 */
object StalkerUrls {

    /** Utils.getRealPortal: protocol://authority, or null when unparseable. */
    fun realPortal(url: String): String? = try {
        val u = java.net.URL(url.trim())
        "${u.protocol}://${u.authority}"
    } catch (e: Exception) {
        null
    }

    /** Cookie header — BaseActivity.getToken: mac, stb_lang, device timezone. */
    fun cookie(mac: String): String =
        "mac=$mac;stb_lang=en;timezone=${java.util.TimeZone.getDefault().id}"

    /** Referer for the endpoint family (modern: /c/, legacy: stalker_portal). */
    fun referer(base: String, html: Boolean): String =
        if (html) "$base/stalker_portal/c/index.html" else "$base/c/"

    private fun path(base: String, html: Boolean, tail: String): String =
        if (html) "$base/stalker_portal/server/load.php$tail"
        else "$base/portal.php$tail"

    fun handshakeUrl(base: String, html: Boolean): String =
        path(base, html, "?action=handshake&type=stb&token=&JsHttpRequest=1-xml")

    fun profileUrl(base: String, html: Boolean): String =
        path(base, html, "?type=stb&action=get_profile&JsHttpRequest=1-xml")

    fun genresUrl(base: String, html: Boolean): String =
        path(base, html, "?type=itv&action=get_genres&JsHttpRequest=1-xml")

    fun allChannelsUrl(base: String, html: Boolean): String =
        path(base, html, "?type=itv&action=get_all_channels&force_ch_link_check=&JsHttpRequest=1-xml")

    /**
     * v1.12.5 — the adult-genre channel page (APIService.get_adult_channel,
     * VERBATIM): portals EXCLUDE the xxx genre's channels from
     * get_all_channels (live-proven on mag.max-cdn.com: 13 409 regular +
     * 320 hidden behind genre 75 "FOR ADULTS"), and the reference pulls
     * them through this exact paged endpoint (BaseActivity.getAdultChannel,
     * 14 rows per page, pages = total/14 [+1 when remainder]) and UPSERTS
     * them into the channel store.
     */
    fun adultListUrl(base: String, html: Boolean, genreId: String, page: Int): String =
        path(
            base, html,
            "?type=itv&action=get_ordered_list&force_ch_link_check=&fav=0&sortby=number&hd=0&JsHttpRequest=1-xml&genre=${enc(genreId)}&p=$page"
        )

    fun createLinkUrl(base: String, html: Boolean, cmd: String): String =
        path(
            base, html,
            "?type=itv&action=create_link&series=&forced_storage=0&disable_ad=0&download=0&force_ch_link_check=0&JsHttpRequest=1-xml&cmd=${enc(cmd)}"
        )

    /**
     * v1.7.0 — bulk EPG (StalkerEpgDownloadService, verbatim):
     * `type=itv&action=get_epg_info&period=10` — one shot with the whole
     * channel→programs map for the next days. Heavy (~15 MB on big portals)
     * but exactly what the reference downloads into its Realm EPG store.
     */
    fun epgInfoUrl(base: String, html: Boolean): String =
        path(base, html, "?type=itv&action=get_epg_info&period=10&JsHttpRequest=1-xml")

    /**
     * v1.7.0 — per-channel day table (APIService.get_catch_epg, verbatim
     * path): `type=epg&action=get_simple_data_table` with ch_id + mysql date
     * + page. 10 rows per page (the reference's pagination math).
     */
    fun simpleDataTableUrl(
        base: String,
        html: Boolean,
        chId: Long,
        date: String,
        page: Int
    ): String = path(
        base, html,
        "?type=epg&action=get_simple_data_table&ch_id=$chId&date=${enc(date)}&p=$page&JsHttpRequest=1-xml"
    )

    // ── v1.11.0 — VOD & Series (APIService.java, VERBATIM) ──

    /** get_vod_genre — `type=vod&action=get_categories` → js[] {id,title}. */
    fun vodCategoriesUrl(base: String, html: Boolean): String =
        path(base, html, "?type=vod&action=get_categories&JsHttpRequest=1-xml")

    /** get_series_genre — `type=series&action=get_categories`. */
    fun seriesCategoriesUrl(base: String, html: Boolean): String =
        path(base, html, "?type=series&action=get_categories&JsHttpRequest=1-xml")

    /**
     * get_movie_models / get_series_models — the paged browse lists
     * (ItemActivity). The MOVIE variant carries `row=0`; the SERIES variant
     * does not — both verbatim from the reference annotations. Query params
     * and their defaults match ItemActivity's fields exactly:
     * sortby=added, fav=0, hd=0, not_ended=0, abc=*, genre=*, years=*.
     */
    fun orderedListUrl(
        base: String,
        html: Boolean,
        series: Boolean,
        category: String,
        sortby: String,
        fav: Int,
        hd: Int,
        notEnded: Int,
        abc: String,
        genre: String,
        years: String,
        search: String,
        p: Int
    ): String {
        val head = if (series) {
            "?type=series&action=get_ordered_list&movie_id=0&season_id=0&episode_id=0&JsHttpRequest=1-xml"
        } else {
            "?type=vod&action=get_ordered_list&movie_id=0&season_id=0&episode_id=0&row=0&JsHttpRequest=1-xml"
        }
        return path(
            base, html,
            "$head&category=${enc(category)}&sortby=${enc(sortby)}&fav=$fav" +
                "&hd=$hd&not_ended=$notEnded&abc=${enc(abc)}&genre=${enc(genre)}" +
                "&years=${enc(years)}&search=${enc(search)}&p=$p"
        )
    }

    /**
     * get_season_models — Ministra seasons: `type=series` with movie_id;
     * episode numbers are EMBEDDED in each row's `series` array.
     */
    fun seasonsUrl(base: String, html: Boolean, movieId: String, category: String, p: Int): String =
        path(
            base, html,
            "?type=series&action=get_ordered_list&season_id=0&episode_id=0&fav=0&sortby=added&hd=0&not_ended=0&JsHttpRequest=1-xml" +
                "&movie_id=${enc(movieId)}&category=${enc(category)}&p=$p"
        )

    /** get_season_middleware — HTML-mode seasons (type=vod, sortby=name). */
    fun seasonMiddlewareUrl(base: String, html: Boolean, movieId: String, category: String): String =
        path(
            base, html,
            "?type=vod&action=get_ordered_list&season_id=0&episode_id=0&row=0&fav=0&sortby=name&hd=0&not_ended=0&p=1&JsHttpRequest=1-xml" +
                "&movie_id=${enc(movieId)}&category=${enc(category)}"
        )

    /** get_episode_middleware — HTML-mode episodes (type=vod, paged). */
    fun episodeMiddlewareUrl(
        base: String,
        html: Boolean,
        movieId: String,
        seasonId: String,
        category: String,
        p: Int
    ): String = path(
        base, html,
        "?type=vod&action=get_ordered_list&episode_id=0&row=0&fav=0&sortby=name&hd=0&not_ended=0&JsHttpRequest=1-xml" +
            "&movie_id=${enc(movieId)}&season_id=${enc(seasonId)}&category=${enc(category)}&p=$p"
    )

    /**
     * get_episode_item — the single-item file-id lookup every HTML-mode
     * playback starts with (MoviePlayerActivity.getVodItem /
     * SeriesPlayActivity.getEpisodeItem): movie_id + season_id +
     * episode_id + category → js.data[0].id = the media file id.
     */
    fun episodeItemUrl(
        base: String,
        html: Boolean,
        movieId: String,
        seasonId: String,
        episodeId: String,
        category: String
    ): String = path(
        base, html,
        "?type=vod&action=get_ordered_list&fav=0&sortby=name&hd=0&not_ended=0&p=1&JsHttpRequest=1-xml" +
            "&movie_id=${enc(movieId)}&season_id=${enc(seasonId)}&episode_id=${enc(episodeId)}&category=${enc(category)}"
    )

    /**
     * get_movie_cmd — the MOVIE create_link. NOTE (verbatim): the
     * reference's annotation carries NO JsHttpRequest suffix on this one.
     */
    fun vodCreateLinkUrl(base: String, html: Boolean, cmd: String): String =
        path(base, html, "?type=vod&action=create_link&cmd=${enc(cmd)}")

    /** get_series_cmd — the EPISODE create_link (+ the series number). */
    fun seriesCreateLinkUrl(base: String, html: Boolean, cmd: String, series: Int): String =
        path(
            base, html,
            "?type=vod&action=create_link&forced_storage=0&disable_ad=0&download=0&force_ch_link_check=0&JsHttpRequest=1-xml" +
                "&cmd=${enc(cmd)}&series=$series"
        )

    /**
     * v1.12.0 — get_catch_url / get_catch_url_html — the tv-archive
     * create_link, VERBATIM from the reference's APIService annotations
     * (CatchUpPlayActivity). NOTE the two family differences:
     *  • forced_storage is EMPTY here (the itv/series variants use "0");
     *  • the media cmd is "auto /media/<id>.ts" on portal.php and
     *    "auto /media/<id>.mpg" on stalker_portal (the reference picks
     *    the extension per family, Live-verified on mag.max-cdn.com:
     *    cmd="auto /media/<epgId>.ts" → js.cmd =
     *    "ffmpeg http://…/play/timeshift.php?…&play_token=…" → 206 mp2t).
     */
    fun catchCreateLinkUrl(base: String, html: Boolean, cmd: String): String =
        path(
            base, html,
            "?type=tv_archive&action=create_link&series=&forced_storage=&disable_ad=0&download=0&force_ch_link_check=0&JsHttpRequest=1-xml" +
                "&cmd=${enc(cmd)}"
        )

    /**
     * v1.12.0 — the catch-up media cmd for one archived program, built
     * family-correct (the reference's CatchUpPlayActivity.getStreamUrl):
     * `"auto /media/" + epgId + ".ts"` (portal.php) / `".mpg"`
     * (stalker_portal).
     */
    fun catchCmd(fileId: String, html: Boolean): String =
        "auto /media/$fileId" + if (html) ".mpg" else ".ts"

    /**
     * v1.11.0 — the reference's playback URL extraction, VERBATIM chain
     * (MoviePlayerActivity / SeriesPlayActivity):
     * `cmd.replaceAll("ffmpeg","").replaceAll("auto","").replaceAll("\\s","")`
     * then ExoPlayer plays whatever http(s) URL remains.
     */
    fun playUrlFromCmd(cmd: String?): String? {
        val cleaned = cmd?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace("ffmpeg", "")
            ?.replace("auto", "")
            ?.replace(Regex("\\s"), "")
            ?: return null
        return cleaned.takeIf { it.startsWith("http", true) }
    }

    /**
     * The cmd create_link expects — the reference's getLiveStreamUrl
     * (LivePlayActivity, verbatim):
     *  • modern portal.php portals: the SYNTHESIZED localhost form
     *    "ffmpeg http://localhost/ch/<id>_" built from the channel's stalker
     *    id — NOT the channel's stored cmd. Proven on mag.max-cdn.com
     *    (v1.6.0's every-channel-fails bug): such portals rewrite a stored
     *    cmd into a dead link with an EMPTY "stream=" parameter, while the
     *    localhost form returns a valid time-limited play URL.
     *  • legacy stalker_portal portals: the channel's stored cmd as-is.
     * A non-numeric id (or a missing one) falls back to the stored cmd.
     */
    fun createLinkCmd(html: Boolean, streamId: String?, storedCmd: String?): String? {
        if (html) return storedCmd
        val id = streamId?.trim()
        if (id.isNullOrEmpty() || !id.all { it.isDigit() }) return storedCmd
        return "ffmpeg http://localhost/ch/${id}_"
    }

    fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /**
     * The reference's playback URL extraction (LivePlayActivity /
     * ChannelListRecyclerAdapter): `js.cmd` arrives as
     * "ffmpeg http://…" / "auto http://…" — the prefix is dropped.
     */
    fun streamUrlFromCmd(cmd: String?): String? {
        val raw: String = cmd?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val url = when {
            raw.startsWith("ffmpeg ", true) -> raw.substring(7).trim()
            raw.startsWith("auto ", true) -> raw.substring(5).trim()
            else -> raw
        }
        return url.takeIf { it.startsWith("http", true) }
    }
}

/**
 * A live portal session: which endpoint family worked + the token the
 * remaining calls must carry as `Authorization: Bearer <token>`.
 */
data class StalkerSession(
    val base: String,
    val mac: String,
    val token: String,
    val html: Boolean
) {
    val referer: String get() = StalkerUrls.referer(base, html)
    val cookie: String get() = StalkerUrls.cookie(mac)
}

// ── Wire models (kotlinx.serialization, `js` envelopes like the reference's Gson DTOs) ──

@Serializable
data class TokenEnvelope(val js: TokenBody? = null) {
    @Serializable
    data class TokenBody(val token: String? = null)
}

@Serializable
data class ProfileEnvelope(val js: ProfileBody? = null) {
    /** v1.7.0: default_timezone captured too — the EPG timestamp fix. */
    @Serializable
    data class ProfileBody(
        val id: JsonPrimitive? = null,
        val default_timezone: String? = null
    )
}

@Serializable
data class GenreEnvelope(val js: List<StalkerGenre>? = null)

@Serializable
data class StalkerGenre(val id: JsonPrimitive? = null, val title: String? = null) {
    val idString: String? get() = id?.contentOrNull
}

@Serializable
data class ChannelEnvelope(val js: ChannelBody? = null) {
    @Serializable
    data class ChannelBody(
        val data: List<StalkerChannel>? = null,
        /** v1.12.5 — JsonPrimitive: get_ordered_list sends total_items and
         *  some portals quote it ("320") — tolerant like every other total. */
        val total_items: JsonPrimitive? = null
    ) {
        val totalItems: Int? get() = total_items?.contentOrNull?.toIntOrNull()
    }
}

/** Channel.java: id, name, cmd, number, status, tv_genre_id, xmltv_id, logo, archive. */
@Serializable
data class StalkerChannel(
    val id: JsonPrimitive? = null,
    val name: String? = null,
    val cmd: String? = null,
    val number: JsonPrimitive? = null,
    val status: JsonPrimitive? = null,
    val tv_genre_id: JsonPrimitive? = null,
    val logo: String? = null,
    /** v1.12.0 — the tv-archive flag. The reference maps Channel.archive;
     *  some portals only send enable_tv_archive, so both are accepted
     *  (Live-verified on mag.max-cdn.com: 39 of 13 409 rows carry 1). */
    val archive: JsonPrimitive? = null,
    val enable_tv_archive: JsonPrimitive? = null
) {
    val idString: String? get() = id?.contentOrNull
    val numberInt: Int? get() = number?.contentOrNull?.toIntOrNull()
    val genreId: String? get() = tv_genre_id?.contentOrNull
    val tvArchiveFlag: Int? get() = when {
        archive?.contentOrNull?.toIntOrNull() == 1 -> 1
        enable_tv_archive?.contentOrNull?.toIntOrNull() == 1 -> 1
        // explicit 0 (either field) → off; both absent → unknown.
        archive?.contentOrNull?.toIntOrNull() == 0 -> 0
        enable_tv_archive?.contentOrNull?.toIntOrNull() == 0 -> 0
        else -> null
    }
}

@Serializable
data class CmdEnvelope(val js: CmdBody? = null) {
    @Serializable
    data class CmdBody(val cmd: String? = null)
}

// ── v1.7.0 EPG wire models (StalkerEpgDownloadService / get_simple_data_table shapes) ──

/** One program row as the portal sends it (bulk map value / table row). */
@Serializable
data class StalkerEpgEntry(
    val name: String? = null,
    val descr: String? = null,
    val start_timestamp: JsonPrimitive? = null,
    val stop_timestamp: JsonPrimitive? = null,
    /** v1.12.0 — catch-up fields (get_simple_data_table rows only): the
     *  compound file id ("55164434_368515" — the /media/<id>.ts input) and
     *  the archived marker (the reference's StalkerEpgModel.id /
     *  mark_archive — the row's clock icon). */
    val id: JsonPrimitive? = null,
    val real_id: JsonPrimitive? = null,
    val mark_archive: JsonPrimitive? = null
) {
    val startSeconds: Long? get() = start_timestamp?.longOrNull
    val stopSeconds: Long? get() = stop_timestamp?.longOrNull
    val fileId: String? get() = id?.contentOrNull
    val markArchiveFlag: Int? get() = mark_archive?.contentOrNull?.toIntOrNull()
}

/** Bulk get_epg_info: {"js":{"data":{"<ch_id>":[…]}}} */
@Serializable
data class BulkEpgEnvelope(val js: BulkEpgBody? = null) {
    @Serializable
    data class BulkEpgBody(val data: Map<String, List<StalkerEpgEntry>>? = null)
}

/** get_simple_data_table: {"js":{"data":[…],"total_items":N}} */
@Serializable
data class TableEpgEnvelope(val js: TableEpgBody? = null) {
    @Serializable
    data class TableEpgBody(
        val data: List<StalkerEpgEntry>? = null,
        val total_items: JsonPrimitive? = null
    ) {
        val totalItems: Int? get() = total_items?.contentOrNull?.toIntOrNull()
    }
}

/**
 * Minimal stalker client over the shared OkHttpClient — same shape as
 * XtreamClient (IO dispatch, typed failure, tolerant JSON).
 */
class StalkerClient(private val okHttp: OkHttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * The reference's handshake ladder: modern portal.php first; when it
     * yields no token (body null / js null / token null / empty — exactly
     * the reference's checks in EditPortalActivity.getToken) fall back to
     * the legacy stalker_portal family. Both failing = PORTAL_NOT_WORKING.
     */
    suspend fun handshake(base: String, mac: String): StalkerSession =
        withContext(Dispatchers.IO) {
            val cookie = StalkerUrls.cookie(mac)
            for (html in listOf(false, true)) {
                val referer = StalkerUrls.referer(base, html)
                val token = try {
                    val body = raw(
                        StalkerUrls.handshakeUrl(base, html),
                        referer = referer, cookie = cookie, auth = null
                    )
                    json.decodeFromString<TokenEnvelope>(body)
                        .js?.token?.takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    null
                }
                if (token != null) {
                    return@withContext StalkerSession(base = base, mac = mac, token = token, html = html)
                }
            }
            throw StalkerException("PORTAL_NOT_WORKING")
        }

    /**
     * Profile validation — the reference accepts the portal only when
     * `js.id` is present, non-empty and not the literal "null"
     * (EditPortalActivity.getProfile's response checks, verbatim).
     */
    suspend fun profileOk(session: StalkerSession): Boolean = withContext(Dispatchers.IO) {
        try {
            val id = profile(session)?.id?.contentOrNull
            id != null && id.isNotBlank() && id != "null"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * v1.7.0 — the full profile body (id + default_timezone). The EPG
     * engine needs default_timezone: the portal shifts every EPG timestamp
     * by (offset(cookieTz) − offset(default_timezone)), and the reference's
     * getDateCurrentTimeZone inversion uses exactly this zone.
     */
    suspend fun profile(session: StalkerSession): ProfileEnvelope.ProfileBody? =
        withContext(Dispatchers.IO) {
            val body = raw(
                StalkerUrls.profileUrl(session.base, session.html),
                referer = session.referer, cookie = session.cookie,
                auth = "Bearer ${session.token}"
            )
            json.decodeFromString<ProfileEnvelope>(body).js
        }

    /** itv/get_genres → js[] {id, title} — best-effort category names. */
    suspend fun genres(session: StalkerSession): List<StalkerGenre> = withContext(Dispatchers.IO) {
        val body = raw(
            StalkerUrls.genresUrl(session.base, session.html),
            referer = session.referer, cookie = session.cookie,
            auth = "Bearer ${session.token}"
        )
        json.decodeFromString<GenreEnvelope>(body).js.orEmpty()
    }

    /** itv/get_all_channels → js.data[] — the full live list. */
    suspend fun allChannels(session: StalkerSession): List<StalkerChannel> =
        withContext(Dispatchers.IO) {
            val body = raw(
                StalkerUrls.allChannelsUrl(session.base, session.html),
                referer = session.referer, cookie = session.cookie,
                auth = "Bearer ${session.token}"
            )
            json.decodeFromString<ChannelEnvelope>(body).js?.data.orEmpty()
        }

    /**
     * v1.12.5 — ONE adult-genre page (get_adult_channel): rows + the
     * portal's total_items (14 rows per page). Callers loop the pages with
     * the reference's math and upsert the rows additively.
     */
    suspend fun adultChannelsPage(
        session: StalkerSession,
        genreId: String,
        page: Int
    ): Pair<List<StalkerChannel>, Int> = withContext(Dispatchers.IO) {
        val body = raw(
            StalkerUrls.adultListUrl(session.base, session.html, genreId, page),
            referer = session.referer, cookie = session.cookie,
            auth = "Bearer ${session.token}"
        )
        val js = json.decodeFromString<ChannelEnvelope>(body).js
        (js?.data.orEmpty()) to (js?.totalItems ?: 0)
    }

    companion object {
        /**
         * v1.12.5 — the reference's getAdultChannel page math, VERBATIM:
         * `total/14` when divisible, `total/14 + 1` otherwise (never less
         * than 1 — a missing total falls back to the current page).
         */
        fun adultPagesFor(totalItems: Int): Int = when {
            totalItems <= 0 -> 1
            totalItems % 14 == 0 -> totalItems / 14
            else -> totalItems / 14 + 1
        }

        /**
         * v1.12.5 — BaseActivity.getLiveGenre's xxx detection, VERBATIM:
         * iterating the genre list OVERWRITES Constants.xxx_category_id on
         * every title containing "xxx"/"adult" (no "porn" here) — so the
         * LAST match wins. That id is the one whose channels the portal
         * hides from get_all_channels (the separate getAdultChannel fetch).
         */
        fun xxxGenreId(genres: List<StalkerGenre>): String? =
            genres.lastOrNull { g ->
                val t = (g.title ?: "").lowercase()
                t.contains("xxx") || t.contains("adult")
            }?.idString
    }

    /**
     * itv/create_link — the play-time URL resolver. Returns `js.cmd`
     * ("ffmpeg http://…") as-is; callers extract the URL via
     * [StalkerUrls.streamUrlFromCmd].
     */
    suspend fun createLink(session: StalkerSession, cmd: String): String =
        withContext(Dispatchers.IO) {
            val body = raw(
                StalkerUrls.createLinkUrl(session.base, session.html, cmd),
                referer = session.referer, cookie = session.cookie,
                auth = "Bearer ${session.token}"
            )
            json.decodeFromString<CmdEnvelope>(body).js?.cmd
                ?: throw StalkerException("PORTAL_NOT_WORKING")
        }

    /**
     * v1.7.0 — authenticated raw GET on the session (EPG fetches): same
     * Referer/Cookie/Bearer headers as every other call, body as string.
     */
    suspend fun rawGet(session: StalkerSession, url: String): String =
        withContext(Dispatchers.IO) {
            raw(url, referer = session.referer, cookie = session.cookie,
                auth = "Bearer ${session.token}")
        }

    // ── v1.11.0 — VOD & Series engine (APIService calls, verbatim) ──

    /** type=vod get_categories → js[] {id, title} ("*" = All). */
    suspend fun vodCategories(session: StalkerSession): List<StalkerGenre> =
        withContext(Dispatchers.IO) {
            val body = raw(
                StalkerUrls.vodCategoriesUrl(session.base, session.html),
                referer = session.referer, cookie = session.cookie,
                auth = "Bearer ${session.token}"
            )
            json.decodeFromString<GenreEnvelope>(body).js.orEmpty()
        }

    /** type=series get_categories → js[] {id, title}. */
    suspend fun seriesCategories(session: StalkerSession): List<StalkerGenre> =
        withContext(Dispatchers.IO) {
            val body = raw(
                StalkerUrls.seriesCategoriesUrl(session.base, session.html),
                referer = session.referer, cookie = session.cookie,
                auth = "Bearer ${session.token}"
            )
            json.decodeFromString<GenreEnvelope>(body).js.orEmpty()
        }

    /**
     * The paged browse list (movies OR series rows — same envelope).
     * Defaults follow ItemActivity: sortby=added, fav=0, hd=0,
     * not_ended=0, abc=*, genre=*, years=*.
     */
    suspend fun orderedList(
        session: StalkerSession,
        series: Boolean,
        category: String,
        search: String,
        page: Int
    ): StalkerVod.OrderedListEnvelope = withContext(Dispatchers.IO) {
        val body = raw(
            StalkerUrls.orderedListUrl(
                session.base, session.html, series,
                category.ifBlank { "*" }, "added", 0, 0, 0, "*", "*", "*",
                search, page
            ),
            referer = session.referer, cookie = session.cookie,
            auth = "Bearer ${session.token}"
        )
        json.decodeFromString<StalkerVod.OrderedListEnvelope>(body)
    }

    /** Ministra seasons (get_season_models) → rows with embedded episodes. */
    suspend fun seasons(
        session: StalkerSession,
        movieId: String,
        category: String,
        page: Int
    ): StalkerVod.SeasonsEnvelope = withContext(Dispatchers.IO) {
        val body = raw(
            StalkerUrls.seasonsUrl(session.base, session.html, movieId, category, page),
            referer = session.referer, cookie = session.cookie,
            auth = "Bearer ${session.token}"
        )
        json.decodeFromString<StalkerVod.SeasonsEnvelope>(body)
    }

    /** HTML-mode seasons (get_season_middleware). */
    suspend fun seasonMiddleware(
        session: StalkerSession,
        movieId: String,
        category: String
    ): List<StalkerVod.SeasonMiddlewareRow> = withContext(Dispatchers.IO) {
        val body = raw(
            StalkerUrls.seasonMiddlewareUrl(session.base, session.html, movieId, category),
            referer = session.referer, cookie = session.cookie,
            auth = "Bearer ${session.token}"
        )
        json.decodeFromString<StalkerVod.SeasonMiddlewareEnvelope>(body).js?.data.orEmpty()
    }

    /** HTML-mode episodes page (get_episode_middleware). */
    suspend fun episodeMiddleware(
        session: StalkerSession,
        movieId: String,
        seasonId: String,
        category: String,
        page: Int
    ): StalkerVod.EpisodeMiddlewareEnvelope = withContext(Dispatchers.IO) {
        val body = raw(
            StalkerUrls.episodeMiddlewareUrl(
                session.base, session.html, movieId, seasonId, category, page
            ),
            referer = session.referer, cookie = session.cookie,
            auth = "Bearer ${session.token}"
        )
        json.decodeFromString<StalkerVod.EpisodeMiddlewareEnvelope>(body)
    }

    /**
     * The single-item file-id lookup (get_episode_item): js.data[0].id —
     * the id the HTML-mode cmd `/media/file_<id>.mpg` is built from.
     */
    suspend fun episodeItem(
        session: StalkerSession,
        movieId: String,
        seasonId: String,
        episodeId: String,
        category: String
    ): String? = withContext(Dispatchers.IO) {
        val body = raw(
            StalkerUrls.episodeItemUrl(
                session.base, session.html, movieId, seasonId, episodeId, category
            ),
            referer = session.referer, cookie = session.cookie,
            auth = "Bearer ${session.token}"
        )
        json.decodeFromString<StalkerVod.EpisodeItemEnvelope>(body)
            .js?.data?.firstOrNull()?.idString
    }

    /** Movie create_link (get_movie_cmd) → raw `js.cmd` ("ffmpeg http://…"). */
    suspend fun createVodLink(session: StalkerSession, cmd: String): String =
        withContext(Dispatchers.IO) {
            val body = raw(
                StalkerUrls.vodCreateLinkUrl(session.base, session.html, cmd),
                referer = session.referer, cookie = session.cookie,
                auth = "Bearer ${session.token}"
            )
            json.decodeFromString<CmdEnvelope>(body).js?.cmd
                ?: throw StalkerException("PORTAL_NOT_WORKING")
        }

    /** Episode create_link (get_series_cmd) with the series number. */
    suspend fun createSeriesLink(session: StalkerSession, cmd: String, seriesNum: Int): String =
        withContext(Dispatchers.IO) {
            val body = raw(
                StalkerUrls.seriesCreateLinkUrl(session.base, session.html, cmd, seriesNum),
                referer = session.referer, cookie = session.cookie,
                auth = "Bearer ${session.token}"
            )
            json.decodeFromString<CmdEnvelope>(body).js?.cmd
                ?: throw StalkerException("PORTAL_NOT_WORKING")
        }

    /**
     * v1.12.0 — the tv-archive create_link (the reference's
     * get_catch_url / get_catch_url_html, CatchUpPlayActivity):
     * `type=tv_archive&action=create_link&series=&forced_storage=&…` with
     * the family-correct "auto /media/<id>.ts|.mpg" cmd → the tmp
     * timeshift URL in js.cmd.
     */
    suspend fun createCatchLink(session: StalkerSession, cmd: String): String =
        withContext(Dispatchers.IO) {
            val body = raw(
                StalkerUrls.catchCreateLinkUrl(session.base, session.html, cmd),
                referer = session.referer, cookie = session.cookie,
                auth = "Bearer ${session.token}"
            )
            json.decodeFromString<CmdEnvelope>(body).js?.cmd
                ?: throw StalkerException("PORTAL_NOT_WORKING")
        }

    private fun raw(url: String, referer: String, cookie: String, auth: String?): String {
        val builder = Request.Builder().url(url).get()
            .header("Referer", referer)
            .header("Cookie", cookie)
        if (auth != null) builder.header("Authorization", auth)
        okHttp.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw StalkerException("HTTP_${response.code}")
            return response.body?.string() ?: throw StalkerException("EMPTY_BODY")
        }
    }

    /**
     * v1.12.5 — the 401/403 renewal contract (the v1.10.0 authGet design,
     * restored after the v1.8.1 rebase): run [block] with [session]; when
     * the portal answers 401/403 (the token died — portals bind ONE token
     * per MAC, so handshaking the SAME account from another app — e.g. the
     * reference app during side-by-side testing — kills ours mid-session),
     * re-run the handshake ladder, hand the fresh session to [onRenewed]
     * (the caller updates the shared cache) and retry the call ONCE.
     * Anything else propagates unchanged.
     */
    suspend fun <T> authedCall(
        session: StalkerSession,
        onRenewed: (StalkerSession) -> Unit,
        block: suspend (StalkerSession) -> T
    ): T {
        return try {
            block(session)
        } catch (e: StalkerException) {
            val code = e.message ?: ""
            if (code != "HTTP_401" && code != "HTTP_403") throw e
            val fresh = handshake(session.base, session.mac)
            onRenewed(fresh)
            block(fresh)
        }
    }
}
