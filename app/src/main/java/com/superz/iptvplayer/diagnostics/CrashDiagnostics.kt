package com.superz.iptvplayer.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic core — crash capture WITHOUT startup-order fragility.
 *
 * LESSON LEARNED (v1.0.1 field crash): the previous design initialized
 * `appContext` ONLY from CrashProvider.onCreate(). Any environment where
 * that provider does not run before MainActivity (some OEM ROM provider
 * ordering, direct-boot, provider startup failure, test environments)
 * made MainActivity throw UninitializedPropertyAccessException at its
 * FIRST line — instant crash-on-open with no report screen possible.
 *
 * New contract:
 *  • [ensureInitialized] is idempotent and called from BOTH the provider
 *    (earliest point) and MainActivity (belt-and-braces).
 *  • Every public method degrades gracefully when not yet initialized
 *    (returns defaults instead of throwing).
 *  • The uncaught-exception handler is still installed by the provider,
 *    before any library startup provider runs.
 */
object CrashDiagnostics {

    private const val DIR = "diagnostics"
    private const val CRASH_FILE = "last-crash.txt"
    private const val BREADCRUMB_FILE = "breadcrumbs.txt"

    @Volatile
    var appContext: Context? = null
        private set

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** Idempotent — safe to call from the provider, MainActivity, anywhere. */
    @Synchronized
    fun ensureInitialized(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun install(context: Context) {
        ensureInitialized(context)
        if (previousHandler == null) {
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(::handleCrash)
        }
        breadcrumb("diagnostics installed (pid=${Process.myPid()})")
    }

    // ─────────────────────────────────────────────────────────────
    // Breadcrumbs: startup-stage trail. Survives crashes because each
    // line is flushed to disk immediately.
    // ─────────────────────────────────────────────────────────────

    fun breadcrumb(stage: String) {
        try {
            val ctx = appContext ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            file(ctx, BREADCRUMB_FILE).appendText("$ts $stage\n")
        } catch (_: Throwable) {
        }
    }

    private fun file(ctx: Context, name: String): File {
        val external = ctx.getExternalFilesDir(null)
        val base = if (external != null) {
            File(external, DIR)
        } else {
            File(ctx.filesDir, DIR)
        }
        base.mkdirs()
        return File(base, name)
    }

    /** Latest N breadcrumbs (used in the report). */
    fun breadcrumbTail(n: Int = 25): String = try {
        val ctx = appContext ?: return ""
        file(ctx, BREADCRUMB_FILE).readLines().takeLast(n).joinToString("\n")
    } catch (_: Throwable) {
        ""
    }

    fun clearBreadcrumbs() {
        try {
            val ctx = appContext ?: return
            file(ctx, BREADCRUMB_FILE).delete()
        } catch (_: Throwable) {
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Crash capture
    // ─────────────────────────────────────────────────────────────

    /** True when a crash report from a previous run exists (read by MainActivity). */
    fun hasPendingCrash(): Boolean = try {
        val ctx = appContext ?: return false
        ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)
            .getBoolean("crashed_last_run", false) && file(ctx, CRASH_FILE).exists()
    } catch (_: Throwable) {
        false
    }

    fun pendingCrashText(): String = try {
        val ctx = appContext ?: return "(no crash report)"
        file(ctx, CRASH_FILE).readText()
    } catch (_: Throwable) {
        "(crash file unreadable)"
    }

    fun consumePendingCrash() {
        try {
            val ctx = appContext ?: return
            ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)
                .edit().putBoolean("crashed_last_run", false).apply()
        } catch (_: Throwable) {
        }
    }

    private fun handleCrash(thread: Thread, throwable: Throwable) {
        try {
            val ctx = appContext
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))

            val report = buildString {
                appendLine("══════════ IPTV PLAYER CRASH REPORT ══════════")
                appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("thread: ${thread.name} (id=${thread.id})")
                appendLine()
                appendLine("── device ──")
                appendLine("brand: ${Build.BRAND}  model: ${Build.MODEL}  device: ${Build.DEVICE}")
                appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString()}")
                appendLine("64-bit: ${Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()}")
                appendLine("kernel: ${System.getProperty("os.version") ?: "?"}")
                appendLine()
                appendLine("── startup breadcrumbs (last stages) ──")
                appendLine(breadcrumbTail())
                appendLine()
                appendLine("── stack trace ──")
                appendLine(sw.toString())
            }

            if (ctx != null) {
                // Write to BOTH external app-specific storage (user-reachable
                // via file manager at Android/data/.../files/diagnostics) and
                // internal storage as a backup.
                file(ctx, CRASH_FILE).writeText(report)
                try {
                    File(File(ctx.filesDir, DIR).apply { mkdirs() }, CRASH_FILE).writeText(report)
                } catch (_: Throwable) {
                }
                ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)
                    .edit().putBoolean("crashed_last_run", true).apply()
            }
        } catch (_: Throwable) {
        } finally {
            // Chain to the system handler (or die) — the report itself is
            // shown on the NEXT app launch by CrashReportActivity.
            val prev = previousHandler
            if (prev != null) {
                prev.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }
}

/**
 * Empty ContentProvider whose ONLY job is to install the crash handler
 * before any other startup component runs (providers execute in manifest
 * order — this one is declared in the app manifest, ahead of the
 * androidx.startup InitializationProvider that libraries merge in).
 *
 * Even if this provider somehow does not run, the app no longer crashes:
 * CrashDiagnostics self-initializes from MainActivity.
 */
class CrashProvider : android.content.ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        CrashDiagnostics.install(ctx)
        return true
    }

    override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, so: String?): android.database.Cursor? = null
    override fun getType(uri: android.net.Uri): String? = null
    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
    override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: android.net.Uri, values: android.content.ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
