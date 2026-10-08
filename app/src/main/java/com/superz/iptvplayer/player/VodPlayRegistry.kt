package com.superz.iptvplayer.player

import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.Playlist
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.4.0 — hand-off bridge between the VOD info pages and the fullscreen
 * player (SAME route, SAME screen, SAME locked engine).
 *
 * A movie or an episode is described to the engine exactly like a channel:
 * a synthetic [Channel] whose `directUrl` is the standard Xtream
 * `/movie|/series/…` link. EngineRouter routes it (http → EXO first, VLC
 * fallback), engine memory keys on it (per-title zap acceleration), and
 * SmartPlayer's chain plays it — ZERO engine code was touched.
 *
 * The registry itself is intentionally tiny and in-memory: the player
 * route survives process death, the registry does not — in that rare case
 * the player falls back to its legacy DB bootstrap (empty for VOD) and the
 * user simply re-taps the episode. No session, no stream, no data at risk.
 */
object VodPlayRegistry {

    /**
     * v1.18.0 — the VOD item's subtitle metadata, handed to the player with
     * the request so the subtitle engine can resolve the EXACT movie:
     * imdbId (layer 1 — panels that send it), year (layer 2 — remakes and
     * same-title movies disambiguated) and the clean title (episodes get
     * "SeriesName S01E05" composed from it + the channel name).
     */
    data class SubtitleMeta(
        val imdbId: String? = null,
        val year: Int? = null,
        val cleanName: String? = null
    )

    data class Request(
        val playlist: Playlist,
        /** Synthetic channels — one movie, or one season's episodes (zapping works). */
        val channels: List<Channel>,
        val startIndex: Int,
        /** VOD favorite this playback maps to ("m:123" / "sr:456"). */
        val favoriteKey: String,
        /** "MOVIE" | "SERIES" */
        val favoriteType: String,
        /** v1.18.0 — subtitle resolution hints (null on live/catch-up). */
        val subtitleMeta: SubtitleMeta? = null
    )

    private val requests = ConcurrentHashMap<String, Request>()

    fun put(key: String, request: Request) {
        requests[key] = request
    }

    fun consume(key: String): Request? = requests.remove(key)
}
