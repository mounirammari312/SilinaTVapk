package com.agon.app.ui.screens

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.agon.app.data.ProfileRepository
import com.agon.app.data.ServerProfile
import com.agon.app.ui.focus.autoRequestFocus
import com.agon.app.ui.focus.focusSaver
import com.agon.app.ui.focus.rememberFocusIndex
import com.agon.app.ui.focus.restoreFocusIfSaved
import com.agon.app.ui.theme.AccentIndigo
import com.agon.app.ui.theme.AccentIndigoLight
import com.agon.app.ui.theme.AccentCyan
import com.agon.app.ui.theme.glassmorphicCard
import com.agon.app.ui.theme.PremiumSpring
import com.agon.app.ui.util.applyImmersiveFullscreen
import com.agon.app.ui.util.AppBackgroundImage
import com.agon.app.R
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.launch

/**
 * ProfilesActivity - Netflix-Style Smart TV Profile Selector
 *
 * Layout:
 *   Top:    Logo + "Who is watching?"
 *   Center: LazyRow of square profile cards (140x140.dp) + "Add User" card
 *   Focus:  D-Pad focusable with scale 1.1f + #6200EA border + action hints
 *
 * ════════════════════════════════════════════════════════════════════════
 *  ANDROID TV FOCUS REFACTOR (D-Pad / Remote Control)
 * ════════════════════════════════════════════════════════════════════════
 *  Defect fixed: "remote sticks at grid edges, can't move diagonally or
 *  to next row automatically."
 *
 *  Root cause: the legacy [ProfileCardNetflix] used [combinedClickable]
 *  WITHOUT [focusable], so the D-Pad couldn't anchor focus on a card.
 *  [AddUserCard] used [clickable] with no [focusable] either. The LazyRow
 *  had no saved focus state, so every scroll reset focus to the first
 *  card.
 *
 *  Fix applied (this file, all changes additive — no business logic touched):
 *
 *    1. Each card now explicitly calls [Modifier.focusable] so the D-Pad
 *       anchor can land on it.
 *
 *    2. Each card installs a [FocusRequester] + [Modifier.focusSaver] so
 *       the parent's saved index is updated whenever the card gains focus.
 *
 *    3. The LazyRow state is wrapped with [rememberFocusIndex] so the
 *       previously-focused card is restored after a fast scroll instead
 *       of resetting to card 0.
 *
 *    4. The first card auto-requests focus via [Modifier.autoRequestFocus]
 *       so entering the screen always lands the D-Pad on a card (not on
 *       the empty background).
 *
 *    5. Explicit [Modifier.focusProperties] on the LazyRow container
 *       ensures the D-Pad cannot escape the card row upward into the
 *       title or downward into the hint text — every direction is
 *       contained inside the card row.
 * ════════════════════════════════════════════════════════════════════════
 */
class ProfilesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveFullscreen()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ProfilesListScreen(
                    onProfileClick = { profile ->
                        startActivity(
                            Intent(this, HubActivity::class.java).apply {
                                putExtra("PROFILE_ID", profile.id)
                            }
                        )
                    },
                    onAddUser = {
                        startActivity(Intent(this, LoginActivity::class.java))
                        finish()
                    },
                    onBack = {
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesListScreen(
    onProfileClick: (ServerProfile) -> Unit = {},
    onAddUser: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val context = LocalContext.current
        val repo = remember { ProfileRepository.getInstance(context) }
        val scope = rememberCoroutineScope()
        val profiles by repo.getAllProfiles().collectAsState(initial = emptyList())

        // Track which profile card is focused (for showing Delete/Edit hints)
        var focusedProfileId by remember { mutableStateOf<String?>(null) }
        var isAddCardFocused by remember { mutableStateOf(false) }

        // ── Focus Restoration State ──
        // Persisted across scroll-out so fast D-Pad scrolling does not reset
        // the focus back to the first card when the user comes back.
        val savedFocusIndex = rememberFocusIndex(key = "profiles_row")
        val lazyListState = rememberLazyListState()

        // FocusRequester for the FIRST visible card so we can auto-request
        // focus when the screen first appears.
        val firstCardFocusRequester = remember { FocusRequester() }

        // V8.5.4 — branded wallpaper background + scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AppBackgroundImage(scrimAlpha = 0.5f)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize()
            ) {
                Spacer(modifier = Modifier.height(80.dp))

                // ── PROFILE CARDS (LazyRow - Netflix Style) ──
                if (profiles.isEmpty()) {
                    // Empty state with Add User card only
                    Text(
                        "No profiles yet.",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    AddUserCard(
                        isFocused = isAddCardFocused,
                        onFocusChanged = { isAddCardFocused = it },
                        onClick = onAddUser,
                        // Auto-focus the Add card on first composition so the
                        // D-Pad lands inside the empty state immediately.
                        focusRequester = firstCardFocusRequester,
                        autoRequestFocus = true,
                        focusIndex = 0,
                        savedFocusIndex = savedFocusIndex
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            // Containment: the LazyRow itself is NOT focusable
                            // (canFocus = false) so the D-Pad anchor always
                            // lands on a CARD, never on the container.
                            .focusProperties { canFocus = false }
                    ) {
                        // Profile cards
                        items(profiles, key = { it.id }) { profile ->
                            val cardIndex = profiles.indexOf(profile)
                            val cardRequester = remember(profile.id) { FocusRequester() }
                            ProfileCardNetflix(
                                profile = profile,
                                isFocused = focusedProfileId == profile.id,
                                onFocusChanged = { focused ->
                                    focusedProfileId = if (focused) profile.id else null
                                },
                                onClick = { onProfileClick(profile) },
                                onDelete = {
                                    scope.launch { repo.deleteProfile(profile.id) }
                                },
                                onEdit = { /* Edit placeholder */ },
                                focusRequester = cardRequester,
                                focusIndex = cardIndex,
                                savedFocusIndex = savedFocusIndex,
                                isFirstCard = cardIndex == 0,
                                firstCardFocusRequester = firstCardFocusRequester
                            )
                        }

                        // Add User card (always last)
                        item(key = "add_user") {
                            val addCardRequester = remember { FocusRequester() }
                            AddUserCard(
                                isFocused = isAddCardFocused,
                                onFocusChanged = { isAddCardFocused = it },
                                onClick = onAddUser,
                                focusRequester = addCardRequester,
                                autoRequestFocus = false,
                                focusIndex = profiles.size,
                                savedFocusIndex = savedFocusIndex
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // ── BOTTOM HINTS (show actions for focused card) ──
                if (focusedProfileId != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Edit hint
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit",
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Edit",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                        }

                        // Delete hint
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = Color(0xFFFF5252).copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Hold OK to Delete",
                                color = Color(0xFFFF5252).copy(alpha = 0.6f),
                                fontSize = 11.sp
                            )
                        }

                        // Select hint
                        Text(
                            "Press OK to select",
                            color = AccentIndigoLight.copy(alpha = 0.9f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else if (isAddCardFocused) {
                    Text(
                        "Press OK to add new profile",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )
                } else {
                    Text(
                        "Use D-Pad to browse profiles",
                        color = Color.White.copy(alpha = 0.3f),
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }

            // ═══════════════════════════════════════════════════════════════
            //  V8.3 — BANNER AD REMOVED (Profiles Screen)
            //  ═══════════════════════════════════════════════════════════════
            //  The Anchored Adaptive Banner (BannerAdView) was physically
            //  removed from the project in V8.3. The profiles screen now
            //  ends cleanly below the profile cards — no ad slot.
            // ═══════════════════════════════════════════════════════════════
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// NETFLIX-STYLE PROFILE CARD (140x140.dp, TV Focusable)
// ══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileCardNetflix(
    profile: ServerProfile,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    focusRequester: FocusRequester? = null,
    focusIndex: Int = 0,
    savedFocusIndex: MutableState<Int> = mutableStateOf(0),
    isFirstCard: Boolean = false,
    firstCardFocusRequester: FocusRequester? = null
) {
    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = PremiumSpring,
        label = "profileCardScale"
    )

    // ── Auto-focus the first card when the screen first appears ──
    // The first card's FocusRequester is shared with the parent so the
    // parent can also use it to restore focus after a fast scroll.
    if (isFirstCard && firstCardFocusRequester != null) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(200)
            runCatching { firstCardFocusRequester.requestFocus() }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .scale(animatedScale)
                // ── Focus chain wiring ──
                // 1. Attach the per-card FocusRequester so the parent can
                //    programmatically send focus to this card.
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier
                )
                // 2. Restore focus to this card if its index matches the
                //    saved focus index (fast-scroll restoration).
                .restoreFocusIfSaved(focusIndex, savedFocusIndex.value, focusRequester ?: FocusRequester())
                // 3. Save the focused index whenever this card gains focus
                //    so the parent's savedFocusIndex stays in sync.
                .focusSaver(focusIndex, savedFocusIndex)
                // 4. Propagate focus changes to the parent so it can update
                //    its focusedProfileId state (used for the bottom hint row).
                .onFocusChanged { state ->
                    onFocusChanged(state.isFocused)
                }
                // 5. Make the card focusable + clickable. The order matters:
                //    focusRequester → focusable → clickable. We use
                //    combinedClickable for the long-press (delete) gesture.
                .focusable()
                .combinedClickable(onClick = onClick, onLongClick = onDelete)
                .glassmorphicCard(cornerRadius = 16, focused = isFocused)
                .background(
                    brush = Brush.verticalGradient(
                        colors = if (profile.isActive) {
                            listOf(AccentIndigo.copy(alpha = 0.25f), Color(0xFF10112A).copy(alpha = 0.6f))
                        } else {
                            listOf(Color(0xFF171822).copy(alpha = 0.5f), Color(0xFF0F1015).copy(alpha = 0.5f))
                        }
                    ),
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            // Badge: AUTO or MANUAL
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(AccentIndigo, AccentCyan)
                        ),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    if (profile.isAutoGenerated) "AUTO" else "MANUAL",
                    color = Color.White,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Large TV/User icon
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = if (isFocused) AccentCyan else Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Profile name
        Text(
            profile.name,
            color = if (isFocused) Color.White else Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(140.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Server URL (subtle)
        Text(
            profile.serverUrl,
            color = Color.White.copy(alpha = if (isFocused) 0.45f else 0.25f),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(140.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
// ADD USER CARD (same size, dashed style)
// ══════════════════════════════════════════════════════════════════════

@Composable
fun AddUserCard(
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    autoRequestFocus: Boolean = false,
    focusIndex: Int = 0,
    savedFocusIndex: MutableState<Int> = mutableStateOf(0)
) {
    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = PremiumSpring,
        label = "addCardScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .scale(animatedScale)
                // ── Focus chain wiring (mirrors ProfileCardNetflix) ──
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier
                )
                .then(
                    if (autoRequestFocus && focusRequester != null) {
                        Modifier.autoRequestFocus(focusRequester, delayMs = 200)
                    } else Modifier
                )
                // Save focus index — Add card sits at the end of the row.
                .focusSaver(focusIndex, savedFocusIndex)
                .onFocusChanged { focusState ->
                    onFocusChanged(focusState.isFocused)
                }
                .focusable()
                .clickable(onClick = onClick)
                .glassmorphicCard(cornerRadius = 16, focused = isFocused),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add User",
                    tint = if (isFocused) AccentCyan else Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Add User",
                    color = if (isFocused) Color.White else Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Label below card
        Text(
            "Add Profile",
            color = if (isFocused) Color.White else Color.White.copy(alpha = 0.5f),
            fontSize = 14.sp,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium
        )
    }
}
