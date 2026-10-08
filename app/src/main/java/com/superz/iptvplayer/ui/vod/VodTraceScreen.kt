package com.superz.iptvplayer.ui.vod

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superz.iptvplayer.R
import com.superz.iptvplayer.diagnostics.VodTrace
import com.superz.iptvplayer.ui.theme.AccentCyan
import com.superz.iptvplayer.ui.theme.AccentIndigo
import com.superz.iptvplayer.ui.theme.GlassSurface
import com.superz.iptvplayer.ui.theme.TextMuted
import com.superz.iptvplayer.ui.theme.TextPrimary
import com.superz.iptvplayer.ui.theme.TextSecondary
import com.superz.iptvplayer.ui.theme.vuGlobalDeepSpaceBackground
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.4.3 — VOD diagnostics viewer: the last raw get_vod_info /
 * get_series_info requests (credentials redacted) with copy/share.
 * Opened from the info pages (long-press the TV badge, or the
 * diagnostics chip when the info fetch failed).
 *
 * This is the tool that turns "the info page is empty" into a fixable
 * report: the recorded response body is the exact panel behavior.
 */
@Composable
fun VodTraceScreen(
    onBack: () -> Unit
) {
    var selected by remember { mutableStateOf<VodTrace.Entry?>(null) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    fun entryText(e: VodTrace.Entry): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.at))
        return buildString {
            appendLine("[$time] ${e.action}")
            appendLine("HTTP: ${e.http ?: "—"}  Error: ${e.error ?: "—"}")
            appendLine()
            append(e.body ?: "(no body)")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .vuGlobalDeepSpaceBackground(withCinematicGlow = false)
    ) {
        // ── Top bar ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = TextSecondary
                )
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(AccentIndigo.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text("TV", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.vod_trace_title),
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (selected != null) {
                IconButton(onClick = {
                    selected?.let { clipboard.setText(AnnotatedString(entryText(it))) }
                    copied = true
                }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.copy),
                        tint = if (copied) AccentCyan else TextSecondary
                    )
                }
                IconButton(onClick = {
                    selected?.let {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, entryText(it))
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    }
                }) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = stringResource(R.string.share),
                        tint = TextSecondary
                    )
                }
            }
        }
        if (copied) {
            Text(
                stringResource(R.string.copied),
                color = AccentCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        val entries = VodTrace.snapshot()
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.no_trace),
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        } else if (selected == null) {
            // ── Entry list ──
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                items(entries, key = { it.id }) { e ->
                    TraceRow(e) { selected = e }
                }
            }
        } else {
            // ── Entry detail: full body, scrollable, monospace ──
            val e = selected!!
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    e.action,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "HTTP ${e.http ?: "—"} · ${e.error ?: "OK"}",
                    color = if (e.error == null) AccentCyan else TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassSurface.copy(alpha = 0.5f))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        e.body ?: "(no body)",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 10.dp)
                ) {
                    TracePill(stringResource(R.string.copy), Icons.Filled.ContentCopy) {
                        clipboard.setText(AnnotatedString(entryText(e)))
                        copied = true
                    }
                    TracePill(stringResource(R.string.share), Icons.Filled.Share) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, entryText(e))
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    }
                    TracePill("←", null) { selected = null; copied = false }
                }
            }
        }
    }
}

@Composable
private fun TraceRow(e: VodTrace.Entry, onClick: () -> Unit) {
    val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.at))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GlassSurface.copy(alpha = 0.4f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when {
                        e.error != null -> TextPrimary
                        e.http != null && e.http in 200..299 -> AccentCyan
                        else -> TextMuted
                    }
                )
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                e.action.substringAfter("action=", e.action),
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$time · HTTP ${e.http ?: "—"} · ${e.error ?: "OK"} · ${e.body?.length ?: 0}B",
                color = TextMuted,
                fontSize = 10.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TracePill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(GlassSurface.copy(alpha = 0.7f))
            .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label, tint = TextSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
