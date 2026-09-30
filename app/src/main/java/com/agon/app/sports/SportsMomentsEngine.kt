package com.agon.app.sports

import android.content.Context
import android.util.Log
import com.agon.app.data.repository.MatchHarvesterRepository
import com.agon.app.proxy.ClipBufferManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SportsMomentsEngine
 *
 * Automatically detects score changes in live matches and saves 30s clips.
 * Polls MatchHarvesterRepository every 30s; on score change, waits 15s
 * then dumps ClipBufferManager's last 30s to a file.
 */
object SportsMomentsEngine {

    private const val TAG = "SportsMoments"
    private const val POLL_INTERVAL_MS = 30_000L
    private const val POST_EVENT_DELAY_MS = 15_000L
    private const val CLIP_DURATION_S = 30

    data class SportsMoment(
        val title: String,
        val matchName: String,
        val timestamp: Long,
        val clipPath: String?
    )

    private val _moments = MutableStateFlow<List<SportsMoment>>(emptyList())
    val moments: StateFlow<List<SportsMoment>> = _moments.asStateFlow()

    private val lastScores = mutableMapOf<String, String>()

    @Volatile
    private var running = false

    suspend fun start(context: Context) {
        if (running) return
        running = true
        Log.i(TAG, "Sports Moments engine started — polling every ${POLL_INTERVAL_MS}ms")
        coroutineScope {
            while (running) {
                try {
                    val matches = MatchHarvesterRepository.getMatchesFromCache(context)
                    detectScoreChanges(context, matches)
                } catch (e: Exception) {
                    Log.w(TAG, "Poll error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        running = false
        Log.i(TAG, "Sports Moments engine stopped")
    }

    private suspend fun detectScoreChanges(
        context: Context,
        matches: List<MatchHarvesterRepository.HarvestedMatch>
    ) = withContext(Dispatchers.Default) {
        for (match in matches) {
            // status field may not exist on all HarvestedMatch versions;
            // treat all matches as LIVE candidates for score tracking.
            val key = "${match.homeTeam}|${match.awayTeam}"
            val currentScore = extractScore(match.broadcaster)
                ?: extractScore(match.homeTeam + " " + match.awayTeam)
                ?: continue
            val prevScore = lastScores[key]
            if (prevScore != null && prevScore != currentScore) {
                Log.i(TAG, "SCORE CHANGE: $key  $prevScore → $currentScore")
                onScoreChange(context, match, prevScore, currentScore)
            }
            lastScores[key] = currentScore
        }
    }

    private suspend fun onScoreChange(
        context: Context,
        match: MatchHarvesterRepository.HarvestedMatch,
        oldScore: String,
        newScore: String
    ) {
        delay(POST_EVENT_DELAY_MS)
        val clipPath = try {
            ClipBufferManager.dumpLastSeconds(context, CLIP_DURATION_S)
        } catch (e: Exception) {
            Log.w(TAG, "Auto-clip failed: ${e.message}")
            null
        }
        val moment = SportsMoment(
            title = "Score: $oldScore → $newScore",
            matchName = "${match.homeTeam} vs ${match.awayTeam}",
            timestamp = System.currentTimeMillis(),
            clipPath = clipPath
        )
        _moments.value = (_moments.value + moment).takeLast(50)
        Log.i(TAG, "Sports moment captured: ${moment.matchName} — ${moment.title} → ${moment.clipPath ?: "no clip"}")
    }

    private fun extractScore(text: String): String? {
        val regex = Regex("\\b(\\d+)\\s*[-:]\\s*(\\d+)\\b")
        return regex.find(text)?.value
    }
}
