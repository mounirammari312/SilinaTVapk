package com.superz.iptvplayer.ui.downloads

import android.content.Context
import android.os.Environment
import com.superz.iptvplayer.data.recording.RecorderEngine
import java.io.File

/**
 * v1.18.1 — one saved video as the library shows it.
 */
data class SavedVideo(
    val path: String,
    val fileName: String,
    val title: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val state: SavedVideosContract.State,
    /** DOWNLOADING only — the engine's live percent; -1 otherwise. */
    val activePercent: Int = -1,
    /** Sidecar files (audio "_a.part"/"_audio.ts" + the ".partial" marker)
     *  deleted together with the video file itself. */
    val sidecarPaths: List<String> = emptyList()
)

/**
 * v1.18.1 — the ONE filesystem scan behind both the Saved Videos library
 * and the hub card's count (identical semantics, computed in one place).
 *
 * Directory: the recorder's own output dir (app external Downloads, Movies
 * fallback) — offline by definition, no network, no database. A save is:
 *   DOWNLOADING — its ".part" temp is the engine's active target;
 *   INCOMPLETE  — a final file carrying the ".partial" marker (stopped
 *                 below ~98%; the saved part still plays);
 *   COMPLETE    — everything else.
 */
object SavedLibrary {

    fun outputDir(context: Context): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)

    fun scan(context: Context): List<SavedVideo> {
        val dir = outputDir(context) ?: return emptyList()
        val files = dir.listFiles()?.filter { it.isFile } ?: return emptyList()
        val names = files.map { it.name }.toSet()
        val activePath = if (RecorderEngine.isRecording) RecorderEngine.lastRecordingPath else ""

        return files
            .filter { SavedVideosContract.isVideoFile(it.name) }
            .map { f ->
                val base = f.name
                val state = when {
                    activePath.isNotEmpty() && activePath == f.absolutePath ->
                        SavedVideosContract.State.DOWNLOADING
                    names.contains(SavedVideosContract.markerNameFor(base)) ->
                        SavedVideosContract.State.INCOMPLETE
                    else -> SavedVideosContract.State.COMPLETE
                }
                val sidecars = mutableListOf<String>()
                if (state != SavedVideosContract.State.DOWNLOADING) {
                    // the partial marker (final files)
                    File(dir, SavedVideosContract.markerNameFor(base)).let {
                        if (it.exists()) sidecars += it.absolutePath
                    }
                    // demuxed audio sidecars (remux-fallback recordings)
                    val stem = base.substringBeforeLast('.', "")
                    listOf("${stem}_audio.ts", "${stem.removeSuffix(".part")}_a.part").forEach { s ->
                        if (s != base && names.contains(s)) sidecars += File(dir, s).absolutePath
                    }
                }
                SavedVideo(
                    path = f.absolutePath,
                    fileName = base,
                    title = SavedVideosContract.displayName(base),
                    sizeBytes = f.length(),
                    lastModifiedMs = f.lastModified(),
                    state = state,
                    activePercent = if (state == SavedVideosContract.State.DOWNLOADING) {
                        RecorderEngine.progress()
                    } else -1,
                    sidecarPaths = sidecars
                )
            }
            .sortedByDescending { it.lastModifiedMs }
    }

    /**
     * Deletes a library entry: the file, its sidecars, and — best effort —
     * the copy the recorder also published into the PUBLIC Downloads
     * collection (MediaStore), so the file manager view stays in sync too.
     */
    fun delete(context: Context, video: SavedVideo): Boolean {
        var ok = File(video.path).delete()
        video.sidecarPaths.forEach { p ->
            try {
                File(p).delete()
            } catch (_: Exception) {
            }
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                context.contentResolver.delete(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?",
                    arrayOf(video.fileName)
                )
            }
        } catch (_: Exception) {
            // the public copy (if any) simply stays — non-fatal
        }
        return ok
    }
}
