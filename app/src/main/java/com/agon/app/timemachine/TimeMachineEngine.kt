package com.agon.app.timemachine

import android.util.Log
import com.agon.app.data.model.SessionData
import com.agon.app.data.model.StreamItem
import com.agon.app.data.repository.PlaylistRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * TimeMachineEngine
 *
 * Implements the "Time Machine" feature: lets the user rewind a live
 * channel to a program that already aired (within the catch-up window).
 *
 * Xtream Codes servers support catch-up via a special URL format:
 *   /live/<user>/<pass>/<streamId>.m3u8?duration=<seconds>&start=<unix_ts>
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

    /** Returns live channels that support catch-up (Xtream-only detection). */
    fun getCatchupChannels(): List<StreamItem> {
        return SessionData.liveStreams.filter { stream ->
            val url = stream.url
            url.contains("/live/") && url.endsWith(".m3u8") && extractStreamId(url) != null
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
     */
    suspend fun getRecentPrograms(streamUrl: String, maxHours: Int = 24): List<PastProgram> {
        val streamId = extractStreamId(streamUrl) ?: return emptyList()
        val now = System.currentTimeMillis() / 1000
        val cutoff = now - (maxHours * 3600L)

        val epg = try {
            PlaylistRepository.getShortEpg(streamId)
        } catch (_: Throwable) { null } ?: return emptyList()

        val programs = mutableListOf<PastProgram>()
        if (epg.startTimestamp > 0 && epg.stopTimestamp > epg.startTimestamp) {
            val durationSec = (epg.stopTimestamp - epg.startTimestamp) / 1000
            val slotDurationSec = durationSec.coerceAtMost(3600L)
            var slotStart = epg.startTimestamp / 1000 - slotDurationSec
            var slotIndex = 1
            while (slotStart > cutoff && slotIndex <= maxHours) {
                val catchupUrl = buildCatchupUrl(streamUrl, slotStart, slotDurationSec)
                if (catchupUrl != null) {
                    programs.add(
                        PastProgram(
                            title = "${epg.title} (replay -${slotIndex}h)",
                            description = epg.description,
                            startUnix = slotStart,
                            endUnix = slotStart + slotDurationSec,
                            durationMinutes = (slotDurationSec / 60).toInt(),
                            catchupUrl = catchupUrl,
                            channelName = "",
                            channelLogo = ""
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
