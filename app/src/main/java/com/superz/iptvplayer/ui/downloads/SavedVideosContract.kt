package com.superz.iptvplayer.ui.downloads

import java.util.Locale

/**
 * v1.18.1 — Saved Videos library: the PURE filename/display contracts,
 * unit-testable with no Android types (same pattern as VuHomeContract).
 *
 * The library lists what RecorderEngine leaves in the app's external
 * Downloads dir:
 *   • final saves  — "Oria_<Title>_<stamp>.mp4" (or ".ts" remux fallback);
 *   • partial saves — same file + an empty "<name>.partial" sidecar marker
 *     (written when a VOD download stopped below ~98% — the remuxed file
 *     still plays everything downloaded so far);
 *   • in-flight saves — "Oria_<Title>_<stamp>.part" while the engine is
 *     actively downloading (RecorderEngine.isRecording + lastRecordingPath);
 *   • audio sidecars — "…_a.part" / "…_audio.ts" (demuxed audio; deleted
 *     together with their video file, never listed on their own).
 */
object SavedVideosContract {

    /** Library entry state, computed by the ViewModel from the filesystem. */
    enum class State { COMPLETE, INCOMPLETE, DOWNLOADING }

    /** Extensions the offline library can play (final containers + .part). */
    private val VIDEO_EXTS = setOf("mp4", "ts", "mkv", "webm", "mov", "avi", "flv", "m4v")

    /** The empty sidecar that marks a save stopped before ~98%. */
    const val PARTIAL_MARKER_SUFFIX = ".partial"

    /**
     * A listable library file: starts with the recorder's "Oria_" prefix
     * and carries a playable extension (or the in-flight ".part"). Audio
     * sidecars and the ".partial" marker itself are NOT listable.
     */
    fun isVideoFile(name: String): Boolean {
        if (!name.startsWith("Oria_")) return false
        if (isAudioSidecar(name)) return false
        if (name.endsWith(PARTIAL_MARKER_SUFFIX)) return false
        val ext = name.lowercase().substringAfterLast('.', "")
        return ext in VIDEO_EXTS || ext == "part"
    }

    /** Demuxed-audio sidecars the recorder can leave next to a recording. */
    fun isAudioSidecar(name: String): Boolean =
        name.endsWith("_a.part") || name.endsWith("_audio.ts")

    /**
     * "Oria_<Title>_<stamp>.<ext>" → "<Title>".
     * The recorder's buildLabel writes "Oria_" + sanitized title + "_" +
     * "yyyyMMdd_HHmmss" + extension; this reverses it for display:
     *   • "Oria_Dune_2021_20261005_183012.mp4"  → "Dune 2021"
     *   • "Oria_فيلم_العروس_20261005_183012.mp4" → "فيلم العروس"
     *   • "Oria_20261005_183012.mp4" (no title)   → "2026-10-05 18:30"
     *   • ".part" / "_a" tails are stripped the same way.
     */
    fun displayName(fileName: String): String {
        var n = fileName
        // extension (last dot) — keeps dots INSIDE the title
        val dot = n.lastIndexOf('.')
        if (dot > 0) n = n.substring(0, dot)
        // in-flight temp: strip a trailing "_a" audio-suffix, then ".part"
        if (n.endsWith(".part")) n = n.removeSuffix(".part")
        if (n.endsWith("_a")) n = n.removeSuffix("_a")
        if (n.startsWith("Oria_")) n = n.removePrefix("Oria_")
        // the trailing stamp "yyyyMMdd_HHmmss" written by buildLabel
        n = n.replace(Regex("_\\d{8}_\\d{6}$"), "")
        n = n.trim('_', '-', '.')
        // timestamp-only label → readable neutral date
        val stamp = Regex("^(\\d{8})_(\\d{6})$").find(n)
        if (stamp != null) {
            val (d, t) = stamp.destructured
            return formatStamp(d, t)
        }
        // underscores were the sanitizer's stand-in for unsafe characters
        return n.replace('_', ' ').trim().ifEmpty { fileName }
    }

    /** "20261005" + "183012" → "2026-10-05 18:30" (locale-neutral). */
    fun formatStamp(datePart: String, timePart: String): String =
        "${datePart.substring(0, 4)}-${datePart.substring(4, 6)}-${datePart.substring(6, 8)} " +
            "${timePart.substring(0, 2)}:${timePart.substring(2, 4)}"

    /** Byte count → compact human size ("812 MB", "1.4 GB"). */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    /** Milliseconds → "H:MM:SS" (≥1h) or "M:SS" — player + list readouts. */
    fun formatDuration(ms: Long): String {
        val total = if (ms > 0) ms / 1000 else 0
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** File last-modified epoch → "dd/MM/yyyy HH:mm" (neutral, sortable). */
    fun formatDate(epochMs: Long): String {
        if (epochMs <= 0) return ""
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = epochMs
        return String.format(
            Locale.US, "%02d/%02d/%04d %02d:%02d",
            c.get(java.util.Calendar.DAY_OF_MONTH),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE)
        )
    }

    /** The marker sidecar path for a final file name. */
    fun markerNameFor(fileName: String): String = fileName + PARTIAL_MARKER_SUFFIX

    /** MIME hint for the local player (null → let ExoPlayer sniff). */
    fun mimeFor(fileName: String): String? = when (fileName.lowercase().substringAfterLast('.', "")) {
        "mp4", "m4v" -> "video/mp4"
        "ts", "part" -> "video/mp2t"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        else -> null
    }
}
