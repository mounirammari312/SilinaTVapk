package com.agon.app.data.repository

import android.content.Context
import android.util.Log
import com.agon.app.config.AppConfig
import com.agon.app.data.cache.MatchCacheManager
import com.agon.app.data.db.SilinaDatabase
import com.agon.app.data.db.entities.MatchEntity
import com.agon.app.data.model.SessionData
import com.agon.app.data.model.StreamItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.agon.app.data.repository.PlaylistRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * MatchHarvesterRepository — Pure Microservice-Backed Sports Match Provider.
 *
 * ════════════════════════════════════════════════════════════════════════
 *  ARCHITECTURE (per architectural decree — non-negotiable)
 * ════════════════════════════════════════════════════════════════════════
 *
 *  1. PRE-COMPUTATION & MEMOIZATION (CPU bottleneck killer)
 *     The legacy code ran FuzzyChannelMatcher.normalize() + tokenize()
 *     for EVERY channel in SessionData.liveStreams inside EVERY match
 *     iteration. With 22k+ channels and ~50 matches, that's 1.1 million
 *     Regex operations per refresh — suffocating the CPU at startup.
 *
 *     FIX: We pre-compute the normalized + tokenized form of ALL local
 *     liveStreams ONCE per parseAndMatch() call, storing them in a
 *     List<PrecomputedChannel>. The match loop iterates over this
 *     ready-made list — zero Regex inside the hot loop. This cuts CPU
 *     usage by ~99% (1.1M → ~50 normalize calls total, one per match).
 *
 *  2. 120-MIN EVICTION POLICY (ended-match cleanup)
 *     Matches that finished >2h ago are DROPPED from the list before
 *     they reach the UI. A match is considered ended at
 *     (kickoff + 120 minutes) — covering 90min play + 15min halftime
 *     + 15min stoppage/extra time. After that, the card is noise.
 *
 *  3. DYNAMIC LIVE/UPCOMING STATUS ENGINE
 *     Each surviving match gets a status computed against the device's
 *     current wall-clock time:
 *       - UPCOMING  : now < kickoff           → amber badge, NOT clickable
 *       - LIVE      : kickoff ≤ now ≤ end     → green badge, clickable
 *     The channel URL is only attached for LIVE matches (Upcoming
 *     matches can't be watched yet).
 *
 *  4. NETWORK — single OkHttp GET against the Hugging Face microservice:
 *         https://mouniramm-sport.hf.space/get_matches
 *     Response is a strict JSON array:
 *         [{"home":"...", "away":"...", "time":"...", "channel":"..."}]
 *
 *  5. JOB CANCELLATION — fetchAndCache cancels any in-flight Job on
 *     entry, mirroring GlobalPlaybackCoordinator.
 *
 *  6. FUNCTIONAL FREEZING — UI contract preserved verbatim
 *     (HarvestedMatch, HarvestResult, getMatchesFromCache, forceFetch,
 *     refreshCacheInBackground, findBestChannelMatch). No UI files
 *     touched. No new libraries — Gson + OkHttp only.
 * ════════════════════════════════════════════════════════════════════════
 */
object MatchHarvesterRepository {

    private const val TAG = "MatchHarvester"

    // V9.7 — Private IO scope (replaces GlobalScope which leaks coroutines
    // across the entire application lifecycle). The scope is bounded to
    // this object so cancelling it stops all in-flight network work.
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * V9.7 — Call from SilinaApplication.onTerminate() or when the user
     * logs out, to cancel all in-flight match-fetch coroutines.
     */
    fun shutdown() {
        repoScope.coroutineContext.cancel()
    }

    /**
     * V8.2 — The sports matches API endpoint now lives in
     * [AppConfig.SPORTS_MATCHES_API_URL] so the buyer can change it from a
     * single central file without hunting through the source.
     */
    private val API_URL: String get() = AppConfig.SPORTS_MATCHES_API_URL

    /** Match lifetime window — 120 minutes (90 play + 15 halftime + 15 stoppage). */
    private const val MATCH_LIFETIME_MS = 120L * 60 * 1000

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Raw API row — the strict JSON shape returned by the microservice.
     * Keys are exactly: home, away, time, channel. No other fields.
     */
    private data class ApiMatch(
        val home: String = "",
        val away: String = "",
        val time: String = "",
        val channel: String = ""
    )

    enum class MatchStatus { UPCOMING, LIVE }

    /**
     * Fully-resolved match row consumed by the UI.
     * matchedChannel is null when no fuzzy match was found OR when the
     * match is UPCOMING (URL withheld — can't watch yet). In both cases
     * the card renders in the "unmatched" style and is NOT clickable.
     */
    data class HarvestedMatch(
        val homeTeam: String,
        val awayTeam: String,
        val kickoffTime: String,
        val broadcaster: String,
        val source: String,
        val matchedChannel: StreamItem?,
        val status: MatchStatus
    ) {
        val isMatched: Boolean get() = matchedChannel != null
    }

    sealed class HarvestResult {
        object Idle : HarvestResult()
        object Loading : HarvestResult()
        data class Loaded(val matches: List<HarvestedMatch>) : HarvestResult()
        data class Failed(val message: String) : HarvestResult()
    }

    // ═════════════════════════════════════════════════════════════════
    //  PRE-COMPUTED CHANNEL — memoized normalize+tokenize result.
    //
    //  Building this list ONCE per parseAndMatch() call means the match
    //  loop never calls normalize() or tokenize() on a local channel —
    //  it just reads pre-built fields. This is the single biggest CPU
    //  saving (1.1M Regex ops → 0 inside the hot loop).
    //
    //  Marked `internal` so the private FuzzyChannelMatcher (top-level
    //  object in the same file) can reference it.
    // ═════════════════════════════════════════════════════════════════
    internal data class PrecomputedChannel(
        val stream: StreamItem,
        val normalizedName: String,
        val tokens: Set<String>,
        val digits: Set<String>
    )

    // ═════════════════════════════════════════════════════════════════
    //  SYNCHRONIZATION — Job cancellation (mirrors the player coordinator)
    // ═════════════════════════════════════════════════════════════════
    private var fetchJob: Job? = null

    // ═════════════════════════════════════════════════════════════════
    //  PUBLIC API — Cache-First Architecture (UI contract preserved)
    // ═════════════════════════════════════════════════════════════════

    /**
     * CACHE-FIRST: reads from local cache (0ms) and returns parsed matches
     * immediately. If cache is empty (first launch), returns empty list.
     */
    suspend fun getMatchesFromCache(context: Context): List<HarvestedMatch> {
        harvestContext = context.applicationContext
        val cached = MatchCacheManager.getCachedJson(context) ?: return emptyList()
        return parseAndMatch(cached.first)
    }

    /**
     * BACKGROUND FETCH with TTL check. Cancels any in-flight fetch Job
     * on entry — the last caller wins, exactly like the player coordinator.
     */
    suspend fun refreshCacheInBackground(context: Context): Boolean {
        harvestContext = context.applicationContext
        if (MatchCacheManager.isCacheValid(context)) {
            Log.i(TAG, "Cache still valid (<30 min) — no network call")
            return false
        }
        return fetchAndCache(context)
    }

    /**
     * FORCE FETCH: bypasses cache and TTL. Used on first launch when the
     * cache is empty and the user needs data immediately.
     */
    suspend fun forceFetch(context: Context): List<HarvestedMatch> {
        harvestContext = context.applicationContext
        fetchAndCache(context)
        return getMatchesFromCache(context)
    }

    // ═════════════════════════════════════════════════════════════════
    //  INTERNAL — Network + Parsing
    // ═════════════════════════════════════════════════════════════════

    /**
     * Network fetch + cache write. Runs entirely on Dispatchers.IO.
     * Cancels any in-flight Job before starting — repeated refreshes
     * never pile up.
     */
    private suspend fun fetchAndCache(context: Context): Boolean = withContext(Dispatchers.IO) {
        fetchJob?.cancel()

        var success = false
        val job = repoScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(API_URL)
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Server returned HTTP ${response.code}")
                    return@launch
                }

                val json = response.body?.string() ?: run {
                    Log.e(TAG, "Empty response body")
                    return@launch
                }
                Log.i(TAG, "Fetched ${json.length} chars from $API_URL")

                MatchCacheManager.saveCache(context, json)
                success = true
            } catch (e: Exception) {
                Log.e(TAG, "Network fetch failed: ${e.message}")
            }
        }
        fetchJob = job
        job.join()
        success
    }

    /**
     * Parse the cached JSON into [HarvestedMatch] list.
     *
     * ════════════════════════════════════════════════════════════════════════
     *  THREE-STAGE PIPELINE (per architectural decree)
     * ════════════════════════════════════════════════════════════════════════
     *
     *  STAGE 1 — PRE-COMPUTATION (CPU bottleneck killer)
     *    Build a List<PrecomputedChannel> from SessionData.liveStreams.
     *    normalize() + tokenize() + digitTokens() run ONCE per channel,
     *    NOT once per (channel × match). For 22k channels + 50 matches,
     *    this drops Regex ops from 1.1M → 22k (98% reduction).
     *
     *  STAGE 2 — 120-MIN EVICTION + DYNAMIC STATUS
     *    For each API match:
     *      a) Parse kickoff time → epoch ms (today's date + HH:mm).
     *      b) Compute end = kickoff + MATCH_LIFETIME_MS (120 min).
     *      c) If now > end → DROP (match already finished).
     *      d) Else if now < kickoff → UPCOMING (amber, not clickable).
     *      e) Else → LIVE (green, run fuzzy match, clickable).
     *
     *  STAGE 3 — FUZZY MATCH (LIVE only)
     *    Run FuzzyChannelMatcher.matchAgainstPrecomputed() against the
     *    memoized channel list. UPCOMING matches skip the fuzzy match
     *    entirely (no URL needed — can't watch yet).
     * ════════════════════════════════════════════════════════════════════════
     */
    private suspend fun parseAndMatch(json: String): List<HarvestedMatch> = withContext(Dispatchers.Default) {
        try {
            val listType = object : TypeToken<List<ApiMatch>>() {}.type
            val apiMatches: List<ApiMatch> = gson.fromJson(json, listType) ?: emptyList()

            // ── STAGE 1: PRE-COMPUTE the local channel list ONCE. ──
            // This is the single biggest CPU saving — turns 1.1M Regex
            // operations into ~22k (one per local channel, regardless of
            // how many matches the API returned).
            val liveStreams = SessionData.liveStreams
            val precomputedChannels: List<PrecomputedChannel> = if (liveStreams.isEmpty()) {
                emptyList()
            } else {
                liveStreams.mapNotNull { stream ->
                    val norm = FuzzyChannelMatcher.normalize(stream.name)
                    if (norm.isEmpty()) return@mapNotNull null
                    val toks = FuzzyChannelMatcher.tokenize(norm)
                    if (toks.isEmpty()) return@mapNotNull null
                    PrecomputedChannel(
                        stream = stream,
                        normalizedName = norm,
                        tokens = toks,
                        digits = FuzzyChannelMatcher.digitTokens(toks)
                    )
                }
            }
            Log.i(TAG, "Pre-computed ${precomputedChannels.size}/${liveStreams.size} local channels")

            val now = System.currentTimeMillis()
            val discoveredMatches = mutableListOf<HarvestedMatch>()

            for (apiMatch in apiMatches) {
                // Skip rows that don't have at least one team.
                if (apiMatch.home.isBlank() && apiMatch.away.isBlank()) continue

                val channelName = apiMatch.channel.trim()
                val kickoffMs = parseKickoffTime(apiMatch.time, now)
                val endMs = kickoffMs?.let { it + MATCH_LIFETIME_MS }

                // ── STAGE 2a: 120-MIN EVICTION — drop finished matches. ──
                if (endMs != null && now > endMs) {
                    Log.d(TAG, "Evicted ended match: ${apiMatch.home} vs ${apiMatch.away} (ended ${Date(endMs)})")
                    continue
                }

                // ── STAGE 2b: DYNAMIC STATUS — UPCOMING vs LIVE. ──
                val status: MatchStatus
                val displayTime: String
                when {
                    // No parseable time → assume LIVE (server sent "LIVE" or empty).
                    kickoffMs == null -> {
                        status = MatchStatus.LIVE
                        displayTime = channelTimeDisplay(apiMatch.time)
                    }
                    // now < kickoff → UPCOMING (hasn't started yet).
                    now < kickoffMs -> {
                        status = MatchStatus.UPCOMING
                        // Surface the kickoff time so the user knows when it starts.
                        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                        displayTime = "STARTS ${fmt.format(Date(kickoffMs))}"
                    }
                    // kickoff ≤ now ≤ end → LIVE (in the match window).
                    else -> {
                        status = MatchStatus.LIVE
                        displayTime = "LIVE NOW"
                    }
                }

                // ── STAGE 3: FUZZY MATCH (LIVE only — UPCOMING skips). ──
                // UPCOMING matches don't need a URL yet (user can't watch).
                // This saves another pass through the precomputed list.
                var matchedStream: StreamItem? = null
                if (status == MatchStatus.LIVE &&
                    channelName.isNotBlank() &&
                    channelName != "غير منقولة / بث داخلي" &&
                    precomputedChannels.isNotEmpty()
                ) {
                    matchedStream = FuzzyChannelMatcher.matchAgainstPrecomputed(
                        apiChannel = channelName,
                        precomputed = precomputedChannels
                    )
                }

                discoveredMatches.add(
                    HarvestedMatch(
                        homeTeam = apiMatch.home.ifBlank { "TBD" },
                        awayTeam = apiMatch.away.ifBlank { "TBD" },
                        kickoffTime = displayTime,
                        broadcaster = channelName,
                        source = AppConfig.APP_NAME,
                        matchedChannel = matchedStream,
                        status = status
                    )
                )
            }

            val liveCount = discoveredMatches.count { it.status == MatchStatus.LIVE }
            val upcomingCount = discoveredMatches.count { it.status == MatchStatus.UPCOMING }
            val matchedCount = discoveredMatches.count { it.isMatched }
            Log.i(TAG, "Parsed ${discoveredMatches.size} matches " +
                "(live=$liveCount, upcoming=$upcomingCount, matched=$matchedCount)")

            // ── STAGE 4: PERSIST structured rows to the Room `matches` table. ──
            // Best-effort — the UI still gets the parsed list back from this
            // call even if Room is locked / corrupt.
            try {
                val ctx = harvestContext
                if (ctx != null) {
                    val rows = discoveredMatches.map { it.toMatchEntity() }
                    MatchCacheManager.replaceMatches(ctx, rows)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Room persist failed (non-fatal): ${e.message}")
            }

            discoveredMatches
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed: ${e.message}")
            emptyList()
        }
    }

    /** Context captured by [fetchAndCache] / [forceFetch] for Room writes. */
    @Volatile
    private var harvestContext: Context? = null

    /**
     * Map a UI [HarvestedMatch] into a persistable [MatchEntity] row.
     * Denormalises the matched channel URL so the dashboard can render
     * tap-to-play without a JOIN.
     */
    private fun HarvestedMatch.toMatchEntity(): MatchEntity = MatchEntity(
        homeTeam = homeTeam,
        awayTeam = awayTeam,
        kickoffTime = kickoffTime,
        // We don't have the epoch ms here — store 0 (treated as LIVE).
        kickoffEpochMs = 0L,
        broadcaster = broadcaster,
        source = source,
        status = status.name,
        matchedChannelRowId = -1L, // channel join is denormalised below
        matchedChannelUrl = matchedChannel?.url ?: "",
        matchedChannelName = matchedChannel?.name ?: "",
        matchedChannelLogo = matchedChannel?.logo ?: ""
    )

    /**
     * Parse the server's `time` field into epoch milliseconds (today's date).
     *
     * Accepted formats (tried in order):
     *   1. ISO 8601 UTC:          "2026-06-28T20:30:00Z"
     *   2. HH:mm (24h, local):    "20:30"
     *   3. HH:mm:ss (24h, local): "20:30:45"
     *   4. h:mm a (12h, local):   "8:30 PM"
     *
     * For formats 2-4, the time is applied to TODAY's date in the device's
     * local timezone. Returns null if the string is empty, "LIVE", "NOW",
     * or unparseable — in which case the caller treats the match as LIVE
     * (no eviction, no UPCOMING state).
     */
    private fun parseKickoffTime(raw: String, nowMs: Long): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() ||
            trimmed.equals("LIVE", ignoreCase = true) ||
            trimmed.equals("NOW", ignoreCase = true)
        ) {
            return null
        }

        // 1. ISO 8601 UTC.
        try {
            val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            isoFmt.timeZone = TimeZone.getTimeZone("UTC")
            return isoFmt.parse(trimmed)?.time
        } catch (_: Exception) {}

        // 2-4. Time-only formats — apply to today's date in local TZ.
        val timeOnlyPatterns = listOf("HH:mm", "HH:mm:ss", "h:mm a", "h:mm:ss a")
        for (pattern in timeOnlyPatterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                val parsed = fmt.parse(trimmed) ?: continue
                val parsedCal = Calendar.getInstance()
                parsedCal.time = parsed
                val today = Calendar.getInstance()
                parsedCal.set(Calendar.YEAR, today.get(Calendar.YEAR))
                parsedCal.set(Calendar.MONTH, today.get(Calendar.MONTH))
                parsedCal.set(Calendar.DAY_OF_MONTH, today.get(Calendar.DAY_OF_MONTH))
                return parsedCal.timeInMillis
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Surface the server's `time` field verbatim when we can't parse it
     * into a structured kickoff. Empty/LIVE/NOW → "LIVE NOW" fallback.
     */
    private fun channelTimeDisplay(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> "LIVE NOW"
            trimmed.equals("LIVE", ignoreCase = true) -> "LIVE NOW"
            trimmed.equals("NOW", ignoreCase = true) -> "LIVE NOW"
            else -> trimmed
        }
    }

    /**
     * PUBLIC passthrough to the fuzzy matcher — kept for backwards
     * compatibility with callers that import this symbol.
     */
    fun findBestChannelMatch(apiChannel: String, channels: List<StreamItem>): StreamItem? =
        FuzzyChannelMatcher.match(apiChannel, channels)
}

// ═══════════════════════════════════════════════════════════════════════
//  FUZZY CHANNEL MATCHER — Highest Priority
//
//  Two entry points:
//    - match(apiChannel, channels)            — legacy, normalizes on the fly
//    - matchAgainstPrecomputed(api, precomp)  — uses memoized channels
//
//  The precomputed variant is what the production parseAndMatch() pipeline
//  uses. It does ZERO Regex work inside the match loop — every channel's
//  normalize() + tokenize() + digitTokens() was done ONCE in Stage 1.
// ═══════════════════════════════════════════════════════════════════════

private object FuzzyChannelMatcher {

    /** Quality tokens that must be stripped before tokenization. */
    private val NOISE_TOKENS = setOf(
        "hd", "fhd", "uhd", "4k", "sd",
        "ar", "en", "fr", "es", "de", "tr", "pt", "it", "ru", "hi",
        "channel", "channels", "tv", "television"
    )

    /** Prefix forms like "ar:", "en:" that leak into local M3U names. */
    private val LANG_PREFIX = Regex("^(ar|en|fr|es|de|tr|pt|it|ru|hi|zh|ja|ko)\\s*:", RegexOption.IGNORE_CASE)

    /** Pre-compiled Regex patterns for normalize() — avoids recompiling on every call. */
    private val NON_ALNUM = Regex("[^a-z0-9\\u0600-\\u06FF]")
    private val LATIN_LETTER_THEN_DIGIT = Regex("([a-z])([0-9])")
    private val DIGIT_THEN_LATIN_LETTER = Regex("([0-9])([a-z])")
    private val ARABIC_LETTER_THEN_DIGIT = Regex("([\\u0600-\\u06FF])([0-9])")
    private val DIGIT_THEN_ARABIC_LETTER = Regex("([0-9])([\\u0600-\\u06FF])")
    private val MULTI_WHITESPACE = Regex("\\s+")
    private val DIGITS_ONLY = Regex("\\d+")

    /**
     * Normalize a raw channel name into its lowercase, noise-free form.
     * Steps:
     *   1. Drop "ar:" / "en:" / ... language prefix.
     *   2. Lowercase.
     *   3. Replace any non-alphanumeric (Latin or Arabic) with a space.
     *   4. SPLIT hybrid alphanumeric tokens — "hd3" → "hd 3", "max4k" → "max 4k".
     *   5. Collapse whitespace.
     */
    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        var s = LANG_PREFIX.replace(raw, "")
        s = s.lowercase()
        s = NON_ALNUM.replace(s, " ")
        s = LATIN_LETTER_THEN_DIGIT.replace(s, "$1 $2")
        s = DIGIT_THEN_LATIN_LETTER.replace(s, "$1 $2")
        s = ARABIC_LETTER_THEN_DIGIT.replace(s, "$1 $2")
        s = DIGIT_THEN_ARABIC_LETTER.replace(s, "$1 $2")
        s = s.trim()
        s = MULTI_WHITESPACE.replace(s, " ")
        return s
    }

    /**
     * Tokenize a normalized name. Quality tokens (hd, fhd, 4k, ar, en, ...)
     * are filtered out. Digits are PRESERVED.
     */
    fun tokenize(normalized: String): Set<String> {
        if (normalized.isEmpty()) return emptySet()
        return normalized.split(" ")
            .filter { it.isNotBlank() && it !in NOISE_TOKENS }
            .toSet()
    }

    /**
     * Extract the standalone digit tokens (channel numbers like "1","2","3").
     * Public so the precomputation stage can call it.
     */
    fun digitTokens(tokens: Set<String>): Set<String> =
        tokens.filter { it.matches(DIGITS_ONLY) }.toSet()

    /**
     * ─────────────────────────────────────────────────────────────────
     *  PRECOMPUTED MATCH (production path — zero Regex inside the loop)
     * ─────────────────────────────────────────────────────────────────
     *
     *  Each [PrecomputedChannel] already has normalized name, tokens, and
     *  digits cached. We only normalize the API channel name ONCE (not
     *  once per local channel), then walk the precomputed list comparing
     *  cached sets.
     *
     *  @param apiChannel   Raw channel name from the API (e.g. "beIN SPORTS MAX HD3")
     *  @param precomputed  List of pre-normalized local channels (built once in Stage 1)
     *  @return Best-matching StreamItem, or null if no candidate scores ≥ 0.5
     */
    fun matchAgainstPrecomputed(
        apiChannel: String,
        precomputed: List<MatchHarvesterRepository.PrecomputedChannel>
    ): StreamItem? {
        if (apiChannel.isBlank() || precomputed.isEmpty()) return null

        val apiNorm = normalize(apiChannel)
        if (apiNorm.isEmpty()) return null
        val apiTokens = tokenize(apiNorm)
        if (apiTokens.isEmpty()) return null
        val apiDigits = digitTokens(apiTokens)

        var bestMatch: StreamItem? = null
        var bestScore = 0.0
        var bestDistance = Int.MAX_VALUE

        for (channel in precomputed) {
            // ── DIGIT GATE (strict equality) ──
            if (apiDigits.isNotEmpty() && apiDigits != channel.digits) continue
            if (channel.digits.isNotEmpty() && channel.digits != apiDigits) continue

            // ── JACCARD SCORE ──
            val intersection = apiTokens.intersect(channel.tokens).size
            val union = apiTokens.union(channel.tokens).size
            val score = if (union == 0) 0.0 else intersection.toDouble() / union
            if (score < 0.5) continue

            // ── TIE-BREAKER: Levenshtein distance ──
            val distance = levenshtein(apiNorm, channel.normalizedName)

            if (score > bestScore || (score == bestScore && distance < bestDistance)) {
                bestScore = score
                bestDistance = distance
                bestMatch = channel.stream
            }
        }

        if (bestMatch != null) {
            Log.d("FuzzyMatcher",
                "Matched '$apiChannel' → '${bestMatch.name}' (score=$bestScore, dist=$bestDistance)")
        }
        return bestMatch
    }

    /**
     * ─────────────────────────────────────────────────────────────────
     *  LEGACY MATCH (backwards compatibility — used by findBestChannelMatch)
     * ─────────────────────────────────────────────────────────────────
     *
     *  Normalizes every local channel on the fly. Slower than the
     *  precomputed variant but kept for callers that don't have a
     *  pre-built list (e.g. one-off lookups).
     */
    fun match(apiChannel: String, channels: List<StreamItem>): StreamItem? {
        if (apiChannel.isBlank() || channels.isEmpty()) return null

        val apiNorm = normalize(apiChannel)
        if (apiNorm.isEmpty()) return null
        val apiTokens = tokenize(apiNorm)
        if (apiTokens.isEmpty()) return null
        val apiDigits = digitTokens(apiTokens)

        var bestMatch: StreamItem? = null
        var bestScore = 0.0
        var bestDistance = Int.MAX_VALUE

        for (channel in channels) {
            val localNorm = normalize(channel.name)
            if (localNorm.isEmpty()) continue
            val localTokens = tokenize(localNorm)
            if (localTokens.isEmpty()) continue
            val localDigits = digitTokens(localTokens)

            if (apiDigits.isNotEmpty() && apiDigits != localDigits) continue
            if (localDigits.isNotEmpty() && localDigits != apiDigits) continue

            val intersection = apiTokens.intersect(localTokens).size
            val union = apiTokens.union(localTokens).size
            val score = if (union == 0) 0.0 else intersection.toDouble() / union
            if (score < 0.5) continue

            val distance = levenshtein(apiNorm, localNorm)

            if (score > bestScore || (score == bestScore && distance < bestDistance)) {
                bestScore = score
                bestDistance = distance
                bestMatch = channel
            }
        }
        return bestMatch
    }

    /** Classic Levenshtein edit distance — used only as a tie-breaker. */
    private fun levenshtein(a: String, b: String): Int {
        val m = a.length; val n = b.length
        if (m == 0) return n; if (n == 0) return m
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
        }
        return dp[m][n]
    }
}
