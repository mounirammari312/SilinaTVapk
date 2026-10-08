package com.superz.iptvplayer.data.timemachine

import android.util.Log
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.epg.EpgProgram

/**
 * TimeMachineEngine — copied from the reference app (silinatv-pro-v111,
 * timemachine/TimeMachineEngine.kt).
 *
 * Implements the "Time Machine" feature: lets the user rewind a live
 * channel to a program that already aired (within the catch-up window).
 *
 * Xtream Codes servers support catch-up via a special URL format:
 *   /live/<user>/<pass>/<streamId>.m3u8?duration=<seconds>&start=<unix_ts>
 *
 * v1.18.0 adaptation notes (engine logic itself is 100% the reference's):
 *   • the reference reads SessionData.liveStreams (its global in-memory
 *     list); ours takes the playlist + the Room channel list as parameters
 *     (our channels don't carry the raw /live/ URL — it is synthesized the
 *     same way StreamUrls.variants does it);
 *   • the reference calls PlaylistRepository.getShortEpg(streamId)
 *     internally; ours receives the already-fetched EPG row (our
 *     EpgRepository.shortEpg, same get_short_epg endpoint, cached) so the
 *     engine stays pure and unit-testable;
 *   • PastProgram timestamps: the reference's LiveEpgData carries
 *     start/stop in MILLISECONDS — our EpgProgram uses the same millis
 *     convention, so the slot math is untouched.
 */
object TimeMachineEngine {

    private const val TAG = "TimeMachine"

    data class PastProgram(
        val title: String,
        val description: String,
        val startUnix: Long,
        val endUnix: Long,
        val durationMinutes: Int,
        val catchupUrl: String,
        val channelName: String,
        val channelLogo: String
    ) {
        val timeAgoLabel: String
            get() {
                val now = System.currentTimeMillis() / 1000
                val diffSec = now - startUnix
                val hours = diffSec / 3600
                val days = hours / 24
                return when {
                    days >= 1 -> "${days}d ago"
                    hours >= 1 -> "${hours}h ago"
                    else -> "${diffSec / 60}m ago"
                }
            }
    }

    /** The stream's /live/ .m3u8 URL — the catchup URL's base (mirrors StreamUrls.variants). */
    fun liveM3u8Url(playlist: Playlist, streamId: Long): String? {
        val base = playlist.server?.trimEnd('/') ?: return null
        val u = playlist.username ?: return null
        val p = playlist.password ?: return null
        if (base.isBlank() || u.isBlank() || p.isBlank()) return null
        return "$base/live/$u/$p/$streamId.m3u8"
    }

    /** The reference's catchup-channel test: a /live/ URL ending .m3u8 with a numeric id. */
    fun isCatchupChannel(streamUrl: String): Boolean =
        streamUrl.contains("/live/") && streamUrl.endsWith(".m3u8") && extractStreamId(streamUrl) != null

    /**
     * Returns the playlist's live channels that can use the Time Machine
     * (the reference's getCatchupChannels — flag-agnostic: any /live/
     * .m3u8 channel with a numeric stream id qualifies; the SERVER decides
     * whether a rewind actually plays).
     */
    fun getCatchupChannels(playlist: Playlist, channels: List<Channel>): List<Channel> {
        if (playlist.type != "XTREAM") return emptyList()
        return channels.filter { ch ->
            ch.streamId != null && liveM3u8Url(playlist, ch.streamId!!) != null
        }
    }

    /** Builds a catch-up URL for the given stream + start time + duration. */
    fun buildCatchupUrl(streamUrl: String, startUnix: Long, durationSeconds: Long): String? {
        val streamId = extractStreamId(streamUrl) ?: return null
        return "$streamUrl?duration=$durationSeconds&start=$startUnix"
    }

    private fun extractStreamId(url: String): String? {
        return try {
            val withoutExt = url.substringBefore(".m3u8")
            val lastSegment = withoutExt.substringAfterLast("/")
            if (lastSegment.all { it.isDigit() } && lastSegment.isNotEmpty()) lastSegment
            else null
        } catch (_: Throwable) { null }
    }

    /**
     * Fetches recent programs for a channel that can be re-watched via
     * catch-up. Returns programs that aired in the last [maxHours] hours.
     *
     * The reference's exact slot math: the CURRENT short-EPG program's
     * duration (capped at 1h) becomes the slot length; slots walk backwards
     * hour by hour from the current program's start, each labeled
     * "<title> (replay -Nh)" and carrying its own catch-up URL.
     */
    fun getRecentPrograms(
        streamUrl: String,
        epg: EpgProgram?,
        maxHours: Int = 24,
        channelName: String = "",
        channelLogo: String = ""
    ): List<PastProgram> {
        val streamId = extractStreamId(streamUrl) ?: return emptyList()
        val now = System.currentTimeMillis() / 1000
        val cutoff = now - (maxHours * 3600L)

        val programs = mutableListOf<PastProgram>()
        if (epg != null && epg.startMs > 0 && epg.endMs > epg.startMs) {
            val durationSec = (epg.endMs - epg.startMs) / 1000
            val slotDurationSec = durationSec.coerceAtMost(3600L)
            var slotStart = epg.startMs / 1000 - slotDurationSec
            var slotIndex = 1
            while (slotStart > cutoff && slotIndex <= maxHours) {
                val catchupUrl = buildCatchupUrl(streamUrl, slotStart, slotDurationSec)
                if (catchupUrl != null) {
                    programs.add(
                        PastProgram(
                            title = "${epg.title} (replay -${slotIndex}h)",
                            description = epg.description ?: "",
                            startUnix = slotStart,
                            endUnix = slotStart + slotDurationSec,
                            durationMinutes = (slotDurationSec / 60).toInt(),
                            catchupUrl = catchupUrl,
                            channelName = channelName,
                            channelLogo = channelLogo
                        )
                    )
                }
                slotStart -= slotDurationSec
                slotIndex++
            }
        }

        Log.i(TAG, "Time Machine: generated ${programs.size} past programs for stream $streamId")
        return programs
    }
}
