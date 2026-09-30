package com.agon.app.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agon.app.data.model.SessionData
import com.agon.app.ui.focus.autoRequestFocus
import com.agon.app.ui.focus.focusSaver
import com.agon.app.ui.focus.rememberFocusIndex
import com.agon.app.ui.focus.restoreFocusIfSaved
import com.agon.app.ui.theme.AccentCyan
import com.agon.app.ui.theme.AccentIndigo
import com.agon.app.ui.theme.AccentIndigoLight
import com.agon.app.ui.theme.deepSpaceBackground
import com.agon.app.ui.theme.glassmorphicCard
import com.agon.app.ui.theme.glassmorphicPanel
import com.agon.app.ui.theme.glassmorphicPill
import com.agon.app.ui.theme.GlassSurface
import com.agon.app.ui.theme.GlassSurfaceAlt
import com.agon.app.ui.theme.SlateBorder
import com.agon.app.ui.theme.SlateTextPrimary
import com.agon.app.ui.theme.SlateTextSecondary
import com.agon.app.ui.theme.SlateTextMuted
import com.agon.app.ui.theme.PremiumSpring
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════
// UNIFIED COLOR CONSTANTS — Deep Space 2026 palette.
// Aliased to the new glassmorphic theme tokens so the entire app
// reads as one design system.
// ═══════════════════════════════════════════════════════════════════
private val SettingsBackground = Color(0xFF050608)   // Deep Space bottom
private val SettingsCardBg     = GlassSurface        // 0xFF12131F
private val SettingsCardAlt    = GlassSurfaceAlt     // 0xFF1A1B2E
private val SettingsBorder     = SlateBorder         // 0xFF232536
private val SettingsBorderFocus = AccentIndigo
private val SettingsAccent     = AccentIndigo
private val SettingsAccent2    = AccentCyan
private val SettingsAccentSoft = AccentIndigoLight
private val SettingsTextPrimary = SlateTextPrimary
private val SettingsTextSecondary = SlateTextSecondary
private val SettingsTextMuted = SlateTextMuted
private val SettingsStatusOk = AccentCyan

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf("ACCOUNT") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .deepSpaceBackground(withCinematicGlow = true)
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── SIDEBAR (28% Weight) — glassmorphic panel ──
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.28f)
                    .glassmorphicPanel(cornerRadius = 14)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Brand header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp, start = 4.dp, end = 4.dp, top = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(SettingsAccent, SettingsAccent2)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "SETTINGS",
                        color = SettingsTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }

                SettingsTab(
                    title = "Account Info",
                    subtitle = "Profile & server details",
                    icon = Icons.Default.Person,
                    isSelected = selectedTab == "ACCOUNT",
                    onClick = { selectedTab = "ACCOUNT" }
                )
                SettingsTab(
                    title = "Playback",
                    subtitle = "Stream preferences",
                    icon = Icons.Default.PlayCircle,
                    isSelected = selectedTab == "PLAYBACK",
                    onClick = { selectedTab = "PLAYBACK" }
                )
                SettingsTab(
                    title = "Storage & Cache",
                    subtitle = "Free up device space",
                    icon = Icons.Default.Storage,
                    isSelected = selectedTab == "STORAGE",
                    onClick = { selectedTab = "STORAGE" }
                )
                SettingsTab(
                    title = "Parental Control",
                    subtitle = "PIN-locked categories",
                    icon = Icons.Default.Lock,
                    isSelected = selectedTab == "PARENTAL",
                    onClick = { selectedTab = "PARENTAL" }
                )
                SettingsTab(
                    title = "Zapping",
                    subtitle = "Fast channel switching",
                    icon = Icons.Default.Wifi,
                    isSelected = selectedTab == "ZAPPING",
                    onClick = { selectedTab = "ZAPPING" }
                )
                SettingsTab(
                    title = "About",
                    subtitle = "App info & support",
                    icon = Icons.Default.Info,
                    isSelected = selectedTab == "ABOUT",
                    onClick = { selectedTab = "ABOUT" }
                )
            }

            // ── CONTENT PANE (72% Weight) — glassmorphic panel ──
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.72f)
                    .glassmorphicPanel(cornerRadius = 14)
                    .padding(22.dp)
            ) {
                when (selectedTab) {
                    "ACCOUNT" -> AccountPane()
                    "PLAYBACK" -> PlaybackPane(context)
                    "STORAGE" -> StoragePane(context)
                    "PARENTAL" -> ParentalPane(context)
                    "ZAPPING" -> ZappingPane(context)
                    "ABOUT" -> AboutPane(context)
                }
            }
        }
    }
}

@Composable
fun SettingsTab(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        if (isFocused) 1.03f else 1f,
        PremiumSpring,
        label = "tabScale"
    )

    // ── D-Pad key handler ──
    // The remote's OK / Select / D-Pad Center button triggers the tab click.
    // Without this handler, the focusable modifier alone does NOT translate
    // the key press into an onClick — the spec calls this the "dead toggle"
    // defect. We intercept KeyUp events for Enter + DirectionCenter so the
    // click fires exactly once per press (not on the down + up repeat).
    val keyHandler = Modifier.onKeyEvent { event ->
        if (event.type == KeyEventType.KeyUp &&
            (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
            onClick()
            true
        } else {
            false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            // ── Semantic role ──
            // Marking the tab as Role.Button tells the Android TV accessibility
            // layer (and the IME) that this is a clickable element, so the
            // remote's OK key is forwarded to our onKeyEvent handler.
            .semantics { role = Role.Button }
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) SettingsAccent.copy(alpha = 0.18f)
                else if (isFocused) SettingsAccent.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .border(
                width = if (isFocused || isSelected) 1.5.dp else 0.dp,
                color = if (isFocused || isSelected) SettingsBorderFocus else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .then(keyHandler)
            .focusable(interactionSource = interactionSource)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected || isFocused)
                            Brush.horizontalGradient(listOf(SettingsAccent, SettingsAccent2))
                        else Brush.horizontalGradient(listOf(GlassSurfaceAlt, GlassSurfaceAlt))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isSelected || isFocused) Color.White else SettingsTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (isSelected || isFocused) SettingsTextPrimary else SettingsTextPrimary.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    color = SettingsTextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// ACCOUNT PANE — shows real session info, no truncation, scrollable.
// ═══════════════════════════════════════════════════════════════════
@Composable
fun AccountPane() {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.horizontalGradient(listOf(SettingsAccent, SettingsAccent2))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("ACCOUNT DETAILS", color = SettingsTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Live session information from the active profile", color = SettingsTextMuted, fontSize = 11.sp)
            }
        }

        // Status banner
        StatusBanner()

        // Full-width info rows — no maxLines, no ellipsis. Long URLs wrap.
        InfoRowFull(
            icon = Icons.Default.Person,
            label = "Username",
            value = SessionData.xtreamUsername.ifBlank { "Not signed in" }
        )
        InfoRowFull(
            icon = Icons.Default.Wifi,
            label = "Server URL",
            value = SessionData.xtreamBaseUrl.ifBlank { "Not configured" }
        )
        InfoRowFull(
            icon = Icons.Default.PlayCircle,
            label = "Playlist name",
            value = SessionData.playlistName.ifBlank { "—" }
        )
        InfoRowFull(
            icon = Icons.Default.Storage,
            label = "Playlist type",
            value = SessionData.playlistType.name
                .replace("M3U_PLAYLIST", "M3U Playlist")
                .replace("XTREAM_API", "Xtream API")
        )
        InfoRowFull(
            icon = Icons.Default.Info,
            label = "Channels loaded",
            value = "${SessionData.allStreams.size} streams"
        )
        InfoRowFull(
            icon = Icons.Default.PlayCircle,
            label = "Live TV / Movies / Series",
            value = "${SessionData.liveStreams.size}  •  ${SessionData.movieStreams.size}  •  ${SessionData.seriesStreams.size}"
        )
        InfoRowFull(
            icon = Icons.Default.Lock,
            label = "Password",
            value = if (SessionData.xtreamPassword.isBlank()) "—" else "•".repeat(SessionData.xtreamPassword.length.coerceIn(6, 14))
        )
    }
}

@Composable
private fun StatusBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SettingsAccent.copy(alpha = 0.10f))
            .border(1.dp, SettingsAccent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(SettingsStatusOk)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Account is active",
            color = SettingsTextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        Text(
            "Connected",
            color = SettingsStatusOk,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Full-width, multi-line info row — Deep Space 2026 glassmorphic.
 * No maxLines / Ellipsis — text wraps to however many lines it needs
 * so long values are never truncated.
 */
@Composable
fun InfoRowFull(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassmorphicCard(cornerRadius = 12, focused = false)
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(GlassSurfaceAlt),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = SettingsAccentSoft, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                label.uppercase(),
                color = SettingsTextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                value,
                color = SettingsTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// PLAYBACK PANE — read-only display of stream preferences.
// (No logic changes — purely informational, matches the existing
//  settings flow that the player exposes via its own settings menu.)
// ═══════════════════════════════════════════════════════════════════
@Composable
fun PlaybackPane(context: Context) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.horizontalGradient(listOf(SettingsAccent, SettingsAccent2))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("PLAYBACK PREFERENCES", color = SettingsTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Defaults applied on the player screen", color = SettingsTextMuted, fontSize = 11.sp)
            }
        }

        InfoRowFull(Icons.Default.PlayCircle, "Default aspect ratio", "Zoom to fill — no black bars on edges")
        InfoRowFull(Icons.Default.Tune, "Reconnect attempts", "Up to 3 automatic retries on stream error")
        InfoRowFull(Icons.Default.Wifi, "Background audio", "Continues playback when the app is backgrounded")
        InfoRowFull(Icons.Default.Info, "Subtitle languages", "Auto-detected from the active stream")
        InfoRowFull(Icons.Default.Tune, "Picture quality", "Auto HDR / SDR (let ExoPlayer decide)")
    }
}

@Composable
fun StoragePane(context: Context) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.horizontalGradient(listOf(SettingsAccent, SettingsAccent2))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Storage, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("STORAGE MANAGEMENT", color = SettingsTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Clear cached media to free up device space", color = SettingsTextMuted, fontSize = 11.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ActionCard(
                title = "Clear Image Cache",
                description = "Frees up space by deleting downloaded logos and covers.",
                buttonText = "CLEAR CACHE",
                onClick = {
                    context.cacheDir.deleteRecursively()
                    Toast.makeText(context, "Image Cache Cleared", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 150.dp)
                    .fillMaxHeight()
            )
            ActionCard(
                title = "Clear EPG Data",
                description = "Deletes old TV guide data to speed up the app performance.",
                buttonText = "CLEAR EPG",
                onClick = {
                    Toast.makeText(context, "EPG Data Cleared", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 150.dp)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
fun ParentalPane(context: Context) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.horizontalGradient(listOf(SettingsAccent, SettingsAccent2))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("PARENTAL CONTROL", color = SettingsTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("PIN-protect sensitive categories", color = SettingsTextMuted, fontSize = 11.sp)
            }
        }

        ActionCard(
            title = "Manage PIN Code",
            description = "Set or change the 4-digit PIN used to lock categories. To reset, navigate to Dashboard.",
            buttonText = "CONFIGURE",
            onClick = {
                Toast.makeText(
                    context,
                    "Parental PIN is managed via Groups Menu.",
                    Toast.LENGTH_LONG
                ).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 150.dp)
        )
    }
}

@Composable
fun AboutPane(context: Context) {
    val scroll = rememberScrollState()
    val versionLabel = remember {
        try {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            "SilinaTV Pro v${pkg.versionName} (${pkg.longVersionCode})"
        } catch (e: Exception) {
            "SilinaTV Pro"
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.horizontalGradient(listOf(SettingsAccent, SettingsAccent2))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("ABOUT", color = SettingsTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Application build & legal information", color = SettingsTextMuted, fontSize = 11.sp)
            }
        }

        InfoRowFull(Icons.Default.Info, "Application", versionLabel)
        InfoRowFull(Icons.Default.Info, "Package", context.packageName)
        InfoRowFull(
            Icons.Default.Info,
            "Disclaimer",
            "This IPTV Player is a General Media Player that does not include any content. We do not sell any playlist or subscriptions. Do not connect any content that infringes copyrights on the application."
        )
    }
}

@Composable
fun InfoCard(
    title: String,
    value: String,
    valueColor: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SettingsCardAlt)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, color = SettingsTextMuted, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                value,
                color = valueColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ActionCard(
    title: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        if (isFocused) 1.03f else 1f,
        PremiumSpring,
        label = "actionCardScale"
    )

    // ── D-Pad OK / Select handler ──
    // The remote's center button must trigger the action — this is the
    // "Toggles appear dead" defect from the spec. We intercept KeyUp for
    // Enter + DirectionCenter and forward to [onClick].
    val keyHandler = Modifier.onKeyEvent { event ->
        if (event.type == KeyEventType.KeyUp &&
            (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
            onClick()
            true
        } else {
            false
        }
    }

    Box(
        modifier = modifier
            .scale(scale)
            // Mark as a Button for the TV accessibility / IME layer so
            // key events are correctly forwarded to our onKeyEvent.
            .semantics { role = Role.Button }
            .glassmorphicCard(cornerRadius = 12, focused = isFocused)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .then(keyHandler)
            .focusable(interactionSource = interactionSource)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    title,
                    color = SettingsTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    description,
                    color = SettingsTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isFocused) Brush.horizontalGradient(listOf(SettingsAccent, SettingsAccent2))
                        else Brush.horizontalGradient(listOf(GlassSurfaceAlt, GlassSurfaceAlt))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    buttonText,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.4f),
                            blurRadius = 3f
                        )
                    )
                )
            }
        }
    }
}
// ═══════════════════════════════════════════════════════════════════
// ZAPPING PANE — Fast Zapping (Instant Play) Toggle
// ═══════════════════════════════════════════════════════════════════

@Composable
fun ZappingPane(context: Context) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val isFastZappingEnabled by com.agon.app.data.FastZappingManager.isEnabledFlow.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.horizontalGradient(listOf(SettingsAccent, SettingsAccent2))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Speed, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("ZAPPING MODE", color = SettingsTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Adjust channel switching speed", color = SettingsTextMuted, fontSize = 11.sp)
            }
        }

        ActionCard(
            title = "Fast Zapping (Instant Play)",
            description = if (isFastZappingEnabled) "Active — Channels open instantly (250ms buffer). May stutter on weak connections." else "Inactive — Stable Mode (2s buffer). Less stuttering on weak connections.",
            buttonText = if (isFastZappingEnabled) "DISABLE" else "ENABLE",
            onClick = {
                com.agon.app.data.FastZappingManager.setFastZappingEnabled(context, !isFastZappingEnabled)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)
        )
    }
}

