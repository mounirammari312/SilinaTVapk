package com.superz.iptvplayer.data.subtitles

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * SubtitleRepository — the subtitle download engine, originally copied from
 * the reference app (silinatv-pro-v111, data/repository/SubtitleRepository.kt).
 *
 * v1.17.0 — rebuilt on the SAME engine skeleton (the three public service
 * endpoints, the Cinemeta → OpenSubtitles → Stremio resolution order, the
 * gzip → srt extraction) to fix three user-reported defects:
 *
 *   DEFECT 1 "الترجمة تظهر كنقاط استفهام" — most Arabic .srt files on
 *   OpenSubtitles are Windows-1256 encoded (the search response literally
 *   carries "SubEncoding":"CP1256"; media3's SubripParser detects charset
 *   from the BOM ONLY and otherwise assumes UTF-8 — verified against the
 *   1.5.1 AAR bytecode). The old code wrote the raw CP1256 bytes into the
 *   cache file, so every Arabic glyph decoded to U+FFFD → "؟؟؟؟".
 *   FIX: every downloaded subtitle is transcoded to strict UTF-8 before it
 *   is written (BOM detection → strict UTF-8 probe → declared SubEncoding
 *   → language default charset → generic ladder).
 *
 *   DEFECT 2 "معضم الأفلام يقول لا تتوفر ترجمة" — dl.opensubtitles.org
 *   rate-limits to a CAPTCHA after ~2 rapid downloads (verified live: 403 →
 *   captcha redirect), and the old code wrote the returned HTML page as
 *   current_sub.srt (the gzip ZipException fallback!) → subtitles that
 *   "activate" but render nothing, and every later movie failing. FIX:
 *   candidate rotation (multiple OpenSubtitles results + the Stremio addon
 *   URLs, which live on a different host with no captcha), a proper
 *   User-Agent on downloads, and CONTENT VALIDATION (a valid .srt/.vtt
 *   must contain "-->" / WEBVTT) so an HTML/captcha page can never be
 *   injected as a subtitle.
 *
 *   DEFECT 3 (same report) — series episodes could never resolve: the
 *   engine only searches Cinemeta's MOVIE catalog. FIX: an episode marker
 *   (S01E01 / 1x01) routes the lookup to the SERIES catalog and filters
 *   the OpenSubtitles results by SeriesSeason/SeriesEpisode.
 *
 *   DEFECT 4 (v1.17.0 user report) — the subtitle belonged to a DIFFERENT
 *   movie: the engine took the FIRST Cinemeta search result blindly (the
 *   reference's own weakness, copied faithfully in v1.15). An ambiguous
 *   title (a remake, a localized name, a common word) resolved to the
 *   WRONG IMDB id → subtitles of another movie. v1.18.0 fix, three layers:
 *     1. PANEL ID — when the VOD info carries the movie's imdb_id, it is
 *        passed straight through (exact match, no search at all);
 *     2. YEAR-AWARE SELECTION — every Cinemeta result is scored (name
 *        similarity + year ±1) and the BEST wins instead of the first;
 *     3. TRANSPARENCY — the resolved title+year ("Dune (2021)") is
 *        returned to the UI so the activation toast tells the user exactly
 *        which movie the subtitles belong to.
 *   Plus: the Stremio fallback now uses the /series/ addon path for
 *   episode lookups (the /movie/ path 404s for series ids).
 *
 * Iron-rule compliance: SmartPlayer is untouched — the caller (ViewModel)
 * side-loads the resulting local .srt into the PUBLIC active ExoPlayer.
 */
object SubtitleRepository {

    // ══════════════════════════════════════════════════════════════════════
    //  Public service endpoints (reference: AppConfig) — same services
    // ══════════════════════════════════════════════════════════════════════

    /** Cinemeta (Stremio v3 catalog) search base — resolves names to IMDB ids. */
    const val SUBTITLE_CINEMETA_BASE_URL: String =
        "https://v3-cinemeta.strem.io/catalog/movie/top/search="

    /** OpenSubtitles legacy REST search base (primary engine). */
    const val OPENSUBTITLES_REST_BASE_URL: String =
        "https://rest.opensubtitles.org/search/"

    /** Stremio OpenSubtitles v3 add-on base (fallback engine). */
    const val STREMIO_OPENSUBTITRES_BASE_URL: String =
        "https://opensubtitles-v3.strem.io/subtitles/movie/"

    /** Cinemeta SERIES catalog base (v1.17.0 — episode lookups). */
    const val SUBTITLE_CINEMETA_SERIES_BASE_URL: String =
        "https://v3-cinemeta.strem.io/catalog/series/top/search="

    /** Cinemeta meta endpoint base (v1.18.0 — name/year of a known imdb id). */
    const val SUBTITLE_CINEMETA_META_BASE_URL: String =
        "https://v3-cinemeta.strem.io/meta/"

    /** Stremio OpenSubtitles v3 add-on base — SERIES path (v1.18.0). */
    const val STREMIO_OPENSUBTITRES_SERIES_BASE_URL: String =
        "https://opensubtitles-v3.strem.io/subtitles/series/"

    // ══════════════════════════════════════════════════════════════════════
    //  Quality tags to strip for fuzzy name matching
    //  (v1.17.0: longest-first + word-boundary replace — the v1.15 order
    //  let the "HD" tag eat the "HDR" token from the inside and left
    //  ". ." artifacts on dotted release names)
    // ══════════════════════════════════════════════════════════════════════

    private val QUALITY_TAGS = listOf(
        "DOLBY VISION", "FULL HD", "TS RIP", "WEB DL", "WEB-DL", "CAMRIP",
        "WEBRIP", "BLURAY", "BDRIP", "DVDRIP", "PDTV", "SATRIP", "HDTV",
        "60FPS", "50FPS", "30FPS", "1080P", "720P", "576P", "480P", "240P",
        "HEVC", "H265", "H264", "AVC", "AAC", "AC3", "HDR", "UHD", "4K",
        "FHD", "SD", "HQ", "HD", "DV"
    ).sortedByDescending { it.length }

    private val YEAR_PATTERN = Regex("""\b(19|20)\d{2}\b""")
    private val BRACKET_CONTENT = Regex("""[\[\(][^\]\)]*[\]\)]""")
    private val TAG_PATTERN_CACHE = QUALITY_TAGS.associateWith {
        Regex("(?i)\\b${Regex.escape(it)}\\b")
    }

    private val EPISODE_SE_PATTERN = Regex("""(?i)\bs(\d{1,2})\s*e\s*(\d{1,3})\b""")
    private val EPISODE_X_PATTERN = Regex("""(?i)\b(\d{1,2})\s*[x×]\s*(\d{1,3})\b""")

    /** An episode marker extracted from a stream name (S01E05 / 1x05). */
    data class EpisodeMarker(val seriesName: String, val season: Int, val episode: Int)

    /**
     * v1.18.0 — the full resolution outcome: the download candidates, the
     * error message (null on success) and the TRANSPARENT title ("Dune
     * (2021)") the engine actually resolved the name to — shown in the
     * activation toast so a wrong match is visible at a glance.
     */
    data class SubtitleLookup(
        val candidates: List<SubtitleCandidate>?,
        val error: String?,
        val resolvedTitle: String?
    )

    /**
     * Shared HTTP client for all subtitle network operations.
     * Built once per process — connection pool is reused across requests.
     */
    private val subtitleClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * Strips quality tags, year markers, bracket content and release-name
     * separators (dots/underscores) from a stream name to produce a clean
     * movie title suitable for subtitle search.
     */
    fun extractCleanMovieName(streamName: String): String {
        var clean = streamName.trim()
        clean = BRACKET_CONTENT.replace(clean, " ")
        // v1.17.0 — release names use "." and "_" as word separators
        // ("The.Nun.II.2023.1080p.WEB-DL" → "The Nun II 1080p WEB-DL").
        clean = clean.replace('.', ' ').replace('_', ' ')
        clean = YEAR_PATTERN.replace(clean, " ")
        for ((tag, pattern) in TAG_PATTERN_CACHE) {
            clean = pattern.replace(clean, " ")
        }
        return clean.replace(Regex("\\s+"), " ").trim().trim('-', ' ')
    }

    /**
     * Strips quality tags from a stream name for fallback fuzzy matching.
     * The difference vs [extractCleanMovieName] is that this variant keeps
     * punctuation that might be meaningful for episode disambiguation,
     * lowercased.
     */
    fun stripQualityTags(name: String): String {
        var clean = name.trim()
        clean = BRACKET_CONTENT.replace(clean, " ")
        clean = clean.replace('.', ' ').replace('_', ' ')
        clean = YEAR_PATTERN.replace(clean, " ")
        for ((tag, pattern) in TAG_PATTERN_CACHE) {
            clean = pattern.replace(clean, " ")
        }
        return clean.replace(Regex("\\s+"), " ").trim().lowercase()
    }

    /**
     * v1.18.0 — pulls a 4-digit year out of any text (a releasedate like
     * "2021-10-22", a bare "2021", or a stream name like "Dune 2021").
     */
    fun extractYear(text: String?): Int? = text
        ?.let { YEAR_PATTERN.find(it) }
        ?.groupValues?.get(0)?.toIntOrNull()

    /**
     * v1.18.0 — normalizes a title for comparison: lowercase, stripped of
     * everything that is not a letter/digit/space (works for Arabic too).
     */
    private fun normalizeTitle(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()

    /**
     * v1.18.0 LAYER 2 — scores one Cinemeta search result against the query
     * (name similarity + year proximity). Pure, unit-tested.
     *
     * name similarity: exact 100 / startsWith 70 / contains 50 / else 0
     * year: |metaYear - year| ≤ 1 → +30; a real mismatch → −40 (a remake
     * with the same title loses to the right year); unknown → 0.
     */
    fun scoreMeta(metaName: String?, metaYear: Int?, query: String, year: Int?): Int {
        val q = normalizeTitle(query)
        val m = metaName?.let { normalizeTitle(it) } ?: ""
        var score = when {
            m.isEmpty() || q.isEmpty() -> 0
            m == q -> 100
            m.startsWith(q) || q.startsWith(m) -> 70
            m.contains(q) || q.contains(m) -> 50
            else -> 0
        }
        if (year != null && metaYear != null) {
            score += if (kotlin.math.abs(metaYear - year) <= 1) 30 else -40
        }
        return score
    }

    /**
     * Detects an episode marker (S01E05 / 1x05) in a stream name and
     * returns the cleaned series name plus season/episode numbers.
     * Returns null for plain movie names.
     */
    fun parseEpisodeMarker(streamName: String): EpisodeMarker? {
        val m = EPISODE_SE_PATTERN.find(streamName)
            ?: EPISODE_X_PATTERN.find(streamName)
            ?: return null
        val season = m.groupValues[1].toIntOrNull() ?: return null
        val episode = m.groupValues[2].toIntOrNull() ?: return null
        if (season !in 1..99 || episode !in 1..999) return null
        val series = extractCleanMovieName(streamName.substring(0, m.range.first))
        if (series.isBlank()) return null
        return EpisodeMarker(series, season, episode)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /** One downloadable subtitle with the encoding its service declared. */
    data class SubtitleCandidate(
        val url: String,
        val encoding: String?,
        val source: String
    )

    /**
     * Resolves the list of downloadable subtitle candidates for the given
     * movie/episode name and target language code — the reference's
     * resolution pipeline (Cinemeta IMDB lookup → OpenSubtitles REST →
     * Stremio addon), now returning EVERY usable link instead of only the
     * first, so the download stage can rotate past rate-limits.
     *
     * v1.18.0 — the wrong-movie fix (DEFECT 4, three layers): an optional
     * panel-provided [imdbId] skips the name search entirely (layer 1);
     * otherwise every Cinemeta result is scored against the query and the
     * optional [year] (layer 2) instead of taking the first blindly; the
     * resolved title+year rides back for the activation toast (layer 3).
     *
     * @param imdbId the panel's own imdb_id for this VOD item, when known.
     * @param year the movie/series year (releasedate or the stream name).
     * @return [SubtitleLookup]. On success candidates is non-empty and
     *         error is null; resolvedTitle is the name+year actually used.
     */
    suspend fun fetchSubtitleCandidates(
        movieName: String,
        languageCode: String,
        imdbId: String? = null,
        year: Int? = null
    ): SubtitleLookup = withContext(Dispatchers.IO) {
        try {
            if (movieName.isBlank()) {
                return@withContext SubtitleLookup(null, "Error: Movie name is empty", null)
            }

            val targetLangCode = languageCode.lowercase().trim()
            val osLang = mapLanguageCode(targetLangCode)
            val episode = parseEpisodeMarker(movieName)
            val candidates = mutableListOf<SubtitleCandidate>()

            // LAYER 2 fallback — when the caller had no year, the raw
            // stream name often still carries one ("Dune 2021").
            val effectiveYear = year ?: extractYear(movieName)

            var resolvedId: String? = imdbId?.takeIf { it.isNotBlank() }
            var resolvedTitle: String? = null

            if (resolvedId != null) {
                // ── LAYER 1: the panel's imdb id — exact, no search. The
                //    display name for the toast is resolved best-effort via
                //    Cinemeta's meta endpoint (silent on failure).
                resolvedTitle = fetchCinemetaMetaTitle(resolvedId!!, episode != null)
            } else {
                // ── Search: Cinemeta (series catalog for episodes, movie
                //    catalog otherwise) — v1.18.0 LAYER 2: score EVERY
                //    result (name + year) and pick the best, not the first.
                try {
                    val query = episode?.seriesName ?: extractCleanMovieName(movieName)
                    val base = if (episode != null) SUBTITLE_CINEMETA_SERIES_BASE_URL
                    else SUBTITLE_CINEMETA_BASE_URL
                    val encodedName = java.net.URLEncoder.encode(query.trim(), "UTF-8")
                    val metaRes = subtitleClient.newCall(
                        okhttp3.Request.Builder()
                            .url("$base$encodedName.json")
                            .header("User-Agent", "Mozilla/5.0")
                            .build()
                    ).execute()
                    val metaBody = metaRes.body?.string() ?: ""
                    if (metaRes.isSuccessful && !metaBody.trim().startsWith("<")) {
                        val metas = org.json.JSONObject(metaBody).optJSONArray("metas")
                        if (metas != null && metas.length() > 0) {
                            val best = pickBestMeta(metas, query, effectiveYear)
                            resolvedId = best?.first?.optString("id", "")?.ifEmpty { null }
                            if (best != null) resolvedTitle = best.second
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (resolvedId == null) {
                return@withContext SubtitleLookup(
                    null,
                    "Error: Movie '${episode?.seriesName ?: movieName}' not found in IMDB",
                    null
                )
            }
            val finalId = resolvedId!!

            // 2. Primary Engine: Direct OpenSubtitles REST API (every result
            //    with a SubDownloadLink becomes a candidate — rotation past
            //    the download-host rate limit; episode lookups filter by
            //    SeriesSeason/SeriesEpisode).
            try {
                val cleanImdb = finalId.replace("tt", "")
                val restUrl = "$OPENSUBTITLES_REST_BASE_URL" +
                    "imdbid-$cleanImdb/sublanguageid-$osLang"
                val restRes = subtitleClient.newCall(
                    okhttp3.Request.Builder()
                        .url(restUrl)
                        .header("User-Agent", "TemporaryUserAgent")
                        .build()
                ).execute()
                val restBody = restRes.body?.string() ?: ""
                if (restRes.isSuccessful && !restBody.trim().startsWith("<") && restBody.trim().startsWith("[")) {
                    val jsonArray = org.json.JSONArray(restBody)
                    for (i in 0 until jsonArray.length()) {
                        val sub = jsonArray.getJSONObject(i)
                        if (episode != null) {
                            val s = sub.optInt("SeriesSeason", -1)
                            val e = sub.optInt("SeriesEpisode", -1)
                            if (s != episode.season || e != episode.episode) continue
                        }
                        val subUrl = sub.optString("SubDownloadLink", "")
                        if (subUrl.isNotEmpty()) {
                            candidates.add(
                                SubtitleCandidate(
                                    url = subUrl,
                                    encoding = sub.optString("SubEncoding", "").ifEmpty { null },
                                    source = "opensubtitles"
                                )
                            )
                        }
                        if (candidates.size >= 4) break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 3. Fallback Engine: Stremio V3 Addon (different download host —
            //    no captcha; URLs re-encode to UTF-8 server-side). v1.18.0:
            //    episodes use the addon's SERIES path — /movie/ 404s for
            //    series imdb ids, so the fallback never fired for them.
            try {
                val stremioBase = if (episode != null) STREMIO_OPENSUBTITRES_SERIES_BASE_URL
                else STREMIO_OPENSUBTITRES_BASE_URL
                val stremioUrl = "$stremioBase$finalId.json"
                val stRes = subtitleClient.newCall(
                    okhttp3.Request.Builder()
                        .url(stremioUrl)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                ).execute()
                val stBody = stRes.body?.string() ?: ""
                if (stRes.isSuccessful && !stBody.trim().startsWith("<")) {
                    val subs = org.json.JSONObject(stBody).optJSONArray("subtitles")
                    if (subs != null) {
                        for (i in 0 until subs.length()) {
                            val sub = subs.getJSONObject(i)
                            val lang = sub.optString("lang", "").lowercase()
                            if (lang == targetLangCode || lang == osLang || lang.startsWith(targetLangCode)) {
                                val url = sub.optString("url", "")
                                if (url.isNotEmpty()) {
                                    candidates.add(
                                        SubtitleCandidate(
                                            url = url,
                                            encoding = null, // the addon re-encodes to UTF-8
                                            source = "stremio"
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (candidates.isEmpty()) {
                return@withContext SubtitleLookup(
                    null, "Error: Subtitle not found for language [$osLang]", resolvedTitle
                )
            }
            return@withContext SubtitleLookup(candidates, null, resolvedTitle)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext SubtitleLookup(null, "Network Exception: ${e.message}", null)
        }
    }

    /**
     * v1.18.0 LAYER 2 — picks the best Cinemeta search result: every meta
     * is scored via [scoreMeta] (name similarity + year ±1) and the highest
     * score wins; when NOTHING scores above zero the FIRST result keeps the
     * old behavior (better than failing). Returns (metaJson, "Name (Year)").
     */
    fun pickBestMeta(
        metas: org.json.JSONArray,
        query: String,
        year: Int?
    ): Pair<org.json.JSONObject, String?>? {
        if (metas.length() == 0) return null
        var bestIdx = 0
        var bestScore = Int.MIN_VALUE
        for (i in 0 until metas.length()) {
            val meta = metas.optJSONObject(i) ?: continue
            val name = meta.optString("name", "")
            val metaYear = metaYearOf(meta)
            val score = scoreMeta(name, metaYear, query, year)
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }
        val chosen = metas.optJSONObject(bestIdx) ?: return null
        val title = chosen.optString("name", "").ifBlank { null }
        val y = metaYearOf(chosen)
        val display = when {
            title != null && y != null -> "$title ($y)"
            title != null -> title
            else -> null
        }
        return chosen to display
    }

    /** A Cinemeta meta's year: "year" first, then "releaseInfo" ("2021" / "2021–2024"). */
    private fun metaYearOf(meta: org.json.JSONObject): Int? {
        meta.optString("year", "").trim().takeIf { it.isNotEmpty() }?.let { raw ->
            extractYear(raw)?.let { return it }
        }
        meta.optString("releaseInfo", "").trim().takeIf { it.isNotEmpty() }?.let { raw ->
            extractYear(raw)?.let { return it }
        }
        return null
    }

    /**
     * v1.18.0 LAYER 1/3 — resolves the display title of a KNOWN imdb id via
     * Cinemeta's meta endpoint (/meta/movie/tt123.json or /meta/series/…).
     * Best-effort: any failure returns null (the toast then simply shows no
     * movie name — the id itself is still exact).
     */
    private fun fetchCinemetaMetaTitle(imdbId: String, isSeries: Boolean): String? {
        return try {
            val kind = if (isSeries) "series" else "movie"
            val url = "$SUBTITLE_CINEMETA_META_BASE_URL$kind/$imdbId.json"
            val res = subtitleClient.newCall(
                okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
            ).execute()
            val body = res.body?.string() ?: ""
            if (res.isSuccessful && !body.trim().startsWith("<")) {
                val meta = org.json.JSONObject(body).optJSONObject("meta") ?: return null
                val name = meta.optString("name", "").ifBlank { null } ?: return null
                val year = metaYearOf(meta)
                if (year != null) "$name ($year)" else name
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Downloads the best available subtitle from the candidate list:
     * tries each in order (small breathing delay between attempts to
     * soften the download host's rate limit), gunzips when needed,
     * TRANSCODES to strict UTF-8, validates the content is really a
     * subtitle (never an HTML/captcha page) and writes the local cache
     * file readable by ExoPlayer.
     */
    suspend fun downloadBestSubtitle(
        candidates: List<SubtitleCandidate>,
        languageCode: String,
        context: Context
    ): Uri? = withContext(Dispatchers.IO) {
        val localFile = File(context.cacheDir, "current_sub.srt")
        for ((index, candidate) in candidates.withIndex()) {
            if (index > 0) delay(1500L)
            try {
                val request = okhttp3.Request.Builder()
                    .url(candidate.url)
                    .header("User-Agent", "TemporaryUserAgent")
                    .build()
                subtitleClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bodyBytes = response.body?.byteStream()?.readBytes()
                        ?: return@use
                    // gzip magic → decompress (OpenSubtitles SubDownloadLink
                    // serves .gz; the Stremio addon serves plain text).
                    val raw = if (bodyBytes.size >= 2 &&
                        bodyBytes[0] == 0x1f.toByte() && bodyBytes[1] == 0x8b.toByte()
                    ) {
                        GZIPInputStream(ByteArrayInputStream(bodyBytes)).use { it.readBytes() }
                    } else {
                        bodyBytes
                    }
                    if (raw.isEmpty()) return@use
                    val utf8 = transcodeToUtf8(raw, candidate.encoding, languageCode)
                    val text = String(utf8, StandardCharsets.UTF_8)
                    if (!looksLikeSubtitle(text)) return@use // captcha/HTML page → next candidate
                    localFile.writeBytes(utf8)
                    return@withContext Uri.fromFile(localFile)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        null
    }

    /**
     * Heuristic: does this decoded text actually look like a subtitle file?
     * A valid .srt contains "-->" timing arrows; a .vtt starts with
     * WEBVTT. An HTML error page or captcha redirect never does.
     */
    fun looksLikeSubtitle(text: String): Boolean {
        val t = text.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        return t.contains("-->") || t.startsWith("WEBVTT", ignoreCase = true)
    }

    /**
     * Transcodes arbitrary subtitle bytes to strict UTF-8.
     *
     * Detection ladder (each step only applies if the previous fails):
     *   1. UTF-8 / UTF-16 BOM (converted / stripped).
     *   2. Strict UTF-8 validation (valid UTF-8 passes through untouched).
     *   3. The encoding the service DECLARED for this file (SubEncoding).
     *   4. The charset that matches the requested language
     *      (ar → windows-1256, ru → windows-1251, …).
     *   5. Generic ladder: windows-1256 → windows-1252 → ISO-8859-1.
     */
    fun transcodeToUtf8(
        raw: ByteArray,
        declaredEncoding: String? = null,
        languageCode: String? = null
    ): ByteArray {
        // 1. BOM detection
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
            return raw.copyOfRange(3, raw.size) // UTF-8 BOM → strip
        }
        if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) {
            return String(raw, 2, raw.size - 2, StandardCharsets.UTF_16LE)
                .toByteArray(StandardCharsets.UTF_8)
        }
        if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) {
            return String(raw, 2, raw.size - 2, StandardCharsets.UTF_16BE)
                .toByteArray(StandardCharsets.UTF_8)
        }
        // 2. Strict UTF-8 probe
        if (decodesStrictly(raw, StandardCharsets.UTF_8)) return raw
        // 3-5. Charset ladder
        for (name in encodingCandidates(declaredEncoding, languageCode)) {
            val cs = try { Charset.forName(name) } catch (e: Exception) { continue }
            if (cs == StandardCharsets.UTF_8) continue
            if (decodesStrictly(raw, cs)) {
                return String(raw, cs).toByteArray(StandardCharsets.UTF_8)
            }
        }
        // Last resort — Latin-1 decodes any byte sequence.
        return String(raw, StandardCharsets.ISO_8859_1).toByteArray(StandardCharsets.UTF_8)
    }

    /** The ordered charset ladder for step 3-5 of [transcodeToUtf8]. */
    fun encodingCandidates(declaredEncoding: String?, languageCode: String?): List<String> {
        val ladder = mutableListOf<String>()
        declaredEncoding?.trim()?.takeIf { it.isNotEmpty() && !it.equals("UTF-8", true) }?.let {
            ladder.add(it) // e.g. "CP1256" — Charset.forName accepts this form
        }
        val langDefault = when (languageCode?.lowercase()?.trim()) {
            "ar", "ara" -> "windows-1256"
            "ru", "rus" -> "windows-1251"
            "tr", "tur" -> "windows-1254"
            "el", "gre" -> "windows-1253"
            "he", "heb" -> "windows-1255"
            else -> null
        }
        langDefault?.let { ladder.add(it) }
        ladder.add("windows-1256")
        ladder.add("windows-1252")
        return ladder.distinct()
    }

    /** True when the bytes are a valid sequence in the given charset. */
    private fun decodesStrictly(raw: ByteArray, charset: Charset): Boolean = try {
        val decoder: CharsetDecoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(raw))
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Maps ISO 639-1 / English-language names to OpenSubtitles 3-letter codes.
     * Falls back to the input lowercased if no mapping is known.
     */
    fun mapLanguageCode(targetLangCode: String): String = when (targetLangCode) {
        "ar", "ara", "arabic" -> "ara"
        "en", "eng", "english" -> "eng"
        "fr", "fre", "fra", "french" -> "fre"
        "es", "spa", "spanish" -> "spa"
        "de", "ger", "deu", "german" -> "ger"
        "it", "ita", "italian" -> "ita"
        "tr", "tur", "turkish" -> "tur"
        "pt", "por", "portuguese" -> "por"
        "ru", "rus", "russian" -> "rus"
        "hi", "hin", "hindi" -> "hin"
        "nl", "dut", "nld", "dutch" -> "dut"
        "pl", "pol", "polish" -> "pol"
        "sv", "swe", "swedish" -> "swe"
        "da", "dan", "danish" -> "dan"
        "no", "nor", "norwegian" -> "nor"
        "fi", "fin", "finnish" -> "fin"
        "cs", "cze", "ces", "czech" -> "cze"
        "ro", "rum", "ron", "romanian" -> "rum"
        "el", "gre", "ell", "greek" -> "gre"
        "he", "heb", "hebrew" -> "heb"
        "id", "ind", "indonesian" -> "ind"
        "ja", "jpn", "japanese" -> "jpn"
        "ko", "kor", "korean" -> "kor"
        "zh", "chi", "zho", "chinese" -> "chi"
        else -> targetLangCode
    }
}
