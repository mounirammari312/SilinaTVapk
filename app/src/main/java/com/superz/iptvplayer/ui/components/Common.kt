package com.superz.iptvplayer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.superz.iptvplayer.data.db.CategoryWithCount
import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.ui.theme.GlassSurface
import com.superz.iptvplayer.ui.theme.GlassSurfaceAlt
import com.superz.iptvplayer.ui.theme.TextMuted
import com.superz.iptvplayer.ui.theme.TextPrimary
import com.superz.iptvplayer.ui.theme.TextSecondary
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.vuPlanCardRowStyle

/** Channel logo with graceful letter-avatar fallback.
 *  sizeDp > 0 → fixed-size logo (rows/sidebar);
 *  sizeDp <= 0 → fills [modifier] (grid cells, 16:9).
 *  plain = true → NO glass card behind the image (v1.15.0: the player's
 *  zap overlay shows the logo floating clean, the reference's look —
 *  a rectangular plate behind it visually wrecked the player). */
@Composable
fun ChannelLogo(
    logoUrl: String?,
    name: String,
    sizeDp: Int = 40,
    cornerDp: Int = 8,
    plain: Boolean = false,
    modifier: Modifier = Modifier
) {
    val sizing = if (sizeDp > 0) Modifier.size(sizeDp.dp) else modifier
    if (!logoUrl.isNullOrBlank()) {
        AsyncImage(
            model = logoUrl,
            contentDescription = name,
            contentScale = if (sizeDp > 0) ContentScale.Fit else ContentScale.Crop,
            modifier = sizing
                .clip(RoundedCornerShape(if (plain) 4.dp else cornerDp.dp))
                .then(if (plain) Modifier else Modifier.background(GlassSurfaceAlt))
        )
    } else {
        val letter = name.trim().firstOrNull()?.uppercase() ?: "?"
        Box(
            modifier = sizing
                .clip(RoundedCornerShape(if (plain) 6.dp else cornerDp.dp))
                .background(
                    if (plain) {
                        // Plain mode: a whisper of gold — readable on any
                        // video, but no "card" plate (v1.19.5 identity).
                        Brush.linearGradient(
                            listOf(
                                VuGold.Rich.copy(alpha = 0.22f),
                                VuGold.Gold.copy(alpha = 0.12f)
                            )
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(
                                VuGold.Rich.copy(alpha = 0.35f),
                                VuGold.Gold.copy(alpha = 0.16f)
                            )
                        )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                letter,
                color = TextPrimary,
                fontSize = if (sizeDp > 0) (sizeDp * 0.42f).sp else 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Channel row used by the browse list and the player sidebar.
 *  v1.19.5 — the selected row joins the golden identity: gold edge, a
 *  gold number badge with dark-bronze digits and a gold favorite star
 *  (the playing channel reads as the golden one, over the dark glass).
 *
 *  v2.2.3 — THE PLAN-CARD ROW CONTRACT (user directive: the full player
 *  joins the family): every channel row — the player's slide-out zap
 *  list and the catch-up lists — wears the plan cards' row skin through
 *  the shared [vuPlanCardRowStyle]: dark glass + static gold hairline at
 *  rest; the animated golden ring + warm-bronze glass face while the row
 *  is SELECTED (the playing channel glows like the active account card).
 *  The number badge keeps its solid-gold face on the selected row (the
 *  tier-architecture pattern — semantic content rides the family faces);
 *  the unselected rows read as a quiet column of little plan cards. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(
    channel: Channel,
    isFavorite: Boolean,
    selected: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 3.dp)
            .vuPlanCardRowStyle(cornerRadius = 10.dp, focused = false, selected = selected)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Number badge — solid gold on the selected row (the golden
            // marker), dark glass on the rest (the family's quiet face)
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) Brush.verticalGradient(
                            listOf(VuGold.Gold, VuGold.Rich)
                        )
                        else SolidColor(GlassSurface)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${channel.num}",
                    color = if (selected) VuGold.OnGold else TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(10.dp))
            ChannelLogo(logoUrl = channel.logo, name = channel.name, sizeDp = 40)
            Spacer(Modifier.width(10.dp))
            Text(
                channel.name,
                color = if (selected) TextPrimary else TextSecondary,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (isFavorite) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = VuGold.Text,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** Category rail item with channel count — selected edge is GOLD (v1.19.5). */
@Composable
fun CategoryItem(
    category: CategoryWithCount,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) GlassSurfaceAlt else Color.Transparent)
            .let {
                if (selected) it.border(1.dp, VuGold.Gold.copy(alpha = 0.5f), RoundedCornerShape(10.dp)) else it
            }
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            category.name.ifBlank { "—" },
            color = if (selected) TextPrimary else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text("${category.count}", color = TextMuted, fontSize = 10.sp)
    }
}

/** Simple empty-state block. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text,
            color = TextMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}
