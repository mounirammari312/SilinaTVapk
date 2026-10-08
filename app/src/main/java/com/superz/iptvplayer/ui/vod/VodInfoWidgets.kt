package com.superz.iptvplayer.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import coil.compose.AsyncImage
import com.superz.iptvplayer.R
import com.superz.iptvplayer.ui.theme.GlassSurfaceAlt
import com.superz.iptvplayer.ui.theme.TextMuted
import com.superz.iptvplayer.ui.theme.TextSecondary
import com.superz.iptvplayer.ui.theme.VuGold

/**
 * v1.4.3 — shared widgets for the redesigned (scrollable) movie/series
 * info pages: section headers, a 5-star rating row that mimics InfoField
 * alignment, and the reference-style cast row with round headshots
 * (Wikipedia-enriched when available, initials avatars otherwise).
 *
 * v1.19.5 — the identity accents go GOLD: the section dot and the
 * initials-avatar fallbacks swap their cyan/indigo for the app's gold
 * (the star rating was already gold and stays).
 */

private val StarGold = Color(0xFFFFC94A)

/** Section header: gold dot + bold title (v1.19.5: cyan → gold). */
@Composable
internal fun SectionHeader(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(VuGold.Gold)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            title,
            color = com.superz.iptvplayer.ui.theme.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Rating row aligned like [InfoField]: muted label (110dp) + ★★★☆☆ +
 * numeric value. Accepts raw panel ratings on the 0-10 OR 0-5 scale
 * (values above 5 are halved for the 5-star display; the numeric shows
 * the panel value as sent).
 */
@Composable
internal fun RatingField(label: String, rawRating: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(110.dp)
        )
        val v = rawRating?.trim()
            ?.trimEnd('/')
            ?.removeSuffix("/10")
            ?.toFloatOrNull()
        if (v == null) {
            Text(
                stringResource(R.string.no_info),
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            val five = (if (v > 5f) v / 2f else v).coerceIn(0f, 5f)
            val full = five.toInt() + if (five - five.toInt() >= 0.5f) 1 else 0
            Text(
                "★".repeat(full.coerceIn(0, 5)) + "☆".repeat((5 - full).coerceIn(0, 5)),
                color = StarGold,
                fontSize = 15.sp,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formatRating(v),
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun formatRating(v: Float): String =
    if (v % 1f == 0f) "${v.toInt()}" else String.format("%.1f", v)

/**
 * Cast row (reference): horizontal cards with a round 64dp headshot and
 * the actor name. Photos arrive async from [com.superz.iptvplayer.data.xtream.CastPhotoResolver]
 * — until then (or on miss) an initials avatar shows.
 */
@Composable
internal fun CastRow(
    names: List<String>,
    photos: Map<String, String>,
    modifier: Modifier = Modifier
) {
    if (names.isEmpty()) return
    LazyRow(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        items(names, key = { it }) { name ->
            CastCard(name, photos[name])
        }
    }
}

@Composable
private fun CastCard(name: String, photo: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(78.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .border(1.dp, GlassSurfaceAlt, CircleShape)
        ) {
            if (!photo.isNullOrBlank()) {
                AsyncImage(
                    model = photo,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    VuGold.Rich.copy(alpha = 0.32f),
                                    VuGold.Gold.copy(alpha = 0.12f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        initialsOf(name),
                        color = com.superz.iptvplayer.ui.theme.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** "Naomi Baker" → "NB"; single word → first letter. */
private fun initialsOf(name: String): String =
    name.split(' ', limit = 3)
        .mapNotNull { w -> w.trim().firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifBlank { "?" }

/**
 * v1.4.5 — reference parity: TMDB credits row (photo + actor name +
 * character name), shown INSTEAD of the name/Wikipedia [CastRow] when
 * the panel's info carried a tmdb_id and the TMDB /credits call
 * succeeded. Same visual language as [CastRow], plus the character
 * subtitle the reference app's VU screenshots show under the photo.
 */
@Composable
internal fun TmdbCastRow(
    members: List<com.superz.iptvplayer.data.xtream.TmdbCastResolver.Member>,
    modifier: Modifier = Modifier
) {
    if (members.isEmpty()) return
    LazyRow(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        items(members, key = { it.name + (it.character ?: "") }) { member ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(78.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .border(1.dp, GlassSurfaceAlt, CircleShape)
                ) {
                    if (!member.photoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = member.photoUrl,
                            contentDescription = member.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            VuGold.Rich.copy(alpha = 0.32f),
                                            VuGold.Gold.copy(alpha = 0.12f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                initialsOf(member.name),
                                color = com.superz.iptvplayer.ui.theme.TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    member.name,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!member.character.isNullOrBlank()) {
                    Text(
                        member.character,
                        color = com.superz.iptvplayer.ui.theme.TextSecondary.copy(alpha = 0.7f),
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
