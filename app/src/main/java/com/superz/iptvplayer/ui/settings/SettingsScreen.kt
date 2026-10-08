package com.superz.iptvplayer.ui.settings

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.superz.iptvplayer.R
import com.superz.iptvplayer.ui.components.ParentalControl
import com.superz.iptvplayer.ui.components.PremiumInfoDialog
import com.superz.iptvplayer.ui.components.VuPremiumBannerPill
import com.superz.iptvplayer.ui.theme.OriaBranding
import com.superz.iptvplayer.ui.theme.VuBackButton
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.VuPremiumLogo
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import com.superz.iptvplayer.ui.theme.vuBackground

/**
 * v1.5.0 — the settings page, an exact copy of the reference's
 * SettingActivity + activity_setting.xml + item_setting.xml:
 *
 *  • app-wide gradient background;
 *  • top row: ly_back (marginStart 15sdp) + PremiumLL (marginTop 15sdp,
 *    marginStart 10sdp — the shared widgets);
 *  • setting_grid: 3-column grid, margin 20sdp, spacing 10sdp;
 *    each item = item_setting.xml: item_setting_bg (#33707070 radius 5sdp
 *    → gradient on focus), padding V 7sdp, centered row: icon 20sdp +
 *    name 10sdp (marginStart 7sdp).
 *
 * The 7 items and their order are SettingActivity.getSettingMenuModels,
 * VERBATIM: General Settings / Time Format / EPG Timeline / Parental
 * Control / Rate Us / Check Update / Language.
 *
 * Dialogs are the reference's fragments (350×200sdp window on a
 * black_65 scrim, header 15sdp, image_close 25×25sdp, save 130×25sdp).
 */
object VuSettingsContract {

    data class Item(val id: String, val labelRes: Int, val iconRes: Int)

    /** SettingActivity.getSettingMenuModels — order + ids verbatim. */
    val MENU = listOf(
        Item("general_setting", R.string.vu_general_settings, R.drawable.vu_image_general_setting),
        Item("time_format", R.string.vu_time_format, R.drawable.vu_image_time_format),
        Item("epg_time_line", R.string.vu_epg_timeline, R.drawable.vu_image_epg_timeline),
        Item("parental_control", R.string.vu_parental_control, R.drawable.vu_image_parent),
        Item("rate_us", R.string.vu_rate_us, R.drawable.vu_image_rate),
        Item("check_update", R.string.vu_check_update, R.drawable.vu_image_update_check),
        Item("language", R.string.vu_language, R.drawable.vu_ic_language)
    )

    /** GetSharedAppInfo.getGeneralSettingModel — labels + defaults, verbatim. */
    data class GeneralItem(
        val label: String?,
        val key: String,
        val default: Boolean
    )

    val GENERAL_ITEMS = listOf(
        GeneralItem("AutoStart On Boot UP", "general_autostart", false),
        GeneralItem("Show Full EPG", "general_full_epg", true),
        GeneralItem("Active Subtitle", "general_subtitle", false)
        // v1.18.1 — the "Auto Save While Watching" row was REMOVED from
        // here (user feedback: its setting belongs IN THE PLAYER, so the
        // user can pick any movie/series they like while watching and save
        // it). The toggle now lives in the player's top bar — the
        // "general_autosave" preference itself is unchanged, only its UI
        // moved (see PlayerViewModel.toggleAutoSave + PlayerScreen).
    )

    const val DEFAULT_TIME_FORMAT = "24"       // radio_button_24 checked
    const val DEFAULT_EPG_MODE = "only_epg"    // radio_button_epg checked
}

/** The settings store (the reference's SharedPreferenceHelper equivalent). */
object VuSettingsPrefs {

    private const val FILE = "vu_settings"

    fun bool(context: Context, key: String, default: Boolean): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(key, default)

    fun putBool(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(key, value).apply()
    }

    fun string(context: Context, key: String, default: String): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, default) ?: default

    fun putString(context: Context, key: String, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(key, value).apply()
    }

    /** "Show Full EPG" gate — ChannelViewViewModel reads this. */
    fun showFullEpg(context: Context): Boolean =
        bool(context, "general_full_epg", true)

    /**
     * v1.7.0 — "Active Subtitle" gate: SubtitleActivation (ui/player)
     * reads this and drives the active ExoPlayer's text-track selection.
     */
    fun activeSubtitle(context: Context): Boolean =
        bool(context, "general_subtitle", false)

    /**
     * v1.18.0 — "Auto Save While Watching" gate (OFF by default).
     * v1.18.1 — the ROW is gone from Settings → General (user feedback:
     * the setting belongs in the player); PlayerViewModel reads it here
     * and the player's top-bar toggle flips it (toggleAutoSave).
     */
    fun autoSave(context: Context): Boolean =
        bool(context, "general_autosave", false)

    /** The split-page clock's hour cycle ("24" default, like the dialog). */
    fun timeFormat(context: Context): String =
        string(context, "time_format", VuSettingsContract.DEFAULT_TIME_FORMAT)
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLanguage: () -> Unit,
    /** v2.0.4 — the premium banner's route (the shared golden pill). */
    onOpenPremium: () -> Unit = {}
) {
    val s = rememberVuSdp()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    // v2.1.0 — a PREMIUM member pressing the golden banner reads his own
    // subscription card (account + dates + enabled features) instead of
    // the upgrade page (user: "باقي ازرار premium… تعرض معلومات حسابه و
    // الميزات المفعلة").
    var showPremiumInfo by remember { mutableStateOf(false) }
    // v2.0.4 — the premium entry appears only when the panel enables it
    // (premium.enabled AND a host) — the same gate every entry shares.
    val premiumLive = OriaBranding.premiumEnabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .vuBackground()
    ) {
        // Top row — ly_back (15sdp start) + PremiumLL (15sdp top, 10sdp start).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = s.d(15), top = s.d(15))
        ) {
            VuBackButton(onClick = onBack)
            VuPremiumLogo(modifier = Modifier.padding(start = s.d(10)))
        }

        // ══════════ v2.0.4/v2.0.6 — THE PREMIUM BANNER ══════════
        // The user's directive: premium entries on the app's other pages
        // "مثل التطبيقات الاحترافية" — the SAME shared crown pill the
        // method-picker page carries, centered under the header (only when
        // the panel enables premium AND leaves the upgrade button visible
        // — the appearance tab's new toggle, with its panel-authored label).
        // The reference grid below is untouched.
        if (premiumLive && OriaBranding.premiumCtaVisible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = s.d(8)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(s.d(280))
                        .height(s.d(30))
                ) {
                    VuPremiumBannerPill(
                        label = OriaBranding.premiumCtaText
                            .ifBlank { stringResource(R.string.premium_cta) },
                        onClick = {
                            if (com.superz.iptvplayer.ui.theme.PremiumAccess.active) {
                                showPremiumInfo = true
                            } else {
                                onOpenPremium()
                            }
                        }
                    )
                }
            }
        }

        // setting_grid — 3 columns, margin 20sdp, spacing 10sdp.
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(s.d(20)),
            horizontalArrangement = Arrangement.spacedBy(s.d(10)),
            verticalArrangement = Arrangement.spacedBy(s.d(10)),
            modifier = Modifier.fillMaxSize()
        ) {
            gridItems(VuSettingsContract.MENU, key = { it.id }) { item ->
                VuSettingItem(
                    label = stringResource(item.labelRes),
                    iconRes = item.iconRes,
                    onClick = {
                        if (item.id == "language") onOpenLanguage()
                        else dialog = item.id
                    }
                )
            }
        }
    }

    // ── The reference's dialog fragments, as overlays ──
    dialog?.let { id ->
        when (id) {
            "general_setting" -> VuGeneralSettingDialog(onClose = { dialog = null })
            "time_format" -> VuTimeFormatDialog(onClose = { dialog = null })
            "epg_time_line" -> VuEpgTimelineDialog(onClose = { dialog = null })
            "parental_control" -> VuParentalDialog(onClose = { dialog = null })
            "rate_us" -> VuRateUsDialog(onClose = { dialog = null })
            "check_update" -> VuCheckUpdateDialog(onClose = { dialog = null })
        }
    }

    // v2.1.0 — the premium member's own subscription card.
    if (showPremiumInfo) {
        PremiumInfoDialog(onDismiss = { showPremiumInfo = false })
    }
}

/** item_setting.xml — item_setting_bg pill: icon 20sdp + name 10sdp. */
@Composable
private fun VuSettingItem(
    label: String,
    iconRes: Int,
    onClick: () -> Unit
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(s.d(5)))
            .then(
                if (focused) Modifier.background(VuPalette.PurpleBrush)
                else Modifier.background(VuPalette.PanelOverlay)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(vertical = s.d(7))
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(s.d(20))
        )
        Spacer(Modifier.width(s.d(7)))
        Text(
            label,
            color = VuPalette.White,
            fontSize = s.t(10),
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// The reference's dialog fragments: black_65 scrim (#a6000000) +
// 350×200sdp window (#2b2b37, radius 10dp) + header 15sdp centered
// (marginTop 15sdp) + image_close 25×25sdp (end 10sdp) + btn_save
// 130×25sdp (bottom 15sdp, 10sdp text).
// ─────────────────────────────────────────────────────────────────

/** black_65 — the fragments' full-screen scrim. */
private val Scrim = Color(0xA6000000)

/** colorBoxBg — the dialog window fill (blur_background + tint). */
private val BoxBg = Color(0xFF2B2B37)

@Composable
private fun VuDialogScaffold(
    title: String,
    onClose: () -> Unit,
    showSave: Boolean = true,
    saveLabel: String = stringResource(R.string.vu_save_changes),
    onSave: () -> Unit = {},
    content: @Composable () -> Unit
) {
    val s = rememberVuSdp()
    var closeFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Scrim)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = s.d(350), height = s.d(200))
                .clip(RoundedCornerShape(10.dp))
                .background(BoxBg)
        ) {
            // txt_header — 15sdp, centered, marginTop 15sdp.
            Text(
                title,
                color = VuPalette.White,
                fontSize = s.t(15),
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = s.d(15))
            )

            // btn_close — image_close 25×25sdp, end 10sdp (0.9→1.0 focus).
            Image(
                painter = painterResource(R.drawable.vu_image_close),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = s.d(10), top = s.d(15))
                    .size(s.d(25))
                    .graphicsScale(if (closeFocused) 1f else 0.9f)
                    .onFocusChanged { closeFocused = it.isFocused }
                    .focusable()
                    .clickable(onClick = onClose)
            )

            // Content area — between header and save button.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = s.d(15), bottom = s.d(15)),
                contentAlignment = Alignment.Center
            ) {
                content()
            }

            // btn_save — 130×25sdp, bottom 15sdp, 10sdp text.
            if (showSave) {
                var saveFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = s.d(15))
                        .size(width = s.d(130), height = s.d(25))
                        .clip(RoundedCornerShape(s.d(15)))
                        .then(
                            if (saveFocused) Modifier.background(VuPalette.PurpleBrush)
                            else Modifier.background(VuPalette.PanelOverlay)
                        )
                        .onFocusChanged { saveFocused = it.isFocused }
                        .focusable()
                        .clickable(onClick = onSave),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        saveLabel,
                        color = VuPalette.White,
                        fontSize = s.t(10),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/** The reference's 0.9→1.0 focus scale (focus listeners). */
private fun Modifier.graphicsScale(scaleFactor: Float): Modifier =
    this.scale(scaleFactor)

/** item_general_setting.xml — check icon 15sdp + label 10sdp (start 10sdp). */
@Composable
private fun VuGeneralRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(s.d(10))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onToggle)
    ) {
        Image(
            painter = painterResource(
                if (checked) R.drawable.vu_image_check else R.drawable.vu_image_uncheck
            ),
            contentDescription = null,
            modifier = Modifier.size(s.d(15))
        )
        Spacer(Modifier.width(s.d(10)))
        Text(
            label,
            color = VuPalette.White,
            fontSize = s.t(10),
            fontWeight = FontWeight.Medium
        )
        if (focused) { /* focus ring handled by parent's bg swap only */ }
    }
}

/** fragment_general_setting — the 3 general toggles (reference defaults). */
@Composable
private fun VuGeneralSettingDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val items = VuSettingsContract.GENERAL_ITEMS
    val checked = remember {
        items.map { mutableStateOf(VuSettingsPrefs.bool(context, it.key, it.default)) }
    }

    VuDialogScaffold(
        title = stringResource(R.string.vu_general_settings),
        onClose = onClose,
        onSave = {
            items.forEachIndexed { i, item ->
                VuSettingsPrefs.putBool(context, item.key, checked[i].value)
            }
            onClose()
        }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            items.forEachIndexed { i, item ->
                VuGeneralRow(
                    label = item.label.orEmpty(),
                    checked = checked[i].value,
                    onToggle = { checked[i].value = !checked[i].value }
                )
            }
        }
    }
}

/** MyRadioButtonStyle row — 14ssp text, ring + dot, paddingStart 10sdp. */
@Composable
private fun VuRadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    marginTop: Int = 0
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = s.d(marginTop), start = s.d(10), end = s.d(10))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onSelect)
    ) {
        // The radio ring: outer circle + inner dot when selected.
        Box(
            modifier = Modifier
                .size(s.d(18))
                .clip(CircleShape)
                .border(2.dp, VuPalette.White, CircleShape)
                .then(
                    if (selected) Modifier.border(2.dp, Color.Transparent, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(s.d(10))
                        .clip(CircleShape)
                        .background(VuPalette.White)
                )
            }
        }
        Spacer(Modifier.width(s.d(10)))
        Text(
            label,
            color = VuPalette.White,
            fontSize = s.ts(14),
            fontWeight = FontWeight.Medium
        )
    }
}

/** fragment_time_format — 24h (default) / 12h radios + save. */
@Composable
private fun VuTimeFormatDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var format by remember {
        mutableStateOf(VuSettingsPrefs.string(context, "time_format", "24"))
    }

    VuDialogScaffold(
        title = stringResource(R.string.vu_time_format),
        onClose = onClose,
        onSave = {
            VuSettingsPrefs.putString(context, "time_format", format)
            onClose()
        }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VuRadioRow(
                label = stringResource(R.string.vu_24_hours),
                selected = format == "24",
                onSelect = { format = "24" }
            )
            VuRadioRow(
                label = stringResource(R.string.vu_12_hours),
                selected = format == "12",
                onSelect = { format = "12" },
                marginTop = 10
            )
        }
    }
}

/** fragment_epg_time_line — "only with EPG" (default) / all radios + save. */
@Composable
private fun VuEpgTimelineDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var mode by remember {
        mutableStateOf(VuSettingsPrefs.string(context, "epg_timeline", "only_epg"))
    }

    VuDialogScaffold(
        title = stringResource(R.string.vu_epg_timeline),
        onClose = onClose,
        onSave = {
            VuSettingsPrefs.putString(context, "epg_timeline", mode)
            onClose()
        }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VuRadioRow(
                label = stringResource(R.string.vu_show_only_epg),
                selected = mode == "only_epg",
                onSelect = { mode = "only_epg" }
            )
            VuRadioRow(
                label = stringResource(R.string.vu_show_all_channels),
                selected = mode == "all",
                onSelect = { mode = "all" },
                marginTop = 10
            )
        }
    }
}

/** fragment_parent_password.xml + ParentPasswordDlgFragment — VERBATIM:
 *  two numberPassword pills ("Password" + "Confirm Password", 12sdp labels,
 *  45% guideline) each with its eye toggle (ic_eye_hide/show), and
 *  savePinCode()'s validation: empty → "Empty New Password!" / empty confirm
 *  → "Empty Confirm Password!" / mismatch → "No matched!" (the reference
 *  hardcodes these in Java — kept as-is). v1.12.1 FIX: the BasicTextField
 *  renders ALWAYS with the hint overlaid when empty (the old if/else never
 *  showed a field while empty — the password could not be typed at all). */
@Composable
private fun VuParentalDialog(onClose: () -> Unit) {
    val s = rememberVuSdp()
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var passVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    VuDialogScaffold(
        title = stringResource(R.string.vu_set_parent_password),
        onClose = onClose,
        onSave = {
            // v1.19.14 — the reference's savePinCode() validation order,
            // with BOTH sides digit-normalized first: an Arabic keyboard
            // types ٠٠٠٠ (Arabic-Indic), and a PIN saved that way must (a)
            // match its ASCII-typed confirmation and (b) land in the store
            // as clean ASCII so the PIN gate's verify() stays symmetric.
            val np = ParentalControl.normalizePin(password)
            val nc = ParentalControl.normalizePin(confirm)
            error = when {
                np.isEmpty() -> "Empty New Password!"
                nc.isEmpty() -> "Empty Confirm Password!"
                np != nc -> "No matched!"
                else -> null
            }
            if (error == null) {
                VuSettingsPrefs.putString(context, "parent_password", np)
                onClose()
            }
        }
    ) {
        Column {
            // ── Row 1: txt_password + LLEditPassword (45% guideline) ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.vu_password),
                    color = VuPalette.White,
                    fontSize = s.t(12),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(0.45f)
                )
                VuPasswordPill(
                    value = password,
                    onValueChange = { password = it },
                    visible = passVisible,
                    onToggle = { passVisible = !passVisible },
                    hint = stringResource(R.string.vu_enter_password),
                    modifier = Modifier.weight(0.55f)
                )
            }
            Spacer(Modifier.height(s.d(20)))   // LLEditConfirmPassword marginTop
            // ── Row 2: txt_confirm + LLEditConfirmPassword ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.vu_confirm_password),
                    color = VuPalette.White,
                    fontSize = s.t(12),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(0.45f)
                )
                VuPasswordPill(
                    value = confirm,
                    onValueChange = { confirm = it },
                    visible = confirmVisible,
                    onToggle = { confirmVisible = !confirmVisible },
                    hint = stringResource(R.string.vu_enter_confirm_password),
                    modifier = Modifier.weight(0.55f)
                )
            }
            // setError() — the reference's inline EditText error, below the row.
            error?.let {
                Spacer(Modifier.height(s.d(10)))
                Text(
                    it,
                    color = VuPalette.Yellow,
                    fontSize = s.t(8),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** LLEditPassword/LLEditConfirmPassword — et_agent_bg pill 25sdp tall with
 *  the numberPassword field + the eye (ic_eye_hide/show, 25×25sdp). */
@Composable
private fun VuPasswordPill(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggle: () -> Unit,
    hint: String,
    modifier: Modifier = Modifier
) {
    val s = rememberVuSdp()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(s.d(25))
            .clip(RoundedCornerShape(s.d(5)))
            .background(VuPalette.PanelOverlay)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = s.d(7)),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(
                    color = VuPalette.White,
                    fontSize = s.t(10)
                ),
                // inputType="numberPassword" — digits only, always masked unless shown.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                visualTransformation = if (visible) VisualTransformation.None
                else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (value.isEmpty()) {
                Text(
                    hint,
                    color = Color(0xFF9F94A4),
                    fontSize = s.t(10)
                )
            }
        }
        // image_password — the eye toggle (25×25sdp, tinted white).
        Image(
            painter = painterResource(
                if (visible) R.drawable.vu_ic_eye_show else R.drawable.vu_ic_eye_hide
            ),
            contentDescription = null,
            colorFilter = ColorFilter.tint(VuPalette.White),
            modifier = Modifier
                .size(s.d(25))
                .padding(s.d(4))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onToggle() }
        )
    }
}

/** fragment_rate_us — 5 stars + save (layout stripped from the reference;
 *  reconstructed in its dialog language: header + close + star row). */
@Composable
private fun VuRateUsDialog(onClose: () -> Unit) {
    val s = rememberVuSdp()
    val context = LocalContext.current
    var stars by remember {
        mutableStateOf(VuSettingsPrefs.string(context, "rate_us", "0").toIntOrNull() ?: 0)
    }

    VuDialogScaffold(
        title = stringResource(R.string.vu_rate_us),
        onClose = onClose,
        onSave = {
            VuSettingsPrefs.putString(context, "rate_us", stars.toString())
            onClose()
        }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(s.d(8))) {
            repeat(5) { i ->
                val filled = i < stars
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = if (filled) Color(0xFFFFC107) else Color(0xFF707070),
                    modifier = Modifier
                        .size(s.d(25))
                        .focusable()
                        .clickable { stars = i + 1 }
                )
            }
        }
    }
}

/** fragment_check_update — "App V{x} is currently their newest version". */
@Composable
private fun VuCheckUpdateDialog(onClose: () -> Unit) {
    val s = rememberVuSdp()
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    VuDialogScaffold(
        title = stringResource(R.string.vu_check_update),
        onClose = onClose,
        showSave = false
    ) {
        // ly_update — ic_update + txt_update.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(s.d(10))
        ) {
            Image(
                painter = painterResource(R.drawable.vu_ic_update),
                contentDescription = null,
                modifier = Modifier.size(s.d(24))
            )
            Text(
                stringResource(R.string.vu_newest_version, "V$version"),
                color = VuPalette.White,
                fontSize = s.t(12),
                fontWeight = FontWeight.Medium
            )
        }
    }
}
