package com.superz.iptvplayer.player

import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.Movie
import com.superz.iptvplayer.data.xtream.VodEpisode
import com.superz.iptvplayer.data.xtream.VodSeason

/**
 * v2.2.4 — THE CONTENT SIDEBAR (user directive: “عند النقر على القائمة
 * الجانبية في المشغل عند مشاهدة مسلسل يجب أن تظهر الحلقات… و نفس الشيء
 * للأفلام يجب أن تظهر قائمة الأفلام كي لا يبقى المستخدم يخرج من المشغل
 * ليختار فيلم أو حلقة أو مسلسل”).
 *
 * PURE builders for the player's slide-out list when the session is VOD:
 *  • a SERIES episode session → the FULL series' episodes (every season,
 *    in order — the zap list upgrade that makes auto-next cross seasons,
 *    exactly the big platforms' binge behavior);
 *  • a MOVIE session → the playlist's movie list (DB rows, position
 *    numbers — the DB's own `num` runs into the tens of thousands and
 *    would overflow the row's 30dp badge).
 *
 * The v2.2.3 CRASH this file's delivery fixes: the info pages register
 * synthetic VOD channels whose Room `id` stays at the default 0 — a list
 * of N episodes meant N LazyColumn rows all keyed “0” →
 * IllegalArgumentException(“Key 0 was already used”) the instant the
 * sidebar opened (movies registered a single row, live channels come
 * from the DB with real ids — which is why ONLY series crashed). The
 * sidebar now keys rows by `Channel.key` (unique by construction:
 * vode:{episodeId} / vodm:{streamId} / the live channel_key index).
 *
 * Zero engine contact: these builders only assemble [Channel] rows for
 * the UI/ViewModel layer, exactly like the info pages' registrations.
 */
object VodSidebarBuilder {

    /** v2.2.4 — what the player's slide-out list represents. */
    enum class Mode { CHANNELS, EPISODES, MOVIES }

    /**
     * The movies sidebar page size — the DB query's LIMIT. Big portals
     * list 100k+ movies; the page keeps the sidebar's memory bounded and
     * its list snappy, while the search field re-queries the WHOLE
     * library through the same DAO call.
     */
    const val MOVIES_SIDEBAR_LIMIT: Int = 600

    /** The mode for what's playing right now. */
    fun modeFor(channelKey: String?, localSession: Boolean): Mode = when {
        localSession -> Mode.CHANNELS                       // saved-files library: the local zap list IS the right list
        channelKey == null -> Mode.CHANNELS
        channelKey.startsWith("vode:") -> Mode.EPISODES     // a series episode → the episodes list
        channelKey.startsWith("vodm:local:") -> Mode.CHANNELS
        channelKey.startsWith("vodm:") -> Mode.MOVIES       // a streamed movie → the movies list
        else -> Mode.CHANNELS                               // live channels / catch-up: unchanged
    }

    /** The row label the info pages' registrations already use. */
    fun episodeName(ep: VodEpisode): String = "S${ep.season}E${ep.episodeNumber} · ${ep.title}"

    /**
     * Every episode of every season, in order, as zap-able channels.
     * [resolve] supplies the playback row per episode — Xtream:
     * (directSource ?: built /series/ URL, null); PORTAL: (null, the
     * season's stalker cmd) with the refs armed by the caller. The
     * currently-playing channel object is preserved verbatim (its URL
     * was already resolved/probed at registration — it must never be
     * swapped for a fresh unresolved row). Duplicated keys (defensive:
     * panels that repeat an episode id across seasons) keep the FIRST
     * occurrence.
     */
    fun buildEpisodeChannels(
        playlistId: Long,
        seasons: List<VodSeason>,
        posterFallback: String?,
        resolve: (VodEpisode) -> Pair<String?, String?>,
        current: Channel?
    ): List<Channel> {
        val currentKey = current?.key
        val out = ArrayList<Channel>(64)
        for (season in seasons) {
            for (ep in season.episodes) {
                val (url, cmd) = resolve(ep)
                out += Channel(
                    playlistId = playlistId,
                    key = "vode:${ep.id}",
                    num = ep.episodeNumber,
                    name = episodeName(ep),
                    logo = ep.thumbnail ?: posterFallback,
                    categoryId = null,
                    directUrl = url,
                    stalkerCmd = cmd
                )
            }
        }
        val dedup = out.distinctBy { it.key }
        if (currentKey == null) return dedup
        return dedup.map { ch -> if (ch.key == currentKey) (current ?: ch) else ch }
    }

    /**
     * The playlist's movies as sidebar channels — key "vodm:{streamId}",
     * num = the 1-based POSITION (badge-safe; the DB's num is a global
     * counter that overflows the row's 30dp badge), logo = the poster.
     * The currently-playing movie keeps its resolved URL/cmd/name (the
     * registration probed them). Rows without a streamId can never play
     * and are skipped.
     */
    fun buildMovieChannels(movies: List<Movie>, current: Channel?): List<Channel> {
        val currentKey = current?.key
        val currentUrl = current?.directUrl
        val currentCmd = current?.stalkerCmd
        return movies.mapIndexedNotNull { i, m ->
            val sid = m.streamId ?: return@mapIndexedNotNull null
            val key = "vodm:$sid"
            val isCurrent = key == currentKey
            Channel(
                playlistId = m.playlistId,
                key = key,
                num = i + 1,
                name = if (isCurrent) (current?.name ?: m.name) else m.name,
                logo = if (isCurrent) (current?.logo ?: m.poster) else m.poster,
                categoryId = m.categoryId,
                streamId = sid,
                directUrl = if (isCurrent) currentUrl else null,
                stalkerCmd = if (isCurrent) currentCmd else null
            )
        }.distinctBy { it.key }
    }
}
