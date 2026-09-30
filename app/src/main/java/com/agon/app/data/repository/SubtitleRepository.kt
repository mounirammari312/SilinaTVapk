package com.agon.app.data.repository

import android.content.Context
import android.net.Uri
import com.agon.app.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * SubtitleRepository
 *
 * Extracted from PlayerActivity as part of the MVVM refactoring.
 *
 * Responsibilities:
 *   1. Resolve a subtitle URL for a given movie/episode name and language code.
 *      Resolution pipeline:
 *        a. IMDB ID lookup via Cinemeta (Stremio v3 catalog).
 *        b. Primary engine: OpenSubtitles REST API.
 *        c. Fallback engine: Stremio OpenSubtitles v3 add-on.
 *   2. Download the subtitle (potentially .gz compressed) and decompress it
 *      to a local cache file readable by ExoPlayer.
 *
 * The repository exposes a single OkHttpClient instance shared across all
 * subtitle network calls, ensuring connection pooling and consistent timeouts.
 */
object SubtitleRepository {

    // ══════════════════════════════════════════════════════════════════════
    //  Quality tags to strip for fuzzy name matching
    // ══════════════════════════════════════════════════════════════════════

    private val QUALITY_TAGS = listOf(
        "4K", "UHD", "FHD", "FULL HD", "HD", "SD", "HQ", "HDH", "H265",
        "HEVC", "H264", "AVC", "1080P", "720P", "576P", "480P", "240P",
        "60FPS", "50FPS", "30FPS", "HDR", "DV", "DOLBY VISION", "AC3",
        "AAC", "HDTV", "WEBRIP", "WEB-DL", "BLURAY", "BDRIP", "DVDRIP",
        "CAMRIP", "TS RIP", "PDTV", "SATRIP"
    )

    private val YEAR_PATTERN = Regex("""\b(19|20)\d{2}\b""")
    private val BRACKET_CONTENT = Regex("""[\[\(][^\]\)]*[\]\)]""")

    /**
     * Shared HTTP client for all subtitle network operations.
     * Built once per process — connection pool is reused across requests.
     */
    private val subtitleClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * Strips quality tags, year markers, and bracket content from a stream name
     * to produce a clean movie title suitable for subtitle search.
     */
    fun extractCleanMovieName(streamName: String): String {
        var clean = streamName.trim()
        clean = BRACKET_CONTENT.replace(clean, " ")
        clean = YEAR_PATTERN.replace(clean, " ")
        for (tag in QUALITY_TAGS) {
            clean = clean.replace(tag, " ", ignoreCase = true)
        }
        return clean.replace(Regex("\\s+"), " ").trim().trim('.', '-', '_')
    }

    /**
     * Strips quality tags from a stream name for fallback fuzzy matching.
     * The difference vs [extractCleanMovieName] is that this variant keeps
     * punctuation that might be meaningful for episode disambiguation.
     */
    fun stripQualityTags(name: String): String {
        var clean = name.trim()
        clean = BRACKET_CONTENT.replace(clean, " ")
        clean = YEAR_PATTERN.replace(clean, " ")
        for (tag in QUALITY_TAGS) {
            clean = clean.replace(tag, " ", ignoreCase = true)
        }
        return clean.replace(Regex("\\s+"), " ").trim().lowercase()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Resolves a remote subtitle URL for the given movie/episode name and
     * target language code.
     *
     * @param movieName  Clean movie name (use [extractCleanMovieName] first).
     * @param languageCode  ISO 639-1 language code (e.g. "ar", "en", "fr").
     * @return Pair<subtitleUrl, errorMessage>. On success, errorMessage is null.
     */
    suspend fun fetchSubtitleUri(
        movieName: String,
        languageCode: String
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            if (movieName.isBlank()) return@withContext Pair(null, "Error: Movie name is empty")

            // 1. Fetch IMDB ID from Cinemeta
            // V8.2: URL base comes from AppConfig.SUBTITLE_CINEMETA_BASE_URL.
            val encodedName = java.net.URLEncoder.encode(movieName.trim(), "UTF-8")
            val cinemetaUrl = "${AppConfig.SUBTITLE_CINEMETA_BASE_URL}$encodedName.json"

            val metaReq = okhttp3.Request.Builder()
                .url(cinemetaUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val metaRes = subtitleClient.newCall(metaReq).execute()
            val metaBody = metaRes.body?.string()
                ?: return@withContext Pair(null, "Error: IMDB database did not respond")

            val metaJson = org.json.JSONObject(metaBody)
            val metas = metaJson.optJSONArray("metas")
            if (metas == null || metas.length() == 0) {
                return@withContext Pair(null, "Error: Movie '$movieName' not found in IMDB")
            }

            val imdbId = metas.getJSONObject(0).optString("id", "")
            if (imdbId.isEmpty()) return@withContext Pair(null, "Error: IMDB ID is missing")

            // 2. Global Language Mapper
            val targetLangCode = languageCode.lowercase().trim()
            val osLang = mapLanguageCode(targetLangCode)
            val cleanImdb = imdbId.replace("tt", "")

            // 3. Primary Engine: Direct OpenSubtitles REST API
            // V8.2: URL base comes from AppConfig.OPENSUBTITLES_REST_BASE_URL.
            val restUrl = "${AppConfig.OPENSUBTITLES_REST_BASE_URL}imdbid-$cleanImdb/sublanguageid-$osLang"
            val restReq = okhttp3.Request.Builder()
                .url(restUrl)
                .header("User-Agent", "TemporaryUserAgent")
                .build()

            val restRes = subtitleClient.newCall(restReq).execute()
            val restBody = restRes.body?.string() ?: ""

            if (restRes.isSuccessful && !restBody.trim().startsWith("<")) {
                val jsonArray = org.json.JSONArray(restBody)
                if (jsonArray.length() > 0) {
                    val subUrl = jsonArray.getJSONObject(0).optString("SubDownloadLink", "")
                    if (subUrl.isNotEmpty()) return@withContext Pair(subUrl, null)
                }
            }

            // 4. Fallback Engine: Stremio V3 Addon
            // V8.2: URL base comes from AppConfig.STREMIO_OPENSUBTITRES_BASE_URL.
            val stremioUrl = "${AppConfig.STREMIO_OPENSUBTITRES_BASE_URL}$imdbId.json"
            val stReq = okhttp3.Request.Builder()
                .url(stremioUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val stRes = subtitleClient.newCall(stReq).execute()
            val stBody = stRes.body?.string()
                ?: return@withContext Pair(null, "Error: Subtitle servers are down")

            if (stRes.isSuccessful && !stBody.trim().startsWith("<")) {
                val stJson = org.json.JSONObject(stBody)
                val subs = stJson.optJSONArray("subtitles")
                if (subs != null) {
                    for (i in 0 until subs.length()) {
                        val sub = subs.getJSONObject(i)
                        val lang = sub.optString("lang", "").lowercase()
                        if (lang == targetLangCode || lang == osLang || lang.startsWith(targetLangCode)) {
                            val url = sub.optString("url", "")
                            if (url.isNotEmpty()) return@withContext Pair(url, null)
                        }
                    }
                }
            }

            return@withContext Pair(null, "Error: Subtitle not found for language [$osLang]")
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Pair(null, "Network Exception: ${e.message}")
        }
    }

    /**
     * Downloads a subtitle file (possibly .gz compressed) and extracts it to
     * a local cache file readable by ExoPlayer.
     *
     * Flow:
     *   a. Open an InputStream from the URL via OkHttp.
     *   b. Wrap it in a [GZIPInputStream] to decompress.
     *   c. Write the uncompressed bytes to [File] in [Context.getCacheDir].
     *   d. Return [Uri.fromFile] pointing to the local file.
     *
     * If the stream is NOT gzip (e.g. plain .srt), GZIPInputStream throws
     * ZipException and we fall back to reading the raw bytes directly.
     */
    suspend fun downloadAndExtractSubtitle(
        url: String,
        context: Context
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val request = okhttp3.Request.Builder().url(url).build()
            val response = subtitleClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val responseBody = response.body?.byteStream() ?: return@withContext null

            val localFile = File(context.cacheDir, "current_sub.srt")

            // BufferedInputStream improves read performance and gives GZIPInputStream
            // a larger lookahead buffer for format detection.
            val bufferedStream = BufferedInputStream(responseBody)

            try {
                GZIPInputStream(bufferedStream).use { gzipIn ->
                    FileOutputStream(localFile).use { out ->
                        gzipIn.copyTo(out)
                    }
                }
            } catch (_: java.util.zip.ZipException) {
                // Not a gzip file — read raw bytes directly (plain .srt)
                bufferedStream.use { rawIn ->
                    FileOutputStream(localFile).use { out ->
                        rawIn.copyTo(out)
                    }
                }
            }
            Uri.fromFile(localFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Maps ISO 639-1 / English-language names to OpenSubtitles 3-letter codes.
     * Falls back to the input lowercased if no mapping is known.
     */
    private fun mapLanguageCode(targetLangCode: String): String = when (targetLangCode) {
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
