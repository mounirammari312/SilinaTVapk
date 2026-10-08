package com.superz.iptvplayer.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.superz.iptvplayer.R
import com.superz.iptvplayer.data.remote.UpdateCenter
import com.superz.iptvplayer.data.remote.RemoteConfig
import com.superz.iptvplayer.ui.theme.OriaLogoImage
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import com.superz.iptvplayer.ui.theme.vuGoldenBorder
import com.superz.iptvplayer.ui.theme.vuGoldFace

/**
 * v2.0.0 — THE UPDATE DIALOG.
 *
 * Shown by MainActivity whenever UpdateCenter.pending is set (the panel
 * announced a newer versionCode + apkUrl). Design follows the app's
 * dialog idiom (dark scrim, #2B2B37 panel, gold accents) with the solid
 * gold CTA. Mandatory updates hide the "later" affordance entirely.
 */
@Composable
fun OriaUpdateDialog(
    info: RemoteConfig.UpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val s = rememberVuSdp()
    val downloading = UpdateCenter.downloading
    val percent = UpdateCenter.downloadPercent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(VuPalette.Black65),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF2B2B37))
                .padding(horizontal = 22.dp, vertical = 24.dp)
        ) {
            OriaLogoImage(modifier = Modifier.size(52.dp))

            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.update_available_title),
                color = VuGold.Text,
                fontSize = s.t(13),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(3.dp))
            Text(
                stringResource(R.string.update_available_version, info.versionName),
                color = VuPalette.White,
                fontSize = s.t(10),
                textAlign = TextAlign.Center
            )

            if (info.notes.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x1AFFFFFF))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(
                        info.notes,
                        color = VuPalette.White,
                        fontSize = s.t(9),
                        lineHeight = s.t(13)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (downloading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.update_downloading, percent),
                        color = VuGold.Text,
                        fontSize = s.t(10)
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    color = VuGold.Gold,
                    trackColor = Color(0x33FFFFFF),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                )
            } else {
                // ── UPDATE NOW — the solid-gold CTA ──
                Box {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .vuGoldenBorder(cornerRadius = 19.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .vuGoldFace()
                            .clickableNoRipple {
                                if (UpdateCenter.canInstall(context)) {
                                    UpdateCenter.startDownload(context, info) {}
                                } else {
                                    // first time: ask Android for the one-time
                                    // "install unknown apps" grant, then retry next tap
                                    context.startActivity(UpdateCenter.unknownSourcesIntent(context))
                                }
                            }
                    ) {
                        Text(
                            stringResource(R.string.update_now),
                            color = VuGold.OnGold,
                            fontSize = s.t(11),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                if (!info.mandatory) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.update_later),
                        color = VuPalette.Grey,
                        fontSize = s.t(10),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickableNoRipple(onClick = onDismiss)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.update_mandatory_note),
                        color = VuPalette.Grey,
                        fontSize = s.t(8),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
    BackHandler(enabled = !info.mandatory && !downloading) { onDismiss() }
}

/** Clickable without ripple (matches the app's pill idiom). */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
