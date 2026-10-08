package com.superz.iptvplayer.player

import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.EngineMemory
import com.superz.iptvplayer.data.db.Playlist

/** Playback engine. */
enum class Engine { EXO, VLC }

/** One attempt in the fallback chain. */
data class PlayAttempt(
    val engine: Engine,
    val url: String,
    val variant: String?    // "ts" | "m3u8" | null (direct URL)
)

/** Decides which engine handles a URL before any memory is consulted. */
object EngineRouter {
    fun route(url: String): Engine = when {
        url.startsWith("rtsp:", true) || url.startsWith("rts:", true) ||
            url.startsWith("rtmp:", true) || url.startsWith("rtmpe:", true) ||
            url.startsWith("rtmps:", true) || url.startsWith("rtmpt:", true) ||
            url.startsWith("udp:", true) || url.startsWith("rtp:", true) ||
            url.startsWith("mms:", true) || url.startsWith("mmsh:", true) ||
            url.startsWith("srt:", true) -> Engine.VLC
        else -> Engine.EXO
    }
}

/** Builds every playable URL variant for a channel. */
object StreamUrls {

    fun variants(playlist: Playlist, channel: Channel): List<Pair<String, String?>> {
        val out = mutableListOf<Pair<String, String?>>()
        // directSource from Xtream (already a full URL) or M3U URL
        channel.directUrl?.let { direct ->
            if (direct.startsWith("http", true)) out += direct to null
        }
        if (channel.streamId != null && playlist.type == "XTREAM") {
            val base = playlist.server?.trimEnd('/') ?: ""
            val u = playlist.username ?: ""
            val p = playlist.password ?: ""
            if (base.isNotBlank() && u.isNotBlank() && p.isNotBlank()) {
                out += "$base/live/$u/$p/${channel.streamId}.ts" to "ts"
                out += "$base/live/$u/$p/${channel.streamId}.m3u8" to "m3u8"
            }
        }
        return out.distinctBy { it.first }
    }

    /** The URL of the first (primary) attempt — used for preconnect/preload. */
    fun primary(playlist: Playlist, channel: Channel, memory: Map<String, EngineMemory>): String? {
        val remembered = memory[channel.key]
        if (remembered != null) {
            val all = variants(playlist, channel)
            val url = all.firstOrNull { it.second == remembered.variant } ?: all.firstOrNull()
            if (url != null) return url.first
        }
        val direct = channel.directUrl
        if (direct != null && direct.startsWith("http", true)) return direct
        return variants(playlist, channel).firstOrNull()?.first
    }

    /** Full fallback chain for a channel (remembered attempt first). */
    fun buildChain(playlist: Playlist, channel: Channel, remembered: EngineMemory?): List<PlayAttempt> {
        // v1.19.13 — LOCAL saved-library entries ("vodm:local:", the loopback
        // URL from LocalMediaServer) NEVER consult engine memory. The chain
        // is always EXO-first with VLC as the codec-fallback of last resort.
        // WHY: memory is a per-KEY zap accelerator for NETWORK streams — one
        // old EXO timeout on a local file (v1.18.1–v1.19.12 ran the loopback
        // URL through the 512 MB disk cache, which multi-GB saves can thrash
        // past the 15 s attempt budget) let VLC win once, rememberEngine
        // pinned "VLC" to that index, and EVERY later open started on VLC —
        // the engine with NO bottom time bar ("المشغل بلا شريط أحمر أو مؤقت").
        // A local HTTP file is ExoPlayer's home turf; it always gets the
        // first shot. (buildMediaSource now also bypasses the disk cache for
        // loopback URLs, so the original timeout cause is gone too.)
        if (channel.key.startsWith("vodm:local:") && channel.directUrl != null) {
            val url = channel.directUrl
            return if (EngineRouter.route(url) == Engine.EXO) {
                listOf(PlayAttempt(Engine.EXO, url, null), PlayAttempt(Engine.VLC, url, null))
            } else {
                listOf(PlayAttempt(Engine.VLC, url, null), PlayAttempt(Engine.EXO, url, null))
            }
        }

        val isM3uDirect = playlist.type == "M3U" || (channel.streamId == null && channel.directUrl != null)
        val attempts = mutableListOf<PlayAttempt>()

        if (isM3uDirect) {
            val url = channel.directUrl ?: return emptyList()
            val primary = EngineRouter.route(url)
            val secondary = if (primary == Engine.EXO) Engine.VLC else Engine.EXO
            attempts += PlayAttempt(primary, url, null)
            attempts += PlayAttempt(secondary, url, null)
        } else {
            val variants = variants(playlist, channel)
            if (variants.isEmpty()) return emptyList()
            // Xtream order: exo(.ts) → exo(.m3u8) → vlc(.ts) → vlc(.m3u8)
            for ((url, variant) in variants) {
                attempts += PlayAttempt(Engine.EXO, url, variant)
            }
            attempts += PlayAttempt(Engine.VLC, variants.first().first, variants.first().second)
            variants.getOrNull(1)?.let { (url, variant) ->
                attempts += PlayAttempt(Engine.VLC, url, variant)
            }
        }

        if (remembered != null) {
            val first = attempts.indexOfFirst {
                it.engine.name == remembered.engine && it.variant == remembered.variant
            }
            if (first > 0) {
                val rememberedAttempt = attempts.removeAt(first)
                attempts.add(0, rememberedAttempt)
            }
        }
        return attempts
    }
}
