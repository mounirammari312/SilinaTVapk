package com.superz.iptvplayer.data.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RecorderEngine — v1.17.0.
 *
 * WHY THIS REPLACES THE v1.16.0 TEE: the reference's RecordingDataSource tee
 * dumps EVERY byte the player reads into one file. For HLS that interleaves
 * the m3u8 manifest TEXT with segment bytes from ADAPTIVE quality switches
 * (and from separate audio/video playlists) — the resulting file is struct
 * garbage: the user's recording of a movie played as a black screen for a
 * fixed bogus duration. (The reference v111 as shipped does not even wire
 * the tee into its player — Task 16 analysis — so there was nothing more
 * to copy; this engine makes the feature actually work.)
 *
 * WHAT IT DOES — a standalone recorder, fully independent of playback
 * (SmartPlayer.kt untouched — the iron rule):
 *   • HLS VOD (#EXT-X-ENDLIST): downloads ALL segments at full network
 *     speed (not wall-clock speed), then remuxes the TS to a real MP4.
 *   • HLS LIVE: polls the media playlist at the stream's own cadence and
 *     appends only NEW segments (a clean TS, no manifest bytes, no ABR
 *     interleaving), then remuxes on stop.
 *   • Master playlists: picks the variant closest to what the user is
 *     WATCHING (or the best one), plus its audio rendition when the audio
 *     is demuxed, and muxes the two tracks together.
 *   • AES-128 encrypted streams are decrypted (per-spec IV handling).
 *   • EXT-X-BYTERANGE segments are fetched with Range headers.
 *   • fMP4 HLS (EXT-X-MAP / .m4s): the byte concatenation of the init
 *     segment + media segments is already a playable fMP4 — saved as .mp4
 *     directly, no remux needed.
 *   • Progressive URLs: plain streamed byte-exact download.
 *   • Fallbacks: if the TS→MP4 remux fails for any reason, the clean TS is
 *     published as .ts (VLC/most players handle it) instead of losing the
 *     recording.
 *
 * The recording no longer touches the player at all: zapping, engine
 * fallback to VLC, stream cuts and auto-restarts during a recording are
 * all harmless (each rebuilds only the playback source). It finalizes on
 * user stop, on natural completion (VOD fully downloaded), or on exiting
 * the player screen.
 *
 * v1.18.0 — WATCH = DOWNLOAD additions:
 *   • a caller-supplied LABEL (the movie/series name) now prefixes the
 *     output filename, so downloads are recognizable in the phone's
 *     Downloads folder ("Oria_Dune_20261005_…mp4") instead of a bare
 *     timestamp;
 *   • VOD progress (0..100) is tracked and exposed via [progress] — the
 *     player pill shows "⬇ 37%" while a movie downloads (HLS: downloaded
 *     seconds / total playlist seconds; progressive: bytes / Content-
 *     Length). Live recording stays indeterminate (-1).
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
object RecorderEngine {

    private const val TAG = "RecorderEngine"

    // ══════════════════════════════════════════════════════════════════════
    //  UI-facing state (the REC pill polls these, same contract the
    //  reference's PlayerActivity used with its service statics)
    // ══════════════════════════════════════════════════════════════════════

    enum class Phase { DOWNLOADING, FINALIZING }

    @Volatile
    private var job: Job? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    private var phase: Phase = Phase.DOWNLOADING

    @Volatile
    private var recordingStartMs: Long = 0L

    private val recordingBytes = AtomicLong(0L)

    @Volatile
    var lastRecordingPath: String = ""
        private set

    @Volatile
    var currentLabel: String = ""
        private set

    /**
     * v1.19.9 — WHO owns the active download:
     *  • true  → the fullscreen player armed it (its ⬇/auto-save). The
     *    player's onCleared STOPS it when the user leaves the screen —
     *    the v1.17/v1.18 "nothing keeps downloading after the player"
     *    contract;
     *  • false → a VOD INFO PAGE armed it (the new download button next
     *    to Play). It is a deliberate background download: it must
     *    SURVIVE the player opening/closing (watch + leave, or never
     *    watching at all). Only its own stop button or an error ends it.
     */
    @Volatile
    var playerOwned: Boolean = true
        private set

    /**
     * v1.19.9 — the VOD key ("vodm:{streamId}" / "vode:{episodeId}") that
     * armed the active download, null for player-armed ones. The info
     * pages poll it on (re)entry so a page re-opened mid-download resumes
     * the progress display instead of offering a second download of the
     * same title (one download at a time is the engine's iron rule).
     */
    @Volatile
    var sourceKey: String? = null
        private set

    /**
     * v1.18.0 — VOD download progress in percent (0..100), or -1 while the
     * total is unknown (live polling / no Content-Length). Polled by the
     * player pill exactly like the elapsed/size readouts.
     */
    @Volatile
    private var progressPercent: Int = -1

    fun progress(): Int = progressPercent

    @Volatile
    private var stopRequested: Boolean = false

    /** Structured result delivered when a recording finishes. */
    data class RecResult(
        val bytes: Long,
        val path: String,
        val error: Boolean,
        val errorMessage: String? = null
    )

    /** Set by the PlayerViewModel; receives the finalize result (toast). */
    @Volatile
    var onResult: ((RecResult) -> Unit)? = null

    val isFinalizing: Boolean get() = isRecording && phase == Phase.FINALIZING
    fun recordingStartTime(): Long = recordingStartMs
    fun recordingBytesWritten(): Long = recordingBytes.get()

    // ══════════════════════════════════════════════════════════════════════
    //  Start / stop
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Starts recording the given stream URL. Returns false (synchronously)
     * when a recording is already active or the URL is unusable.
     *
     * @param userAgent the UA the engine's own sources speak (mirror of
     *        SmartPlayer's private constant, duplicated by the caller).
     * @param preferredHeight the video height the user is actually watching
     *        (player.videoFormat?.height) — picks the closest variant of a
     *        master playlist; null → best variant.
     * @param title v1.18.0 — the movie/series/channel name for the output
     *        filename (sanitized; null → the classic timestamp-only label).
     * @param playerOwned v1.19.9 — true (default) = the player armed this
     *        download (its onCleared stops it); false = a VOD info page
     *        armed it as a background download that outlives the player.
     * @param sourceKey v1.19.9 — the info page's VOD key for this download
     *        ("vodm:{id}" / "vode:{id}"), so re-entered pages can re-attach
     *        their progress UI to the running engine.
     */
    fun start(
        context: Context,
        url: String,
        userAgent: String,
        preferredHeight: Int?,
        resultCallback: ((RecResult) -> Unit)? = null,
        title: String? = null,
        playerOwned: Boolean = true,
        sourceKey: String? = null
    ): Boolean {
        if (job != null && job?.isActive == true) {
            Log.w(TAG, "Recording already in progress")
            return false
        }
        if (url.isBlank() || !url.startsWith("http")) return false
        val appCtx = context.applicationContext
        onResult = resultCallback
        this.playerOwned = playerOwned
        this.sourceKey = sourceKey
        stopRequested = false
        recordingStartMs = System.currentTimeMillis()
        recordingBytes.set(0L)
        progressPercent = -1
        phase = Phase.DOWNLOADING
        isRecording = true
        currentLabel = buildLabel(title)

        job = GlobalScope.launch(Dispatchers.IO) {
            val result = try {
                if (isHlsUrl(url)) recordHls(appCtx, url, userAgent, preferredHeight)
                else recordProgressive(appCtx, url, userAgent)
            } catch (e: Exception) {
                Log.e(TAG, "Recording failed: ${e.message}", e)
                RecResult(recordingBytes.get(), lastRecordingPath, true, e.message)
            } finally {
                isRecording = false
            }
            Log.i(TAG, "Recording done: bytes=${result.bytes} path=${result.path} err=${result.errorMessage}")
            try { onResult?.invoke(result) } catch (e: Exception) { Log.w(TAG, "result cb: ${e.message}") }
        }
        return true
    }

    /** Signals the recorder to finalize (remux + publish) and save. */
    fun stop() {
        stopRequested = true
    }

    /**
     * v1.18.0 — "Oria_<Title>_<timestamp>": the caller's title sanitized to
     * filename-safe characters (Arabic and Latin letters, digits, _ and -),
     * capped at 48 chars; a blank title falls back to the timestamp alone.
     */
    fun buildLabel(title: String?): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val clean = title
            ?.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_' }
            ?.joinToString("")
            ?.trim('_', '.')
            ?.takeIf { it.isNotBlank() }
            ?.take(48)
            ?: return "Oria_$stamp"
        return "Oria_${clean}_$stamp"
    }

    private fun isHlsUrl(url: String): Boolean =
        url.substringBefore('?').lowercase().endsWith(".m3u8") ||
            url.lowercase().contains(".m3u8")

    // ══════════════════════════════════════════════════════════════════════
    //  Pure HLS playlist model + parsers (unit-tested, no android.*)
    // ══════════════════════════════════════════════════════════════════════

    /** A variant stream from a master playlist. */
    data class HlsVariant(val bandwidth: Long, val height: Int?, val uri: String)

    /** An audio rendition from a master playlist (URI null → muxed audio). */
    data class HlsAudioRendition(val name: String?, val isDefault: Boolean, val uri: String?)

    /** An AES-128 key spec applying to a set of segments. */
    data class HlsKeySpec(val method: String, val uri: String?, val ivHex: String?)

    /** One media segment. */
    data class HlsSegment(
        val url: String,
        val durationSec: Double,
        val sequence: Long,
        val key: HlsKeySpec?,
        val rangeOffset: Long?,
        val rangeLength: Long?
    )

    /** A parsed media playlist. */
    data class HlsMediaInfo(
        val segments: List<HlsSegment>,
        val targetDurationSec: Double,
        val isVod: Boolean,
        val mediaSequence: Long,
        val initSegmentUrl: String?,
        val isFmp4: Boolean
    )

    /**
     * Parses a master playlist. Returns null when the text is a media
     * playlist (no #EXT-X-STREAM-INF).
     */
    fun parseMasterPlaylist(text: String, baseUrl: String): Pair<List<HlsVariant>, List<HlsAudioRendition>>? {
        if (!text.contains("#EXT-X-STREAM-INF")) return null
        val variants = mutableListOf<HlsVariant>()
        val audios = mutableListOf<HlsAudioRendition>()
        val lines = text.lines()
        var pendingBandwidth = -1L
        var pendingHeight: Int? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pendingBandwidth = attrLong(line, "BANDWIDTH") ?: -1L
                pendingHeight = attrResolutionHeight(line)
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                if (pendingBandwidth >= 0) {
                    variants.add(HlsVariant(pendingBandwidth, pendingHeight, resolveUrl(baseUrl, line)))
                    pendingBandwidth = -1
                    pendingHeight = null
                }
            } else if (line.startsWith("#EXT-X-MEDIA:")) {
                val type = attrValue(line, "TYPE")
                if (type == "AUDIO") {
                    audios.add(
                        HlsAudioRendition(
                            name = attrValue(line, "NAME"),
                            isDefault = attrValue(line, "DEFAULT")?.equals("YES", true) == true,
                            uri = attrValue(line, "URI")?.let { resolveUrl(baseUrl, it) }
                        )
                    )
                }
            }
        }
        return variants to audios
    }

    /** Parses a media playlist (segments, live/VOD state, keys, ranges). */
    fun parseMediaPlaylist(text: String, baseUrl: String): HlsMediaInfo {
        val segments = mutableListOf<HlsSegment>()
        var targetDuration = 10.0
        var isVod = false
        var mediaSequence = 0L
        var initSegmentUrl: String? = null
        var currentKey: HlsKeySpec? = null
        var pendingDuration = 0.0
        var pendingRangeOffset: Long? = null
        var pendingRangeLength: Long? = null
        var lastRangeEnd = -1L

        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:") -> {
                    pendingDuration = line.removePrefix("#EXTINF:")
                        .substringBefore(',').toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val spec = line.removePrefix("#EXT-X-BYTERANGE:")
                    val (len, off) = parseByteRange(spec, lastRangeEnd)
                    pendingRangeLength = len
                    pendingRangeOffset = off
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val body = line.removePrefix("#EXT-X-KEY:")
                    val method = attrValue(body, "METHOD") ?: "NONE"
                    currentKey = if (method.equals("NONE", true)) null
                    else HlsKeySpec(
                        method = method,
                        uri = attrValue(body, "URI")?.let { resolveUrl(baseUrl, it) },
                        ivHex = attrValue(body, "IV")
                    )
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    initSegmentUrl = attrValue(line.removePrefix("#EXT-X-MAP:"), "URI")
                        ?.let { resolveUrl(baseUrl, it) }
                }
                line.startsWith("#EXT-X-TARGETDURATION:") ->
                    targetDuration = line.substringAfter(':').toDoubleOrNull() ?: 10.0
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    mediaSequence = line.substringAfter(':').toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-ENDLIST") -> isVod = true
                line.startsWith("#") -> Unit // comments/unknown tags
                else -> {
                    // A URI line → a segment
                    val url = resolveUrl(baseUrl, line)
                    if (pendingRangeOffset != null && pendingRangeLength != null) {
                        lastRangeEnd = pendingRangeOffset + pendingRangeLength - 1
                    }
                    segments.add(
                        HlsSegment(
                            url = url,
                            durationSec = pendingDuration,
                            sequence = mediaSequence + segments.size,
                            key = currentKey,
                            rangeOffset = pendingRangeOffset,
                            rangeLength = pendingRangeLength
                        )
                    )
                    pendingDuration = 0.0
                    pendingRangeOffset = null
                    pendingRangeLength = null
                }
            }
        }
        val isFmp4 = initSegmentUrl != null ||
            segments.any { it.url.substringBefore('?').lowercase().endsWith(".m4s") }
        return HlsMediaInfo(segments, targetDuration, isVod, mediaSequence, initSegmentUrl, isFmp4)
    }

    /**
     * Picks the variant closest to what the user is watching; ties (e.g.
     * variants without a resolution) go to the higher bandwidth; with no
     * preference at all, the highest-bandwidth variant wins.
     */
    fun chooseVariant(variants: List<HlsVariant>, preferredHeight: Int?): HlsVariant {
        if (variants.size <= 1) return variants.first()
        if (preferredHeight != null && preferredHeight > 0) {
            val distances = variants.map { v ->
                v to kotlin.math.abs((v.height ?: 10_000) - preferredHeight)
            }
            val minDist = distances.minOf { it.second }
            return distances.filter { it.second == minDist }
                .maxByOrNull { it.first.bandwidth }!!.first
        }
        return variants.maxByOrNull { it.bandwidth } ?: variants.first()
    }

    /**
     * Resolves a playlist-relative URI against the playlist URL.
     * Handles absolute URLs, protocol-relative, root-absolute and
     * "dir/seg.ts" / "../seg.ts" relative forms.
     */
    fun resolveUrl(baseUrl: String, relative: String): String {
        if (relative.isEmpty()) return baseUrl
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        if (relative.startsWith("//")) {
            val scheme = baseUrl.substringBefore("://")
            return "$scheme:$relative"
        }
        val schemeIdx = baseUrl.indexOf("://")
        if (schemeIdx < 0) return relative
        val authorityEnd = baseUrl.indexOf('/', schemeIdx + 3)
        val origin = if (authorityEnd < 0) baseUrl else baseUrl.substring(0, authorityEnd)
        return if (relative.startsWith("/")) {
            origin + relative
        } else {
            val dirEnd = baseUrl.lastIndexOf('/')
            var combined = baseUrl.substring(0, dirEnd + 1) + relative
            while (combined.contains("/../")) {
                combined = combined.replaceFirst(Regex("[^/]+/\\.\\./"), "")
            }
            combined.replace("/./", "/")
        }
    }

    /** Parses an EXT-X-BYTERANGE spec ("n[@o]") against the previous end. */
    fun parseByteRange(spec: String, previousEnd: Long): Pair<Long, Long> {
        val s = spec.trim()
        val len = s.substringBefore('@').toLongOrNull() ?: 0L
        val off = if (s.contains('@')) {
            s.substringAfter('@').toLongOrNull() ?: 0L
        } else {
            previousEnd + 1
        }
        return len to off
    }

    /** IV for AES-128 when the playlist does not declare one: the segment
     *  media sequence number as a 16-byte big-endian value (HLS spec). */
    fun ivFromSequence(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var v = sequence
        for (i in 15 downTo 0) {
            iv[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        return iv
    }

    private fun attrValue(line: String, attr: String): String? {
        val m = Regex("""$attr=("([^"]*)"|([^,]*))""").find(line) ?: return null
        return m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { null }
    }

    private fun attrLong(line: String, attr: String): Long? =
        attrValue(line, attr)?.toLongOrNull()

    private fun attrResolutionHeight(line: String): Int? {
        val m = Regex("""RESOLUTION=(\d+)[xX](\d+)""").find(line) ?: return null
        return m.groupValues[2].toIntOrNull()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HTTP helpers
    // ══════════════════════════════════════════════════════════════════════

    private val http: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun httpGet(url: String, ua: String, range: Pair<Long, Long>? = null): ByteArray? {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Cache-Control", "no-cache")
            .apply {
                if (range != null) header("Range", "bytes=${range.first}-${range.second}")
            }
            .build()
        return http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                Log.w(TAG, "GET $url → ${res.code}")
                null
            } else {
                res.body?.byteStream()?.use { it.readBytes() }
            }
        }
    }

    /** Playlist fetch (text bodies: m3u8 manifests and key files are not
     *  downloaded through this — segments/keys use [httpGet]). */
    private fun httpGetText(url: String, ua: String): String? =
        httpGet(url, ua)?.toString(Charsets.UTF_8)

    // ══════════════════════════════════════════════════════════════════════
    //  HLS recording flow
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun recordHls(
        ctx: Context, url: String, ua: String, preferredHeight: Int?
    ): RecResult {
        val masterText = httpGetText(url, ua)
            ?: return RecResult(0, "", true, "Could not fetch the stream playlist")
        val master = parseMasterPlaylist(masterText, url)

        var videoPlaylistUrl = url
        var audioPlaylistUrl: String? = null
        if (master != null) {
            val (variants, audios) = master
            if (variants.isEmpty()) return RecResult(0, "", true, "Empty master playlist")
            videoPlaylistUrl = chooseVariant(variants, preferredHeight).uri
            audioPlaylistUrl = (audios.firstOrNull { it.isDefault && it.uri != null }
                ?: audios.firstOrNull { it.uri != null })?.uri
        }

        val videoText = httpGetText(videoPlaylistUrl, ua)
            ?: return RecResult(0, "", true, "Could not fetch the media playlist")
        val videoInfo = parseMediaPlaylist(videoText, videoPlaylistUrl)
        val audioInfo = audioPlaylistUrl?.let { au ->
            httpGetText(au, ua)?.let { parseMediaPlaylist(it, au) }
        }

        // Encrypted-stream guard: SAMPLE-AES is not decryptable client-side.
        val hasSampleAes = (videoInfo.segments + (audioInfo?.segments ?: emptyList()))
            .any { it.key != null && it.key.method.equals("SAMPLE-AES", true) }
        if (hasSampleAes) {
            return RecResult(0, "", true, "SAMPLE-AES encrypted stream — recording unsupported")
        }

        val dir = outputDir(ctx) ?: return RecResult(0, "", true, "No writable storage")
        val tmpVideo = File(dir, "$currentLabel.part")
        val tmpAudio = audioInfo?.let { File(dir, "${currentLabel}_a.part") }

        try {
            val keyCache = mutableMapOf<String, ByteArray>()
            if (videoInfo.isVod) {
                // ── VOD: download every segment at full speed ──
                downloadTrack(videoInfo, tmpVideo, ua, keyCache, isInitFirst = true)
                audioInfo?.let { downloadTrack(it, tmpAudio!!, ua, keyCache, isInitFirst = true) }
            } else {
                // ── LIVE: poll the playlist, append only NEW segments ──
                recordLiveTracks(videoPlaylistUrl, audioPlaylistUrl, tmpVideo, tmpAudio, ua, keyCache)
            }

            phase = Phase.FINALIZING
            return finalizeRecording(ctx, tmpVideo, tmpAudio, videoInfo.isFmp4)
        } finally {
            tmpVideo.delete()
            tmpAudio?.delete()
        }
    }

    /** Downloads all segments of one track into [out] (VOD path). */
    private suspend fun downloadTrack(
        info: HlsMediaInfo,
        out: File,
        ua: String,
        keyCache: MutableMap<String, ByteArray>,
        isInitFirst: Boolean
    ) {
        // v1.18.0 — VOD progress: downloaded seconds / total seconds. Only
        // the VIDEO track drives the readout (it dominates the download;
        // audio would double-count).
        val totalSec = info.segments.sumOf { it.durationSec }.takeIf { it > 0.0 }
        var doneSec = 0.0
        FileOutputStream(out).use { fos ->
            if (isInitFirst && info.initSegmentUrl != null) {
                httpGet(info.initSegmentUrl, ua)?.let { fos.write(it); recordingBytes.addAndGet(it.size.toLong()) }
            }
            for (seg in info.segments) {
                if (stopRequested) return
                appendSegment(seg, fos, ua, keyCache)
                if (totalSec != null) {
                    doneSec += seg.durationSec
                    progressPercent = ((doneSec / totalSec) * 100.0)
                        .toInt().coerceIn(0, 100)
                }
            }
            fos.flush()
        }
    }

    /** Live loop: polls each playlist and appends segments the moment they appear. */
    private suspend fun recordLiveTracks(
        videoPlaylistUrl: String,
        audioPlaylistUrl: String?,
        tmpVideo: File,
        tmpAudio: File?,
        ua: String,
        keyCache: MutableMap<String, ByteArray>
    ) {
        var lastVideoSeq = -1L
        var lastAudioSeq = -1L
        // Start at the live edge (the newest segment), not the back-buffer.
        val firstVideo = httpGetText(videoPlaylistUrl, ua)
            ?.let { parseMediaPlaylist(it, videoPlaylistUrl) }
        if (firstVideo != null && firstVideo.segments.isNotEmpty()) {
            lastVideoSeq = firstVideo.segments.last().sequence - 1
        }
        val firstAudio = audioPlaylistUrl?.let { httpGetText(it, ua)?.let { p -> parseMediaPlaylist(p, it) } }
        if (firstAudio != null && firstAudio.segments.isNotEmpty()) {
            lastAudioSeq = firstAudio.segments.last().sequence - 1
        }

        FileOutputStream(tmpVideo).use { vOut ->
            val aOut = tmpAudio?.let { FileOutputStream(it) }
            try {
                var initWritten = false
                while (!stopRequested) {
                    val vInfo = httpGetText(videoPlaylistUrl, ua)
                        ?.let { parseMediaPlaylist(it, videoPlaylistUrl) }
                    if (vInfo != null) {
                        if (!initWritten && vInfo.initSegmentUrl != null) {
                            httpGet(vInfo.initSegmentUrl, ua)?.let {
                                vOut.write(it); recordingBytes.addAndGet(it.size.toLong())
                            }
                            initWritten = true
                        }
                        for (seg in vInfo.segments) {
                            if (stopRequested) break
                            if (seg.sequence > lastVideoSeq) {
                                appendSegment(seg, vOut, ua, keyCache)
                                lastVideoSeq = seg.sequence
                            }
                        }
                        if (vInfo.isVod) break // event ended → completed
                    }
                    if (audioPlaylistUrl != null && aOut != null) {
                        val aInfo = httpGetText(audioPlaylistUrl, ua)
                            ?.let { parseMediaPlaylist(it, audioPlaylistUrl) }
                        if (aInfo != null) {
                            for (seg in aInfo.segments) {
                                if (stopRequested) break
                                if (seg.sequence > lastAudioSeq) {
                                    appendSegment(seg, aOut, ua, keyCache)
                                    lastAudioSeq = seg.sequence
                                }
                            }
                            if (aInfo.isVod) break
                        }
                    }
                    val targetMs = (vInfo?.targetDurationSec ?: 10.0) * 1000.0
                    val pollMs = (targetMs / 2).toLong().coerceIn(2000L, 10_000L)
                    delay(pollMs)
                }
                vOut.flush()
                aOut?.flush()
            } finally {
                aOut?.close()
            }
        }
    }

    /** Downloads one segment (Range-aware), decrypts if needed, appends it. */
    private fun appendSegment(
        seg: HlsSegment,
        out: FileOutputStream,
        ua: String,
        keyCache: MutableMap<String, ByteArray>
    ) {
        val range = if (seg.rangeOffset != null && seg.rangeLength != null) {
            seg.rangeOffset to (seg.rangeOffset + seg.rangeLength - 1)
        } else null
        var bytes = httpGet(seg.url, ua, range) ?: return
        val key = seg.key
        if (key != null && key.method.equals("AES-128", true) && !key.uri.isNullOrEmpty()) {
            val keyBytes = keyCache.getOrPut(key.uri) {
                httpGet(key.uri, ua) ?: ByteArray(0)
            }
            if (keyBytes.size == 16) {
                val iv = key.ivHex?.let { parseIvHex(it) } ?: ivFromSequence(seg.sequence)
                bytes = decryptAes128(bytes, keyBytes, iv) ?: bytes
            }
        }
        out.write(bytes)
        recordingBytes.addAndGet(bytes.size.toLong())
    }

    private fun parseIvHex(hex: String): ByteArray? {
        val h = hex.removePrefix("0x").removePrefix("0X").trim()
        if (h.length != 32) return null
        return try {
            ByteArray(16) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: Exception) { null }
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(data)
        } catch (e: Exception) {
            try {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                val plain = cipher.doFinal(data)
                // strip PKCS7 padding manually if present
                val pad = plain.last().toInt()
                if (pad in 1..16) plain.copyOf(plain.size - pad) else plain
            } catch (e2: Exception) {
                Log.w(TAG, "AES decrypt failed: ${e2.message}")
                null
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Progressive (direct file URL) recording
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun recordProgressive(ctx: Context, url: String, ua: String): RecResult {
        val dir = outputDir(ctx) ?: return RecResult(0, "", true, "No writable storage")
        val ext = progressiveExtension(url)
        val tmp = File(dir, "$currentLabel.part")
        try {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return RecResult(0, "", true, "HTTP ${res.code} downloading stream")
                val body = res.body?.byteStream() ?: return RecResult(0, "", true, "Empty stream body")
                // v1.18.0 — VOD progress: bytes / Content-Length when known.
                val totalBytes = res.body?.contentLength()?.takeIf { it > 0 }
                FileOutputStream(tmp).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        if (stopRequested) break
                        val n = body.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        total += n
                        recordingBytes.addAndGet(n.toLong())
                        if (totalBytes != null) {
                            progressPercent = ((total * 100.0) / totalBytes)
                                .toInt().coerceIn(0, 100)
                        }
                    }
                    fos.flush()
                }
            }
            phase = Phase.FINALIZING
            return finalizeRecording(ctx, tmp, null, isFmp4 = false, forcedExt = ext)
        } finally {
            tmp.delete()
        }
    }

    private fun progressiveExtension(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".mp4") || path.endsWith(".m4v") -> ".mp4"
            path.endsWith(".ts") -> ".ts"
            path.endsWith(".mkv") -> ".mkv"
            path.endsWith(".webm") -> ".webm"
            path.endsWith(".flv") -> ".flv"
            path.endsWith(".mov") -> ".mov"
            path.endsWith(".avi") -> ".avi"
            else -> ".mp4"
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Finalize: rename / remux + publish to MediaStore Downloads
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Turns the recorded temp track(s) into the final user-visible file:
     *   • TS track(s) → remux to MP4 (MediaExtractor + MediaMuxer); on any
     *     remux failure the clean TS is published instead (still playable).
     *   • fMP4 track (init+segments concatenation) or progressive download →
     *     the bytes are already the final container: plain rename.
     */
    private fun finalizeRecording(
        ctx: Context,
        tmpVideo: File,
        tmpAudio: File?,
        isFmp4: Boolean,
        forcedExt: String? = null
    ): RecResult {
        val bytes = recordingBytes.get()
        if (bytes <= 0L || !tmpVideo.exists() || tmpVideo.length() == 0L) {
            return RecResult(0, lastRecordingPath, false)
        }
        val dir = tmpVideo.parentFile ?: return RecResult(bytes, tmpVideo.absolutePath, false)
        val finalFile: File
        var mime = "video/mp4"

        if (forcedExt != null) {
            // Progressive download — byte-exact container already.
            finalFile = File(dir, currentLabel + forcedExt)
            tmpVideo.renameTo(finalFile)
            mime = mimeFor(finalFile.name)
        } else if (isFmp4) {
            // fMP4 HLS — init segment + .m4s segments concatenate to a
            // playable fMP4; the audio track (if demuxed) can't be merged
            // without a remux → keep video only (audio is usually muxed in
            // fMP4 IPTV anyway).
            finalFile = File(dir, "$currentLabel.mp4")
            tmpVideo.renameTo(finalFile)
        } else {
            // MPEG-TS → remux to a real MP4 (single track or video+audio).
            // If the demuxed audio track turns out unusable, retry with the
            // video track alone before giving up on the MP4 container.
            val mp4 = File(dir, "$currentLabel.mp4")
            var remuxed = try {
                remuxToMp4(listOfNotNull(tmpVideo, tmpAudio), mp4)
            } catch (e: Exception) {
                Log.w(TAG, "remux failed: ${e.message}")
                false
            }
            if (!remuxed && tmpAudio != null && tmpAudio.exists() && tmpAudio.length() > 0) {
                mp4.delete()
                remuxed = try {
                    remuxToMp4(listOf(tmpVideo), mp4)
                } catch (e: Exception) {
                    Log.w(TAG, "video-only remux failed: ${e.message}")
                    false
                }
            }
            if (remuxed && mp4.length() > 0) {
                finalFile = mp4
            } else {
                mp4.delete()
                // Fallback: publish the clean TS (playable in VLC/most players).
                finalFile = File(dir, "$currentLabel.ts")
                mime = "video/mp2t"
                if (tmpAudio != null && tmpAudio.exists() && tmpAudio.length() > 0) {
                    // Demuxed audio cannot live in the same TS container —
                    // publish the video track only and keep the audio as a
                    // sidecar file for the user.
                    val sidecar = File(dir, "${currentLabel}_audio.ts")
                    tmpAudio.renameTo(sidecar)
                }
                tmpVideo.renameTo(finalFile)
            }
        }
        lastRecordingPath = finalFile.absolutePath
        // v1.18.1 — mark PARTIAL saves for the Saved Videos library: a VOD
        // download that stopped before ~98% (user left mid-movie) still
        // remuxes into a perfectly PLAYABLE mp4 of everything downloaded so
        // far, but the library must badge it "incomplete" (and the entry
        // keeps offering "continue"). Live recordings (progress -1) and
        // full downloads never get the marker. Empty sidecar "<name>.partial".
        if (progressPercent in 0..97) {
            try {
                File(dir, "${finalFile.name}.partial").createNewFile()
            } catch (_: Exception) {
                // non-fatal: the library then shows it as complete
            }
        }
        publishToMediaStore(ctx, finalFile, mime)
        return RecResult(finalFile.length(), finalFile.absolutePath, false)
    }

    private fun outputDir(ctx: Context): File? {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: return null
        if (!dir.exists()) dir.mkdirs()
        return if (dir.exists()) dir else null
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "ts" -> "video/mp2t"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "flv" -> "video/x-flv"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        else -> "video/mp4"
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TS → MP4 remux (sample copy, no re-encode)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Remuxes one or two MPEG-TS files into an MP4 by copying samples
     * (MediaExtractor → MediaMuxer; no decode/encode). With two inputs the
     * video comes from the first and the audio from the second (demuxed
     * HLS), interleaved by presentation time; a muxed single input keeps
     * BOTH its video and audio track (per-sample track routing).
     *
     * Returns false (and deletes the output) when the tracks cannot be
     * copied — the caller then falls back to publishing the raw TS.
     */
    private fun remuxToMp4(inputs: List<File>, output: File): Boolean {
        val extractors = mutableListOf<MediaExtractor>()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            // fileIdx → (extractor track idx → muxer track idx)
            val trackMap = mutableMapOf<Int, Map<Int, Int>>()
            for ((fileIdx, file) in inputs.withIndex()) {
                val ex = MediaExtractor()
                ex.setDataSource(file.absolutePath)
                extractors.add(ex)
                val sel = mutableMapOf<Int, Int>()
                var videoTrack: Int? = null
                var audioTrack: Int? = null
                for (t in 0 until ex.trackCount) {
                    val fmt: MediaFormat = ex.getTrackFormat(t)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && videoTrack == null) videoTrack = t
                    else if (mime.startsWith("audio/") && audioTrack == null) audioTrack = t
                }
                // Single input → keep video AND audio. Second of two inputs →
                // audio only (the video file is the first input).
                val picks = if (inputs.size == 1) listOfNotNull(videoTrack, audioTrack)
                else if (fileIdx == 0) listOfNotNull(videoTrack ?: audioTrack)
                else listOfNotNull(audioTrack)
                for (t in picks) {
                    ex.selectTrack(t)
                    sel[t] = muxer.addTrack(ex.getTrackFormat(t))
                }
                if (sel.isNotEmpty()) trackMap[fileIdx] = sel
            }
            if (trackMap.isEmpty()) return false
            muxer.start()

            val info = MediaCodec.BufferInfo()
            var buffer = ByteBuffer.allocateDirect(1 shl 20)
            val written = mutableMapOf<Int, Long>() // fileIdx → samples written

            // Grows the sample buffer; false when a sample is absurdly large.
            fun ensureCapacity(size: Int): Boolean {
                if (size <= buffer.capacity()) return true
                val newCap = maxOf(size, buffer.capacity() * 2)
                if (newCap > (64 shl 20)) return false
                buffer = ByteBuffer.allocateDirect(newCap)
                return true
            }

            if (trackMap.keys == setOf(0)) {
                // ── Single input, possibly muxed A+V: route each sample by
                //    its own extractor track index. ──
                val ex = extractors[0]
                val sel = trackMap[0] ?: return false
                while (true) {
                    buffer.clear()
                    info.offset = 0
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) break
                    if (!ensureCapacity(size)) return false
                    val muxTrack = sel[ex.sampleTrackIndex]
                    if (muxTrack == null) {
                        // Sample from an unselected track — skip it.
                        ex.advance()
                        continue
                    }
                    info.size = size
                    info.presentationTimeUs = ex.sampleTime
                    info.flags = ex.sampleFlags
                    muxer.writeSampleData(muxTrack, buffer, info)
                    written[0] = (written[0] ?: 0L) + 1
                    ex.advance()
                }
            } else {
                // ── Two inputs (demuxed HLS): one selected track per file,
                //    interleaved by presentation time. ──
                data class Held(val fileIdx: Int, val data: ByteBuffer, val info: MediaCodec.BufferInfo, val track: Int)
                fun readNext(fileIdx: Int): Held? {
                    val ex = extractors.getOrNull(fileIdx) ?: return null
                    val sel = trackMap[fileIdx] ?: return null
                    buffer.clear()
                    val held = MediaCodec.BufferInfo()
                    held.offset = 0
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) return null
                    if (!ensureCapacity(size)) return null
                    held.size = size
                    held.presentationTimeUs = ex.sampleTime
                    held.flags = ex.sampleFlags
                    val copy = ByteBuffer.allocateDirect(size)
                    buffer.position(0)
                    buffer.limit(size)
                    copy.put(buffer)
                    copy.position(0)
                    buffer.clear()
                    ex.advance()
                    return Held(fileIdx, copy, held, sel[ex.sampleTrackIndex] ?: return null)
                }
                var a = readNext(0)
                var b = readNext(1)
                while (a != null || b != null) {
                    val pick = when {
                        a == null -> b
                        b == null -> a
                        else -> if (a.info.presentationTimeUs <= b.info.presentationTimeUs) a else b
                    } ?: break
                    muxer.writeSampleData(pick.track, pick.data, pick.info)
                    written[pick.fileIdx] = (written[pick.fileIdx] ?: 0L) + 1
                    if (pick === a) a = readNext(0) else b = readNext(1)
                }
            }
            // Every added track must have received samples, or muxer.stop()
            // throws — bail to the TS fallback instead.
            if (trackMap.keys.any { (written[it] ?: 0L) == 0L }) return false
            muxer.stop()
            return true
        } finally {
            try { muxer.release() } catch (_: Throwable) {}
            extractors.forEach { try { it.release() } catch (_: Throwable) {} }
            if (!(output.exists() && output.length() > 0)) output.delete()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  MediaStore publish (same delivery model as v1.16.0)
    // ══════════════════════════════════════════════════════════════════════

    private fun publishToMediaStore(context: Context, file: File, mime: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { inp ->
                            inp.copyTo(out, bufferSize = 64 * 1024)
                        }
                    }
                    val finalize = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, finalize, null, null)
                    Log.i(TAG, "Published to MediaStore: $uri")
                }
            } else {
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), arrayOf(mime), null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore publish failed (non-fatal): ${e.message}")
        }
    }
}
