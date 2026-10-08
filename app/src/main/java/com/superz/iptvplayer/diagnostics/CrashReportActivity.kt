package com.superz.iptvplayer.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Crash report viewer — deliberately built with plain Android Views
 * (NO Compose, NO app theme dependencies) so it can render even when
 * the Compose/UI layer is what crashed.
 *
 * Shown automatically on the launch after a crash. Copies the report
 * to the clipboard and offers a share button so the user can send the
 * exact error text to the developer.
 */
class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashDiagnostics.ensureInitialized(this)

        val report = CrashDiagnostics.pendingCrashText()

        // Auto-copy for convenience
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("crash report", report))
        } catch (_: Throwable) {
        }

        val dp = { v: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0B14"))
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = "تقرير الخطأ / Crash Report"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val hint = TextView(this).apply {
            text = "تم نسخ التقرير تلقائيًا. أرسله للمطور لإصلاح المشكلة.\n" +
                "Report copied to clipboard — share it with the developer."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        }

        val body = TextView(this).apply {
            text = report
            setTextColor(Color.parseColor("#EEF1FF"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.1f)
        }

        val scroll = ScrollView(this).apply {
            addView(body)
            setBackgroundColor(Color.parseColor("#12131F"))
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
        }

        fun styledButton(label: String, bg: Int, fg: Int, onClick: (View) -> Unit): Button =
            Button(this).apply {
                text = label
                setTextColor(fg)
                textSize = 14f
                isAllCaps = false
                setBackgroundColor(bg)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = dp(6) }
                setOnClickListener(onClick)
            }

        val share = styledButton("مشاركة التقرير", Color.parseColor("#7C6FFF"), Color.WHITE) {
            try {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, report)
                    putExtra(Intent.EXTRA_TITLE, "Oria crash report")
                }
                startActivity(Intent.createChooser(send, null))
            } catch (_: Throwable) {
            }
        }

        val close = styledButton("متابعة للتطبيق", Color.parseColor("#232536"), Color.parseColor("#EEF1FF")) {
            CrashDiagnostics.consumePendingCrash()
            CrashDiagnostics.clearBreadcrumbs()
            finish()
        }

        (close.layoutParams as LinearLayout.LayoutParams).apply {
            marginEnd = 0
            marginStart = dp(6)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(share)
            addView(close)
        }

        root.addView(title)
        root.addView(hint)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(buttons)

        setContentView(root)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Leaving by back button also consumes the report (one-shot).
        if (isFinishing) {
            CrashDiagnostics.consumePendingCrash()
            CrashDiagnostics.clearBreadcrumbs()
        }
    }
}
