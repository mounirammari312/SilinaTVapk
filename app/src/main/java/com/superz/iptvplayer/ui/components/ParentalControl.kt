package com.superz.iptvplayer.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.superz.iptvplayer.R
import com.superz.iptvplayer.ui.settings.VuSettingsPrefs
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.rememberVuSdp

/**
 * v1.12.0 — Parental Control, replicated from the reference:
 *
 *  • ItemActivity.isXXX (verbatim): a category is adult when its LOWERCASED
 *    name contains "xxx" / "adult" / "porn". Applies to LIVE + VOD + Series
 *    category lists (the same ItemActivity serves all three main types).
 *  • Selecting such a category opens ParentControlDlgFragment — a password
 *    field with the show/hide eye — and compares against the stored PIN
 *    (SharedPreferenceHelper.getSharedPreferencePinCode, default "0000").
 *    Correct → the category opens; wrong → "your_pincode_is_incorrect".
 *  • getLiveChannelsByCategory(all) EXCLUDES the xxx category's channels
 *    from the "All" list — adult content is reachable ONLY through the
 *    PIN-gated category.
 *
 * The PIN itself is set in Settings → Parental Control (VuParentalDialog,
 * key "parent_password" — unchanged since v1.5.0).
 *
 * v1.19.14 — ARABIC DIGIT NORMALIZATION: an Arabic keyboard's digit row
 * produces ARABIC-INDIC digits (٠٠٠٠, U+0660–U+0669); on the masked PIN
 * field they render as four dots EXACTLY like "0000", yet the raw
 * comparison "٠٠٠٠" == "0000" fails — the user typed four zeros, saw
 * four dots, and the adult section refused to open every time. Persian
 * layouts add the EXTENDED set (۰–۹, U+06F0–U+06F9). EVERY comparison —
 * the entered string AND the stored value (a PIN saved from an Arabic
 * keyboard sits in prefs as Arabic-Indic and must keep matching) — runs
 * through [normalizePin]: digits fold to ASCII, whitespace/zero-width
 * joiners/LTR-RTL marks some IMEs append are dropped.
 */
object ParentalControl {

    /** ItemActivity.isXXX, verbatim. */
    fun isXxxName(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("xxx") || n.contains("adult") || n.contains("porn")
    }

    /**
     * v1.12.5 — the ONE xxx category excluded from "All" lists,
     * reference-verbatim (BaseActivity.getLiveGenre / getLiveCategory):
     * the reference iterates the whole category list and OVERWRITES
     * Constants.xxx_category_id on EVERY name match — so exactly ONE id
     * (the LAST match) is excluded, even when the account carries several
     * adult-named categories. Stalker genres match "xxx"/"adult";
     * Xtream categories also match "porn".
     *
     * Our pre-v1.12.5 code excluded EVERY matching category — visibly
     * fewer channels than the reference in the "All" list whenever an
     * account has more than one adult section.
     */
    fun xxxExcludedId(
        categories: List<com.superz.iptvplayer.data.db.Category>,
        portal: Boolean
    ): String? {
        var found: String? = null
        for (c in categories) {
            val n = c.name.lowercase()
            val match = if (portal) {
                n.contains("xxx") || n.contains("adult")
            } else {
                n.contains("xxx") || n.contains("adult") || n.contains("porn")
            }
            if (match) found = c.categoryId
        }
        return found
    }

    /**
     * v1.19.14 — folds every digit glyph a keyboard can produce onto the
     * ASCII one the PIN store uses, and drops the invisible baggage IMEs
     * ride along with (whitespace, zero-width joiners, LTR/RTL marks).
     *
     * ٠١٢٣٤٥٦٧٨٩ (U+0660–U+0669, Arabic-Indic) and ۰۱۲۳۴۵۶۷۸۹
     * (U+06F0–U+06F9, Extended/Persian) map onto 0–9; every other
     * printable character (letters, symbols) passes through untouched —
     * a PIN may be alphanumeric, only the GLYPH variants fold.
     */
    fun normalizePin(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            val ascii = when (c) {
                in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                else -> null
            }
            when {
                ascii != null -> sb.append(ascii)
                c.isWhitespace() -> Unit
                c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF' ||
                    c == '\u200E' || c == '\u200F' -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** The stored PIN (the reference's default: "0000"), digit-normalized
     *  so a value once SAVED from an Arabic keyboard keeps matching. */
    fun pin(context: android.content.Context): String =
        normalizePin(VuSettingsPrefs.string(context, "parent_password", "0000"))

    /** True when the entered PIN matches the stored one — BOTH sides
     *  normalized, so "٠٠٠٠" and "0000" are the same PIN. */
    fun verify(context: android.content.Context, entered: String): Boolean =
        normalizePin(entered) == pin(context)
}

/**
 * The PIN gate dialog (the reference's fragment_parent_control.xml):
 * centered password pill with the eye toggle + error line, over a scrim.
 * Remote/keyboard ready: the save pill takes D-pad focus.
 *
 * v1.19.13 — MODAL, the way a real dialog window is modal:
 *  • the SCRIM consumes every tap (and closes the gate, the reference
 *    DialogFragment's canceled-on-touch-outside) — before, the scrim and
 *    the dialog's own body had NO pointer handlers, so Compose hit-testing
 *    passed those taps STRAIGHT THROUGH to the clickable category cells
 *    behind: the user typed the PIN, tapped a few pixels off the small
 *    130×25 save pill, and the app opened whatever sat behind the dialog
 *    ("يتم النقر على ما وراء الزر او نافذة كلمة السر");
 *  • the dialog BODY consumes stray taps too (a miss inside the panel
 *    does nothing — never reaches the content behind);
 *  • the save pill rides in an invisible 130×45 hit zone (the visual pill
 *    keeps its 25sdp reference height — fingers get a forgiving target);
 *  • BackHandler closes the gate instead of the whole screen;
 *  • the password field auto-focuses (keyboard is up immediately) and the
 *    IME Done action submits, exactly like a login form.
 */
@Composable
fun VuPinGateDialog(
    categoryName: String,
    onResult: (Boolean) -> Unit
) {
    val s = rememberVuSdp()
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scrim = Color(0xA6000000)
    val boxBg = Color(0xFF2B2B37)
    val fieldFocus = remember { FocusRequester() }

    // Back (phone or TV remote) closes the GATE, not the screen behind it.
    BackHandler { onResult(false) }

    LaunchedEffect(Unit) {
        password = ""
        error = false
        runCatching { fieldFocus.requestFocus() }
    }

    fun submit() {
        if (ParentalControl.verify(context, password)) {
            onResult(true)
        } else {
            error = true
        }
    }

    // The scrim: EVERY tap lands here while the gate is open — nothing
    // behind the dialog is ever clickable. Outside-tap closes the gate
    // (the reference DialogFragment's touch-outside-cancel semantics).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onResult(false) }
    ) {
        // The panel: stray taps inside are CONSUMED (no-op) — a miss never
        // falls through to the screen behind.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = s.d(350), height = s.d(200))
                .clip(RoundedCornerShape(10.dp))
                .background(boxBg)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* consume — modal body */ }
        ) {
            // txt_header — "Parental Control" (15sdp, centered, marginTop 15sdp).
            Text(
                stringResource(R.string.vu_parental_control),
                color = VuPalette.White,
                fontSize = s.t(15),
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = s.d(15))
            )

            // The gated category's name under the header (8sdp, cyan).
            Text(
                categoryName,
                color = VuPalette.Cyan,
                fontSize = s.t(8),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = s.d(38), start = s.d(20), end = s.d(20))
            )

            // LLEditPassword — round_corner_text_bg pill 150×25sdp + the eye.
            // v1.12.1 FIX: the BasicTextField renders ALWAYS — the empty state
            // overlays the hint text (the old if/else NEVER showed a field
            // while empty, so the pill could not be typed into at all).
            // v1.19.13: auto-focus + IME Done = submit (login-form contract).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = s.d(150), height = s.d(25))
                        .clip(RoundedCornerShape(s.d(15)))
                        .background(VuPalette.PanelOverlay)
                        .padding(start = s.d(15)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            error = false
                        },
                        textStyle = TextStyle(
                            color = VuPalette.White,
                            fontSize = s.t(10)
                        ),
                        visualTransformation = if (visible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        singleLine = true,
                        // inputType="numberPassword" (the reference's LLEditPassword):
                        // the DIGIT keypad opens first — v1.19.14, the plain text
                        // keyboard let Arabic layouts type ٠٠٠٠ (Arabic-Indic zeros)
                        // which the raw compare rejected; normalization (above)
                        // stays the real guard — some IMEs emit Arabic-Indic digits
                        // even from the numeric pad.
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(fieldFocus)
                    )
                    if (password.isEmpty()) {
                        Text(
                            stringResource(R.string.vu_password),
                            color = Color(0xFF9F94A4),
                            fontSize = s.t(10)
                        )
                    }
                }
                Spacer(Modifier.width(s.d(8)))
                // image_password — the eye toggle.
                Image(
                    painter = painterResource(
                        if (visible) R.drawable.vu_ic_eye_show else R.drawable.vu_ic_eye_hide
                    ),
                    contentDescription = null,
                    modifier = Modifier
                        .size(s.d(18))
                        .clip(RoundedCornerShape(s.d(9)))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { visible = !visible }
                )
            }

            // The wrong-PIN line (the reference's toast, inline).
            if (error) {
                Text(
                    stringResource(R.string.vu_pincode_incorrect),
                    color = VuPalette.Yellow,
                    fontSize = s.t(8),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = s.d(52))
                )
            }

            // btn_close — image_close 25×25sdp, end 10sdp (0.9 focus scale).
            Image(
                painter = painterResource(R.drawable.vu_image_close),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = s.d(10), top = s.d(15))
                    .size(s.d(25))
                    .graphicsLayer { scaleX = 0.9f; scaleY = 0.9f }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onResult(false) }
            )

            // btn_save — 130×25sdp, bottom 15sdp. v1.19.13: the VISUAL pill
            // rides inside an invisible 130×45 hit zone that owns BOTH the
            // tap and the D-pad focus/press — the reference look is unchanged
            // (25sdp pill + focus gradient), but a finger that lands 10sdp
            // off the thin pill still submits instead of doing nothing (and
            // previously, of falling through the dialog onto the screen
            // behind).
            var saveFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = s.d(5))
                    .size(width = s.d(130), height = s.d(45))
                    .onFocusChanged { saveFocused = it.isFocused }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { submit() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = s.d(130), height = s.d(25))
                        .clip(RoundedCornerShape(s.d(15)))
                        .then(
                            if (saveFocused) Modifier.background(VuPalette.PurpleBrush)
                            else Modifier.background(VuPalette.PanelOverlay)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.vu_save_changes),
                        color = VuPalette.White,
                        fontSize = s.t(10),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
