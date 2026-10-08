package com.superz.iptvplayer.ui.login

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superz.iptvplayer.R
import com.superz.iptvplayer.ui.accounts.AccountsViewModel
import com.superz.iptvplayer.ui.theme.ErrorRed
import com.superz.iptvplayer.ui.theme.VuBackButton
import com.superz.iptvplayer.ui.theme.VuGold
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.VuPremiumLogo
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import com.superz.iptvplayer.ui.theme.vuLoginBackground
import com.superz.iptvplayer.ui.theme.vuPlanCardStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * activity_edit_portal.xml + EditPortalActivity — THE login form, verbatim:
 *
 *  • ly_back top-left (marginTop 25sdp);
 *  • LEFT half: PremiumLL (120×120sdp crown) vertically centered with a
 *    50sdp bottom margin; below it +30sdp the "List of User" pill
 *    (100×23sdp — the accounts page pill), centered on the half;
 *  • RIGHT half (from the 50% guideline): the form column
 *    (marginStart 10sdp, marginEnd 20sdp, vertically centered):
 *      header 15sdp → et_name → et_username → password row (eye toggle)
 *      → et_address → [browser: BROWSE button] → btn_add → important note;
 *  • field visibility follows EditPortalActivity's type switch exactly
 *    (VuLoginFlow.fieldsFor): XC = user+pass+url, M3U = url only,
 *      PORTAL = MAC+url (v1.5.0: REAL stalker engine — handshake →
 *      profile → account, via StalkerLoginViewModel), BROWSER = file
 *      path + BROWSE button (v1.5.0: REAL SAF file picker → local
 *      .m3u account);
 *  • edit mode (playlistId != null): fields pre-filled from Room, et_name
 *    disabled, button reads "EDIT USER", saving goes through
 *    AccountsViewModel.update (data layer — no engine involvement).
 *
 * The login engine itself is LoginViewModel — 100% UNTOUCHED (byte rule):
 * add-mode submits go straight into it, exactly like the old screen did.
 */
@Composable
fun EditPortalScreen(
    type: VuLoginFlow.PortalType,
    editPlaylistId: Long?,
    onBack: () -> Unit,
    onUserList: () -> Unit,
    onLoggedIn: () -> Unit,
    onSaved: () -> Unit,
    loginViewModel: LoginViewModel = viewModel(),
    accountsViewModel: AccountsViewModel = viewModel(),
    stalkerViewModel: StalkerLoginViewModel = viewModel()
) {
    val s = rememberVuSdp()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val fields = remember(type) { VuLoginFlow.fieldsFor(type) }
    val isEdit = editPlaylistId != null

    val ui by loginViewModel.ui.collectAsStateWithLifecycle()
    val sui by stalkerViewModel.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val playlists by accountsViewModel.playlists.collectAsStateWithLifecycle()
    val editing = remember(editPlaylistId, playlists) {
        editPlaylistId?.let { id -> playlists.firstOrNull { it.id == id } }
    }

    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // Edit mode: pre-fill from the DB row (et_name disabled, like the
    // reference's setEnabled(false)).
    LaunchedEffect(editing) {
        editing?.let { pl ->
            name = pl.name
            username = pl.username.orEmpty()
            password = pl.password.orEmpty()
            address = pl.server ?: pl.m3uUrl ?: ""
        }
    }

    // Engine success → the loading hand-off (same contract as the old screen).
    LaunchedEffect(ui.success) {
        if (ui.success) onLoggedIn()
    }

    // v1.5.0 — the portal / file engines' hand-off (same contract).
    LaunchedEffect(sui.success) {
        if (sui.success) onLoggedIn()
    }

    // v1.5.0 — portal / file engine errors surface as the reference's
    // toasts (EditPortalActivity toasts "This portal is not working…").
    LaunchedEffect(sui.error) {
        sui.error?.let { err ->
            val res = when (err) {
                StalkerLoginViewModel.Error.DUPLICATE_NAME -> R.string.vu_this_name_exit
                StalkerLoginViewModel.Error.INVALID_URL -> R.string.vu_portal_not_working
                // v2.1.0 — the free 3-account ceiling (repo backstop).
                StalkerLoginViewModel.Error.ACCOUNT_LIMIT -> R.string.error_account_limit
                else -> R.string.vu_portal_not_working
            }
            Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
            stalkerViewModel.consumeError()
        }
    }

    fun submit() {
        focusManager.clearFocus()
        when (type) {
            VuLoginFlow.PortalType.XC ->
                if (isEdit) {
                    editing?.let { pl ->
                        accountsViewModel.update(
                            pl.copy(username = username, password = password, server = address)
                        )
                    }
                    onSaved()
                } else {
                    loginViewModel.loginXtream(name, address, username, password)
                }

            VuLoginFlow.PortalType.M3U ->
                if (isEdit) {
                    editing?.let { pl -> accountsViewModel.update(pl.copy(m3uUrl = address)) }
                    onSaved()
                } else {
                    loginViewModel.loginM3U(name, address)
                }

            // v1.5.0 — the REAL stalker MAC engine (checkPortalUrl →
            // getToken → getProfile → addPortalToRealm).
            VuLoginFlow.PortalType.PORTAL -> {
                if (username.isBlank()) {
                    Toast.makeText(context, context.getString(R.string.vu_put_mac), Toast.LENGTH_SHORT).show()
                    return
                }
                if (isEdit) {
                    editing?.let { pl ->
                        accountsViewModel.update(pl.copy(server = address, username = username))
                    }
                    Toast.makeText(context, context.getString(R.string.vu_portal_edited), Toast.LENGTH_SHORT).show()
                    onSaved()
                } else {
                    stalkerViewModel.loginPortal(name, address, username)
                }
            }

            // v1.5.0 — the REAL local-file engine (checkFileUrl: the
            // SAF document uri was validated at pick time).
            VuLoginFlow.PortalType.BROWSER -> {
                if (address.isBlank()) return
                if (isEdit) {
                    editing?.let { pl -> accountsViewModel.update(pl.copy(m3uUrl = address)) }
                    Toast.makeText(context, context.getString(R.string.vu_portal_edited), Toast.LENGTH_SHORT).show()
                    onSaved()
                } else {
                    stalkerViewModel.loginBrowserFile(name, address)
                }
            }

            // v1.13.0 — 6-digit code: the generation server delivers 3
            // verified Xtream accounts and the flow continues exactly like
            // a manual login (accounts page → tap → loading/sync).
            VuLoginFlow.PortalType.CODE -> {
                loginViewModel.loginWithCode(address)
            }

            // v1.14.0 — unreachable: the QR pill on the method grid opens
            // the bridge dialog directly (AddPortalScreen); it never routes
            // to this form. Kept for when-exhaustiveness.
            VuLoginFlow.PortalType.QR -> Unit
        }
    }

    val addFocus = remember { FocusRequester() }

    // v1.5.0 — the REAL file picker (EditPortalActivity.getFileName:
    // GET_CONTENT with type */* and a “Select .m3u” chooser; ours uses
    // OpenDocument + takePersistableUriPermission so the file stays
    // readable across reboots — more robust than the reference’s
    // cache-dir copy, same UX). On pick: verify the display name ends
    // with .m3u (the reference’s driveFilePath.contains(".m3u") check),
    // take the persistable grant, then validate the content carries
    // stream URLs (Utils.checkFileContainUrl) before filling et_address.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val displayName = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex("_display_name")
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
        if (displayName == null || !displayName.endsWith(".m3u", true)) {
            Toast.makeText(context, context.getString(R.string.vu_only_m3u), Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        scope.launch(Dispatchers.IO) {
            val hasUrl = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val head = ByteArray(1 shl 20)
                    var n = 0
                    while (n < head.size) {
                        val r = input.read(head, n, head.size - n)
                        if (r < 0) break
                        n += r
                    }
                    String(head, 0, n, Charsets.UTF_8).contains("http")
                } ?: false
            }.getOrDefault(false)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (hasUrl) {
                    address = uri.toString()
                } else {
                    Toast.makeText(context, context.getString(R.string.vu_file_no_url), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .vuLoginBackground()
    ) {
        val halfW = maxWidth * 0.5f          // vertical_line guideline (50%)
        val screenH = maxHeight

        // ly_back — marginTop 25sdp.
        Box(modifier = Modifier.offset(y = s.d(25))) {
            VuBackButton(onClick = onBack)
        }

        // ══════════ LEFT HALF: PremiumLL + ly_user_list ══════════
        Box(
            modifier = Modifier
                .width(halfW)
                .fillMaxHeight()
        ) {
            // PremiumLL — centered in [0, height − 50sdp] (marginBottom 50sdp).
            // The logo's own height positions the pill (intrinsic measurement —
            // the loop-free v1.4.10 pattern).
            var logoH by remember { mutableStateOf(Dp.Unspecified) }
            val logoHeight = logoH.takeUnless { it == Dp.Unspecified } ?: s.d(120)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(screenH - s.d(50)),
                contentAlignment = Alignment.Center
            ) {
                VuPremiumLogo(
                    logoSizeSdp = 120,
                    onHeight = { logoH = it }
                )
            }
            // ly_buttons — marginTop 30sdp below PremiumLL, centered on the half.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(
                        y = (screenH - s.d(50)) / 2 + logoHeight / 2 + s.d(30)
                    ),
                contentAlignment = Alignment.Center
            ) {
                VuUserListPill(onClick = onUserList)
            }
        }

        // ══════════ RIGHT HALF: ly_edit (the form) ══════════
        Box(
            modifier = Modifier
                .offset(x = halfW)
                .width(halfW)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = s.d(10), end = s.d(20))
            ) {
                // txt_header — 15sdp white, centered.
                Text(
                    stringResource(
                        when (fields.header) {
                            VuLoginFlow.Header.LOGIN_DETAILS -> R.string.vu_enter_login_details
                            VuLoginFlow.Header.STALKER -> R.string.vu_stalker_connectivity
                            VuLoginFlow.Header.BROWSE_PLAYLIST -> R.string.vu_browse_playlist
                        }
                    ),
                    color = VuPalette.White,
                    fontSize = s.t(15),
                    fontWeight = FontWeight.Medium
                )

                // v1.19.0 — CODE mode: the HOW-TO panel (user request:
                // “عند النقر عليه وتفتح صفحة اضف طريقة الاستخدام للمستخدم
                // كي يعرف كيف يضيف الاكواد وعلى ماذا سيتحصل”) — three
                // numbered steps in gold + a “what you get” strip.
                if (type == VuLoginFlow.PortalType.CODE) {
                    VuCodeHowTo()
                }

                // et_name — marginTop 10sdp; disabled when editing.
                // v1.13.0 — CODE mode: no name field (accounts are
                // auto-named "Account 1..3" by the generation flow).
                if (type != VuLoginFlow.PortalType.CODE) {
                    VuFormTextField(
                        value = name,
                        onValueChange = { if (!isEdit) name = it },
                        hint = stringResource(R.string.vu_name),
                        enabled = !isEdit
                    )
                }

                // et_username — XC: username; PORTAL: MAC address hint.
                if (fields.username) {
                    VuFormTextField(
                        value = username,
                        onValueChange = { username = it },
                        hint = stringResource(hintRes(fields.usernameHint))
                    )
                }

                // LLEditPassword — password + ic_eye toggle (18sdp, marginEnd 10sdp).
                if (fields.password) {
                    VuPasswordField(
                        value = password,
                        onValueChange = { password = it },
                        visible = passwordVisible,
                        onToggle = { passwordVisible = !passwordVisible }
                    )
                }

                // et_address — portal URL / M3U URL / disabled M3U file path /
                // 6-digit code (v1.13.0: digits only, max 6, numeric keyboard).
                if (fields.address) {
                    val isCode = type == VuLoginFlow.PortalType.CODE
                    VuFormTextField(
                        value = address,
                        onValueChange = { raw ->
                            if (fields.addressEnabled) {
                                address = if (isCode) {
                                    raw.filter { it.isDigit() }.take(6)
                                } else raw
                            }
                        },
                        hint = stringResource(hintRes(fields.addressHint)),
                        enabled = fields.addressEnabled && !ui.loading,
                        keyboardType = if (isCode) KeyboardType.Number else KeyboardType.Text,
                        centered = isCode
                    )
                }

                // btn_browser — browser mode only: image_folder + BROWSE
                // (v1.5.0: launches the REAL system file picker).
                if (fields.browseButton) {
                    VuFormButton(
                        text = stringResource(R.string.vu_browse).uppercase(),
                        iconRes = R.drawable.vu_image_folder,
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.padding(top = s.d(15))
                    )
                }

                // btn_add — requestFocus (the XML's <requestFocus/>).
                // v2.2.3 — CODE mode: the plan-card contract itself (the
                // same skin as every other form's submit — one family);
                // the bespoke glow/solid-gold wrapper is retired.
                // v2.2.4 — BUG FIX (user screenshot): the CODE submit lost
                // its top padding in the v2.2.3 reskin — the field and the
                // button sat FUSED (the other submits keep s.d(15) here).
                if (type == VuLoginFlow.PortalType.CODE) {
                    VuFormButton(
                        text = stringResource(R.string.vu_code_button),
                        onClick = { submit() },
                        loading = ui.loading || sui.loading,
                        focusRequester = addFocus,
                        modifier = Modifier.padding(top = s.d(15))
                    )
                } else {
                    VuFormButton(
                        text = stringResource(
                            when {
                                isEdit -> R.string.vu_edit_user
                                else -> R.string.vu_add_user
                            }
                        ).uppercase(),
                        onClick = { submit() },
                        loading = ui.loading || sui.loading,
                        focusRequester = addFocus,
                        modifier = Modifier.padding(top = s.d(15))
                    )
                }

                // Error line (engine validation), under the button.
                ui.error?.let { err ->
                    Text(
                        vuLoginErrorText(err, ui.errorDetail),
                        color = ErrorRed,
                        fontSize = s.t(9),
                        modifier = Modifier.padding(top = s.d(5))
                    )
                }

                // tvImportantNote — 9sdp white, margins 10dp (literal).
                Text(
                    stringResource(R.string.vu_important_note),
                    color = VuPalette.White,
                    fontSize = s.t(9),
                    modifier = Modifier.padding(top = 10.dp, bottom = 10.dp)
                )
            }
        }
    }

    // btn_add's <requestFocus/>.
    LaunchedEffect(Unit) {
        runCatching { addFocus.requestFocus() }
    }
}

/** CustomEditText — round_corner_text_bg: 15sdp radius, 30sdp tall.
 *  v1.13.0: optional numeric keyboard + centered text for the 6-digit
 *  code field.
 *  v2.2.3 — THE PLAN-CARD ROW CONTRACT (user directive: the login FORM
 *  pages join the family): the field reads as a little plan-card row —
 *  DARK GLASS resting face + static gold hairline; the warm-bronze
 *  glass + animated golden ring while focused (the row edition, so the
 *  form never spins rings on fields the user isn't in). Text stays
 *  WHITE (input contrast over the deep glass), the hint warms to a
 *  dimmed gold. The reference's grey #33707070 + white focus stroke
 *  are retired. */
@Composable
private fun VuFormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    centered: Boolean = false
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(s.d(15))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = s.d(10))
            .height(s.d(30))
            .then(
                if (focused) Modifier.vuPlanCardStyle(cornerRadius = s.d(15), focused = true)
                else Modifier
                    .clip(shape)
                    .background(VuPalette.PanelOverlay.copy(alpha = VuGold.PLAN_GLASS_ALPHA))
                    .border(0.8.dp, VuGold.Gold.copy(alpha = 0.30f), shape)
            )
            .padding(start = s.d(15), end = s.d(10)),
        contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = enabled,
            textStyle = TextStyle(
                color = if (enabled) VuPalette.White else VuPalette.TintGrey,
                fontSize = s.t(10),
                textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center
                else androidx.compose.ui.text.style.TextAlign.Start
            ),
            cursorBrush = SolidColor(VuGold.Text),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = keyboardType
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
        )
        if (value.isEmpty()) {
            Text(
                hint,
                color = VuGold.Text.copy(alpha = 0.55f),
                fontSize = s.t(10),
                textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center
                else androidx.compose.ui.text.style.TextAlign.Start,
                modifier = if (centered) Modifier.fillMaxWidth() else Modifier
            )
        }
    }
}

/** LLEditPassword — the pill row + ic_eye_hide / ic_eye_show 18sdp toggle.
 *  v2.2.3 — THE PLAN-CARD ROW CONTRACT (same skin as the text fields):
 *  dark glass + static gold hairline at rest; the animated ring +
 *  warm-bronze glass while focused; gold eye glyph, gold cursor. */
@Composable
private fun VuPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggle: () -> Unit
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(s.d(15))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = s.d(10))
            .height(s.d(30))
            .then(
                if (focused) Modifier.vuPlanCardStyle(cornerRadius = s.d(15), focused = true)
                else Modifier
                    .clip(shape)
                    .background(VuPalette.PanelOverlay.copy(alpha = VuGold.PLAN_GLASS_ALPHA))
                    .border(0.8.dp, VuGold.Gold.copy(alpha = 0.30f), shape)
            )
            .padding(start = s.d(15)),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = VuPalette.White, fontSize = s.t(10)),
            visualTransformation =
                if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            cursorBrush = SolidColor(VuGold.Text),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Password
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
        )
        if (value.isEmpty()) {
            Text(
                stringResource(R.string.vu_password),
                color = VuGold.Text.copy(alpha = 0.55f),
                fontSize = s.t(10)
            )
        }
        // image_password — 18sdp, marginEnd 10sdp, gold tint (the family).
        Image(
            painter = painterResource(
                if (visible) R.drawable.vu_ic_eye_show else R.drawable.vu_ic_eye_hide
            ),
            contentDescription = stringResource(R.string.vu_password),
            colorFilter = ColorFilter.tint(VuGold.Text),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = s.d(10))
                .size(s.d(18))
                .clickable(onClick = onToggle)
        )
    }
}

/**
 * v1.19.0 — the CODE login HOW-TO panel (user request: “اضف طريقة
 * الاستخدام للمستخدم كي يعرف كيف يضيف الاكواد وعلى ماذا سيتحصل”):
 * a glass card with a thin gold border under the header — three
 * numbered steps (gold badges) + the “what you get” strip with the
 * live/movies/series icons. Pure text + icons, no logic.
 */
@Composable
private fun VuCodeHowTo() {
    val s = rememberVuSdp()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = s.d(10))
            .clip(RoundedCornerShape(s.d(10)))
            .background(VuPalette.PanelOverlay.copy(alpha = 0.55f))
            .border(s.d(0.7f), VuGold.Gold.copy(alpha = 0.45f), RoundedCornerShape(s.d(10)))
            .padding(horizontal = s.d(12), vertical = s.d(10))
    ) {
        // title — gold, with a small star spark
        Text(
            stringResource(R.string.vu_code_how_title),
            color = VuGold.Text,
            fontSize = s.t(11),
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        // step 1-3 — gold numbered badge + white text
        listOf(
            R.string.vu_code_step1,
            R.string.vu_code_step2,
            R.string.vu_code_step3
        ).forEachIndexed { i, stepRes ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = s.d(7))
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(top = s.d(0.5f))
                        .size(s.d(12))
                        .clip(CircleShape)
                        .background(VuGold.Gold.copy(alpha = 0.85f))
                ) {
                    Text(
                        "${i + 1}",
                        color = VuGold.OnGold,
                        fontSize = s.t(7),
                        fontWeight = FontWeight.Black
                    )
                }
                Spacer(Modifier.width(s.d(7)))
                Text(
                    stringResource(stepRes),
                    color = VuPalette.White.copy(alpha = 0.88f),
                    fontSize = s.t(8),
                    lineHeight = s.t(11),
                    fontWeight = FontWeight.Medium
                )
            }
        }
        // divider — thin gold rule
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(vertical = s.d(8))
                .height(1.dp)
                .background(VuGold.Gold.copy(alpha = 0.30f))
        )
        // “what you get” — icons row + line
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(s.d(10)),
            modifier = Modifier.padding(top = s.d(1))
        ) {
            listOf(Icons.Filled.LiveTv, Icons.Filled.Movie, Icons.Filled.VideoLibrary).forEach { iv ->
                Icon(
                    iv,
                    contentDescription = null,
                    tint = VuGold.Text,
                    modifier = Modifier.size(s.d(12))
                )
            }
        }
        Text(
            stringResource(R.string.vu_code_gets_line),
            color = VuGold.Text.copy(alpha = 0.95f),
            fontSize = s.t(8),
            lineHeight = s.t(11),
            fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = s.d(5))
        )
    }
}

/** btn_add / btn_browser — btn_select_type: 30sdp pill.
 *  v2.2.3 — THE PLAN-CARD CONTRACT (user directive: "اِصل نفس تصميم
 *  الأزرار مثل عقد بطاقة الخطة… الصفحات التي تفتح لتسجيل الدخول Mac
 *  و ملف m3u و رابط m3u و xtream"): EVERY submit/browse button on the
 *  form pages — MAC, M3U file, M3U link, Xtream, Browse and the 6-digit
 *  code journey alike — wears the plan cards' exact skin through the
 *  shared [vuPlanCardStyle]: animated golden ring (0.8sdp rest → 1.6sdp
 *  lit), DARK GLASS resting face, warm-bronze GLASS lit face, GOLD label
 *  (+ gold icon) on both faces. The reference's grey #33707070 face, the
 *  purple focus gradient and the code button's bespoke solid-gold hero
 *  are retired — one family, one contract. */
@Composable
private fun VuFormButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    loading: Boolean = false,
    focusRequester: FocusRequester? = null,
    // v1.19.0-era goldMode kept as a benign no-op parameter so call
    // sites stay source-compatible; the plan-card face IS the gold face.
    goldMode: Boolean = false,
    onFocused: ((Boolean) -> Unit)? = null
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(s.d(30))
            .vuPlanCardStyle(cornerRadius = s.d(15), focused = focused)
            .then(
                focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
            )
            .onFocusChanged {
                focused = it.isFocused
                onFocused?.invoke(it.isFocused)
            }
            .clickable(enabled = !loading, onClick = onClick)
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = VuGold.Text,
                strokeWidth = 2.dp,
                modifier = Modifier.size(s.d(18))
            )
        } else {
            if (iconRes != null) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(VuGold.Text),
                    modifier = Modifier
                        .padding(start = s.d(10))
                        .size(s.d(20))
                )
                Spacer(Modifier.width(s.d(10)))
            }
            Text(
                text,
                color = VuGold.Text,
                fontSize = s.t(12),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (iconRes != null) Spacer(Modifier.width(s.d(20)))
        }
    }
}

/** ly_user_list — the accounts page's 100×23sdp pill, replicated here.
 *  v1.19.5 (user request: “فقط زر List of User أيضا يتبع الهوية الذهبية”).
 *  v2.2.3 — THE PLAN-CARD CONTRACT (user directive: the login FORM pages
 *  join the family): the shared [vuPlanCardStyle] — animated golden ring,
 *  dark-glass rest, warm-bronze lit face, GOLD icon + text on both faces
 *  (the bespoke glow/solid-gold layers retired). */
@Composable
private fun VuUserListPill(onClick: () -> Unit) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(width = s.d(100), height = s.d(23))
            .vuPlanCardStyle(cornerRadius = s.d(15), focused = focused)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.vu_icon_user),
                contentDescription = null,
                colorFilter = ColorFilter.tint(VuGold.Text),
                modifier = Modifier.size(s.d(13))
            )
            Spacer(Modifier.width(s.d(5)))
            Text(
                stringResource(R.string.vu_list_of_user),
                color = VuGold.Text,
                fontSize = s.t(9),
                maxLines = 1
            )
        }
    }
}

/** Hint key → string resource (keeps VuLoginFlow pure). */
private fun hintRes(key: String): Int = when (key) {
    VuLoginFlow.Hint.MAC_ADDRESS -> R.string.vu_mac_address
    VuLoginFlow.Hint.M3U_URL -> R.string.vu_m3u_url
    VuLoginFlow.Hint.M3U_FILE_PATH -> R.string.vu_m3u_file_path
    VuLoginFlow.Hint.PORTAL_URL -> R.string.vu_portal_url
    VuLoginFlow.Hint.CODE -> R.string.vu_code_hint
    else -> R.string.vu_username
}

/** The old LoginScreen's engine-error mapping, moved verbatim. */
@Composable
internal fun vuLoginErrorText(error: LoginError, detail: String? = null): String = when (error) {
    LoginError.ALL_FIELDS -> stringResource(R.string.error_all_fields)
    LoginError.INVALID_URL -> stringResource(R.string.error_invalid_url)
    LoginError.AUTH -> stringResource(R.string.error_auth_failed)
    LoginError.TIMEOUT -> stringResource(R.string.error_timeout)
    LoginError.HOST -> stringResource(R.string.error_host)
    LoginError.SSL -> stringResource(R.string.error_ssl)
    LoginError.M3U_DOWNLOAD -> stringResource(R.string.error_m3u_download)
    LoginError.M3U_EMPTY -> stringResource(R.string.error_m3u_empty)
    LoginError.NOT_PANEL -> stringResource(R.string.error_not_panel)
    LoginError.SERVER -> stringResource(R.string.error_server, detail ?: "")
    LoginError.NETWORK -> stringResource(R.string.error_network, detail ?: "")
    // v1.13.0 — code-login errors (generation service responses).
    LoginError.CODE_FORMAT -> stringResource(R.string.error_code_format)
    LoginError.CODE_LIMIT -> {
        val seconds = detail?.toIntOrNull()
        if (seconds != null && seconds > 0) stringResource(R.string.error_code_limit, seconds)
        else stringResource(R.string.error_code_limit_generic)
    }
    LoginError.CODE_ACCOUNTS -> stringResource(R.string.error_code_accounts)
    LoginError.CODE_NO_ACCOUNTS -> stringResource(R.string.error_code_no_accounts)
    // v2.1.0 — the free 3-account ceiling (repository backstop).
    LoginError.ACCOUNT_LIMIT -> stringResource(
        R.string.error_account_limit,
        com.superz.iptvplayer.ui.theme.PremiumPolicy.FREE_ACCOUNT_LIMIT
    )
}
