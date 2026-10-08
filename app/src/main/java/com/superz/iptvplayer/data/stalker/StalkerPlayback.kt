package com.superz.iptvplayer.data.stalker

import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.Playlist

/**
 * v1.5.0 — play-time URL resolution for stalker portals.
 *
 * The reference (LivePlayActivity / ChannelListRecyclerAdapter) calls
 * `type=itv&action=create_link` EVERY time a channel opens — the returned
 * `js.cmd` ("ffmpeg http://…") carries a time-limited tmp link. We do
 * exactly that, at the single place playback starts:
 * ChannelViewViewModel.playAt/retry. The resolved URL lands in a channel
 * COPY's `directUrl`, which StreamUrls (EngineRouter.kt — an iron-rule
 * file, untouched) already plays natively for any playlist type.
 *
 * The handshake is cached per playlist (45 min TTL) so zapping stays fast:
 * only the create_link call runs per channel switch.
 *
 * v1.6.1 — create_link's cmd now follows the reference's getLiveStreamUrl
 * EXACTLY: modern portal.php portals receive the synthesized
 * "ffmpeg http://localhost/ch/<id>_" form (the channel's stored cmd gets
 * rewritten by such portals into a dead "stream="-empty link — the
 * every-channel-fails bug on mag.max-cdn.com), legacy stalker_portal
 * portals receive the stored cmd verbatim. The channel's stalker id lives
 * in its DB key ("k:<id>", written by syncStalker).
 */
object StalkerPlayback {

    private const val SESSION_TTL_MS = 45 * 60 * 1000L

    private class CachedSession(val session: StalkerSession, val at: Long)

    @Volatile
    private var cached: CachedSession? = null

    /** Test hook — clears the session cache. */
    fun reset() {
        cached = null
    }

    /**
     * Cached session for the playlist, refreshed when stale. v1.7.0: PUBLIC
     * — the EPG repository shares the exact same handshake/token cache so
     * EPG calls never trigger a second handshake while playback is live.
     */
    suspend fun session(app: IPTVApp, playlist: Playlist): StalkerSession {
        val base = playlist.server ?: throw StalkerException("PORTAL_URL_MISSING")
        val mac = playlist.username ?: throw StalkerException("MAC_MISSING")
        val hit = cached
        if (hit != null && hit.session.base == base && hit.session.mac == mac &&
            System.currentTimeMillis() - hit.at < SESSION_TTL_MS
        ) {
            return hit.session
        }
        val client = StalkerClient(app.okHttp)
        val fresh = client.handshake(base, mac)
        cached = CachedSession(fresh, System.currentTimeMillis())
        return fresh
    }

    /** Test hook — the TTL window, injectable for fast tests. */
    internal fun isStale(at: Long): Boolean =
        System.currentTimeMillis() - at >= SESSION_TTL_MS

    /**
     * v1.12.5 — the shared-cache refresh hook (StalkerClient.authedCall's
     * onRenewed): the very first caller after a renewal hands every later
     * caller the fresh session.
     */
    fun onSessionRefreshed(session: StalkerSession) {
        cached = CachedSession(session, System.currentTimeMillis())
    }

    /**
     * v1.12.5 — the playlist-level renewal wrapper: run [block] with the
     * cached session; on HTTP 401/403 the token died (portals bind ONE
     * token per MAC — the reference app handshaking the same account kills
     * ours mid-session), so re-handshake, adopt the fresh session in the
     * shared cache, and retry the block ONCE. This is the v1.10.0 authGet
     * contract, restored: every authenticated stalker call routes here.
     */
    suspend fun <T> authed(
        app: IPTVApp,
        playlist: Playlist,
        block: suspend (StalkerSession) -> T
    ): T {
        val base = playlist.server ?: throw StalkerException("PORTAL_URL_MISSING")
        val mac = playlist.username ?: throw StalkerException("MAC_MISSING")
        val first = session(app, playlist)
        return StalkerClient(app.okHttp).authedCall(first, ::onSessionRefreshed, block)
    }

    /**
     * Returns a playable channel: PORTAL channels get their resolved URL in
     * `directUrl`; every other type (and every unresolvable channel) passes
     * through untouched. Mirrors the reference's behavior: create_link with
     * the reference's own cmd choice — the synthesized localhost form for
     * modern portal.php portals, the stored cmd for legacy stalker_portal
     * ones — then strip the "ffmpeg "/"auto " prefix; if create_link
     * fails, fall back to a direct URL embedded in the stored cmd.
     */
    suspend fun resolveChannel(app: IPTVApp, playlist: Playlist, channel: Channel): Channel {
        if (playlist.type != "PORTAL") return channel
        // v1.11.0 — VOD / episode channels route through the VOD resolver.
        // v1.12.0 — catch-up programs (vodc:) do too.
        if (channel.key.startsWith("vodm:") || channel.key.startsWith("vode:") ||
            channel.key.startsWith("vodc:")
        ) {
            return resolveVod(app, playlist, channel)
        }
        if (channel.stalkerCmd == null && !channel.key.startsWith("k:")) return channel
        val client = StalkerClient(app.okHttp)
        val resolved = try {
            // v1.12.5 — renewed on 401/403 (the token can die mid-session;
            // the fresh handshake feeds the shared cache + the retry).
            StalkerPlayback.authed(app, playlist) { session ->
                // The channel's stalker id — syncStalker writes key "k:<id>".
                val streamId = channel.key.takeIf { it.startsWith("k:") }?.removePrefix("k:")
                val cmd = StalkerUrls.createLinkCmd(session.html, streamId, channel.stalkerCmd)
                    // nothing to send — the caller falls back to the stored cmd
                    ?: return@authed null
                StalkerUrls.streamUrlFromCmd(client.createLink(session, cmd))?.also { url ->
                    registerMediaScope(session, url)
                }
            }
        } catch (e: Exception) {
            null    // fall back to the stored cmd's own URL, if any
        }
        val url = resolved ?: StalkerUrls.streamUrlFromCmd(channel.stalkerCmd)
            ?: return channel    // nothing playable — the UI shows its error state
        return channel.copy(directUrl = url)
    }

    /**
     * v1.11.0 — play-time URL resolution for the synthetic VOD channels
     * ("vodm:{streamId}" movies / "vode:{id}" episodes), replicated from
     * the reference's play activities:
     *
     *  • Ministra movie    (MoviePlayerActivity.getMovieUrl):
     *      create_link(cmd = movie.cmd) → strip ffmpeg/auto/whitespace.
     *  • Ministra episode  (SeriesPlayActivity):
     *      create_link(cmd = season.cmd, series = epNum).
     *  • HTML movie        (getVodItem → getVodLink):
     *      single-item lookup (movie_id, 0, 0, category) → file id →
     *      cmd = "/media/file_<id>.mpg" → create_link(cmd).
     *  • HTML episode      (getEpisodeItem → getEpisodeLink):
     *      single-item lookup (movie_id, seasonId, episodeId, category) →
     *      file id → "/media/file_<id>.mpg" → create_link(cmd, series).
     *
     * Every failure passes the channel through untouched (the player shows
     * its error state, exactly like the reference's releaseMediaPlayer +
     * progressBar path). The tmp URL's host (and the portal host) join the
     * media-headers registry so the OkHttp interceptor stamps the
     * reference's media User-Agent on the segments.
     */
    suspend fun resolveVod(app: IPTVApp, playlist: Playlist, channel: Channel): Channel {
        if (playlist.type != "PORTAL") return channel
        val isVod = channel.key.startsWith("vodm:") || channel.key.startsWith("vode:") ||
            channel.key.startsWith("vodc:")
        if (!isVod) return channel
        // v1.12.0 — catch-up programs FIRST: the cmd is family-correct and
        // built from the key itself (the program's file id), exactly the
        // reference's CatchUpPlayActivity.getStreamUrl(epgModel.getId()).
        if (channel.key.startsWith("vodc:")) {
            val fileId = channel.key.removePrefix("vodc:")
            val client = StalkerClient(app.okHttp)
            if (fileId.isNotEmpty()) {
                val url = try {
                    // v1.12.5 — renewed on 401/403 (fresh create_link needs
                    // a live token; playback restarts depend on this).
                    StalkerPlayback.authed(app, playlist) { session ->
                        val cmd = StalkerUrls.catchCmd(fileId, session.html)
                        StalkerUrls.playUrlFromCmd(client.createCatchLink(session, cmd))
                            ?.also { u -> registerMediaScope(session, u) }
                    }
                } catch (e: Exception) {
                    null
                }
                if (url != null) return channel.copy(directUrl = url)
            }
            return channel
        }
        val ref = StalkerVodRefs.get("${playlist.id}:${channel.key}")
        val client = StalkerClient(app.okHttp)
        val url = try {
            // v1.12.5 — renewed on 401/403 (fresh create_link needs a live
            // token — the reference re-handshakes at every app start).
            StalkerPlayback.authed(app, playlist) { session ->
                val rawCmd: String? = when {
                    // HTML mode (IsStalkerHtml) — two-step: file-id lookup, then
                    // create_link (MoviePlayerActivity.getVodItem / SeriesPlayActivity).
                    session.html && ref?.htmlItem != null -> {
                        val parts = ref.htmlItem.split("|")
                        if (parts.size < 4) null else {
                            val fileId = client.episodeItem(session, parts[0], parts[1], parts[2], parts[3])
                            if (fileId != null) {
                                val fileCmd = "/media/file_$fileId.mpg"
                                if (ref.seriesNum != null) {
                                    client.createSeriesLink(session, fileCmd, ref.seriesNum)
                                } else {
                                    client.createVodLink(session, fileCmd)
                                }
                            } else null
                        }
                    }
                    // Ministra episodes — season cmd + the series number.
                    ref?.cmd != null && ref.seriesNum != null ->
                        client.createSeriesLink(session, ref.cmd!!, ref.seriesNum)
                    // Ministra movies — the row cmd.
                    ref?.cmd != null -> client.createVodLink(session, ref.cmd)
                    // Fallback — a channel that carried its cmd directly.
                    channel.stalkerCmd != null -> client.createVodLink(session, channel.stalkerCmd)
                    else -> null
                }
                StalkerUrls.playUrlFromCmd(rawCmd)?.also { u -> registerMediaScope(session, u) }
            }
        } catch (e: Exception) {
            null
        }
        if (url == null) return channel
        return channel.copy(directUrl = url)
    }

    /**
     * v1.11.0 — the media-host scope: the tmp URL's host AND the portal
     * host get the reference's media User-Agent from the app-wide OkHttp
     * network interceptor (the reference sets ONLY a UA on media —
     * "VU IPTV Player" default; tmp links carry their own play_token).
     */
    private fun registerMediaScope(session: StalkerSession, url: String) {
        StalkerMediaHeaders.registerFromUrl(url)
        StalkerMediaHeaders.registerFromUrl(session.base)
    }
}
