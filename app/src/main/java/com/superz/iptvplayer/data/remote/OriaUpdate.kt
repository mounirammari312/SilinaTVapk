package com.superz.iptvplayer.data.remote

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.superz.iptvplayer.BuildConfig
import java.io.File

/**
 * v2.0.0 — THE UPDATE CENTER.
 *
 * The panel's `update` block is the source of truth. [evaluate] runs
 * whenever a config is applied (cached at start, then the network
 * refresh): if the announced versionCode beats [BuildConfig.VERSION_CODE]
 * AND an apkUrl exists, [pending] becomes non-null and MainActivity
 * shows [com.superz.iptvplayer.ui.components.OriaUpdateDialog].
 *
 * Install path (all standard Android plumbing, zero extra permissions
 * beyond REQUEST_INSTALL_PACKAGES which the user grants once):
 *   DownloadManager → app-external files dir → FileProvider URI →
 *   ACTION_VIEW package-archive intent.
 */
object UpdateCenter {

    private const val TAG = "UpdateCenter"

    /** Non-null when a newer version is available and not yet dismissed. */
    var pending: RemoteConfig.UpdateInfo? by mutableStateOf(null)
        private set

    /** Download progress in percent, or -1 while not downloading. */
    var downloadPercent: Int by mutableStateOf(-1)
        private set

    /** True while a download is in flight (enables the progress UI). */
    var downloading: Boolean by mutableStateOf(false)
        private set

    /** Last dismissal of a specific version — survives restarts. */
    private const val PREFS = "oria_remote"
    private const val KEY_DISMISSED = "update_dismissed_version_code"

    /** True when this build is OLDER than the announced one. */
    fun isOutdated(info: RemoteConfig.UpdateInfo): Boolean =
        info.versionCode > BuildConfig.VERSION_CODE && info.apkUrl.startsWith("http")

    fun evaluate(config: RemoteConfig, context: Context) {
        val info = config.update
        if (!isOutdated(info)) {
            pending = null
            return
        }
        // Non-mandatory updates the user already declined stay declined
        // (until the panel announces a newer versionCode).
        val dismissed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_DISMISSED, -1)
        if (info.mandatory || dismissed != info.versionCode) {
            pending = info
        }
    }

    /** The user pressed "لاحقاً" on a non-mandatory update. */
    fun dismiss(context: Context) {
        pending?.let {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_DISMISSED, it.versionCode).apply()
        }
        pending = null
    }

    /** True if Android 8+ allows installing from this app (unknown sources). */
    fun canInstall(context: Context): Boolean {
        val pm = context.packageManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            pm.canRequestPackageInstalls()
        } else true
    }

    /** Settings deep-link for the one-time "allow unknown apps" grant. */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Download via the system DownloadManager into the app's external
     * files dir (no storage permissions needed) and open the installer
     * when done. [onInstallLaunched] fires when the install intent goes
     * out (the dialog can finish itself).
     */
    fun startDownload(
        context: Context,
        info: RemoteConfig.UpdateInfo,
        onInstallLaunched: () -> Unit
    ) {
        if (downloading) return
        downloading = true
        downloadPercent = 0

        val target = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "oria-update-${info.versionName}.apk"
        )
        target.delete() // a stale partial from an earlier attempt

        val id = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("ORIA ${info.versionName}")
            setDescription("ORIA update")
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, target.name)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setMimeType("application/vnd.android.package-archive")
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(id)

        // progress poller on a background thread
        Thread {
            while (downloading) {
                try {
                    val q = DownloadManager.Query().setFilterById(downloadId)
                    dm.query(q)?.use { c: Cursor ->
                        if (c.moveToFirst()) {
                            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            val sofar = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            when (status) {
                                DownloadManager.STATUS_RUNNING -> {
                                    if (total > 0) downloadPercent = (sofar * 100 / total)
                                }
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    downloadPercent = 100
                                    openInstaller(context, target, onInstallLaunched)
                                    stop()
                                    return@Thread
                                }
                                DownloadManager.STATUS_FAILED, DownloadManager.STATUS_PAUSED -> {
                                    if (status == DownloadManager.STATUS_FAILED) {
                                        Log.w(TAG, "download failed")
                                        stop()
                                        return@Thread
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "download poll: ${e.message}")
                }
                Thread.sleep(400)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun stop() {
        downloading = false
        downloadPercent = -1
    }

    /** Call if the user closes the dialog mid-download. */
    fun cancelUi() = stop()

    private fun openInstaller(context: Context, file: File, onLaunched: () -> Unit) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.update_provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            onLaunched()
        } catch (e: Exception) {
            Log.e(TAG, "openInstaller failed: ${e.message}")
            stop()
        }
    }
}
