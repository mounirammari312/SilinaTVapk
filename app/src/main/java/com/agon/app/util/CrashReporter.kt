package com.agon.app.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashReporter — V9.8
 *
 * A lightweight crash reporting system that doesn't require Firebase or
 * any external service. Captures uncaught exceptions and writes them to
 * a local crash log file in the app's internal storage.
 *
 * Features:
 *   - Installs a global UncaughtExceptionHandler
 *   - Captures the full stack trace + device info
 *   - Writes crash logs to /data/data/com.agon.app/files/crash_logs/
 *   - Keeps the last 10 crash logs (older ones auto-deleted)
 *   - Provides API to read/export crash logs from settings
 *
 * The buyer can later integrate Firebase Crashlytics by:
 *   1. Adding google-services.json
 *   2. Adding firebase-crashlytics dependency
 *   3. Calling FirebaseCrashlytics.getInstance().recordException(e)
 *     in CrashReporter.uncaughtException()
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val CRASH_DIR = "crash_logs"
    private const val MAX_LOGS = 10
    private const val MAX_LOG_SIZE = 512 * 1024L  // 512KB per log

    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null

    /**
     * Installs the global crash handler. Call from SilinaApplication.onCreate().
     * Only installs once — subsequent calls are no-ops.
     */
    fun install(context: Context) {
        if (appContext != null) return  // Already installed
        appContext = context.applicationContext

        try {
            // Create crash log directory
            val crashDir = File(context.filesDir, CRASH_DIR)
            if (!crashDir.exists()) crashDir.mkdirs()

            // Clean up old logs (keep only the last MAX_LOGS)
            cleanupOldLogs(crashDir)

            // Install the handler
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    writeCrashLog(context, thread, throwable)
                } catch (_: Throwable) {
                    // Never let the crash logger itself crash
                }
                // Pass to the previous handler (Android's default)
                previousHandler?.uncaughtException(thread, throwable)
            }

            Log.i(TAG, "CrashReporter installed — logs saved to ${crashDir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to install CrashReporter: ${e.message}")
        }
    }

    /**
     * Writes a crash log file with the full stack trace + device info.
     */
    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val fileName = "crash_$timestamp.txt"
        val logFile = File(context.filesDir, "$CRASH_DIR/$fileName")

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("══════════════════════════════════════════════════════════════")
        pw.println("  SilinaTV Pro — Crash Report")
        pw.println("  Time: $timestamp")
        pw.println("══════════════════════════════════════════════════════════════")
        pw.println()
        pw.println("── Device Info ──")
        pw.println("Manufacturer: ${Build.MANUFACTURER}")
        pw.println("Model: ${Build.MODEL}")
        pw.println("Device: ${Build.DEVICE}")
        pw.println("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        pw.println("App Version: ${getAppVersion(context)}")
        pw.println()
        pw.println("── Thread ──")
        pw.println("Thread: ${thread.name} (id=${thread.id})")
        pw.println("State: ${thread.state}")
        pw.println()
        pw.println("── Exception ──")
        pw.println("Type: ${throwable.javaClass.name}")
        pw.println("Message: ${throwable.message}")
        pw.println()
        pw.println("── Stack Trace ──")
        throwable.printStackTrace(pw)
        pw.println()
        pw.println("══════════════════════════════════════════════════════════════")

        logFile.writeText(sw.toString())

        // Also log to logcat for immediate visibility
        Log.e(TAG, "Crash captured — written to ${logFile.name}", throwable)
    }

    /**
     * Returns a list of all crash log files, newest first.
     */
    fun getCrashLogs(context: Context): List<File> {
        val crashDir = File(context.filesDir, CRASH_DIR)
        if (!crashDir.exists()) return emptyList()
        return crashDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash_") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Reads and returns the contents of the most recent crash log.
     * Returns null if no crash logs exist.
     */
    fun getLatestCrashLog(context: Context): String? {
        val logs = getCrashLogs(context)
        if (logs.isEmpty()) return null
        return logs.first().readText()
    }

    /**
     * Deletes all crash logs. Returns the number of files deleted.
     */
    fun clearAllLogs(context: Context): Int {
        val logs = getCrashLogs(context)
        var count = 0
        for (log in logs) {
            if (log.delete()) count++
        }
        Log.i(TAG, "Cleared $count crash log(s)")
        return count
    }

    /**
     * Removes old crash logs, keeping only the most recent MAX_LOGS.
     */
    private fun cleanupOldLogs(crashDir: File) {
        try {
            val logs = crashDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("crash_") }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            if (logs.size > MAX_LOGS) {
                logs.drop(MAX_LOGS).forEach { it.delete() }
                Log.i(TAG, "Cleaned up ${logs.size - MAX_LOGS} old crash log(s)")
            }
            // Also delete oversized logs
            logs.forEach { log ->
                if (log.length() > MAX_LOG_SIZE) {
                    log.delete()
                    Log.w(TAG, "Deleted oversized crash log: ${log.name} (${log.length()} bytes)")
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Manually records a non-fatal exception (like Crashlytics.recordException).
     * Useful for catching errors that don't crash the app but should be logged.
     */
    fun recordException(throwable: Throwable, context: Context? = appContext) {
        if (context == null) return
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val fileName = "nonfatal_$timestamp.txt"
            val logFile = File(context.filesDir, "$CRASH_DIR/$fileName")

            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("── Non-Fatal Exception ──")
            pw.println("Time: $timestamp")
            pw.println("Type: ${throwable.javaClass.name}")
            pw.println("Message: ${throwable.message}")
            pw.println("Stack Trace:")
            throwable.printStackTrace(pw)

            logFile.writeText(sw.toString())
            Log.w(TAG, "Non-fatal exception recorded: ${throwable.message}", throwable)
        } catch (_: Throwable) {}
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        } catch (_: Throwable) {
            "unknown"
        }
    }
}
