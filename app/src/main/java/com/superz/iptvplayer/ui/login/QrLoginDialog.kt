package com.superz.iptvplayer.ui.login

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superz.iptvplayer.R
import com.superz.iptvplayer.data.code.QrBridgeClient
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * VuQrLoginFeature — v1.14.0 — the QR login bridge ("SMART CONNECT"),
 * ENGINE copied 100% from the reference app, UI in OUR design language
 * (the reference dialog's glassmorphic look is deliberately NOT copied).
 *
 * Flow (the reference's LoginActivity flow, verbatim):
 *  1. A random 6-digit TV code is generated; the QR encodes
 *     VERCEL_QR_BASE?code={code} (the user's own landing page).
 *  2. The phone scans it, fills the credentials on the page, and the page
 *     POSTs a Base64-JSON payload to /api/qr — our MongoDB bridge (the
 *     dead Supabase was replaced in v1.14.1; same payload format).
 *  3. This screen polls every 2s; when a payload arrives it decodes it and
 *     AUTO-TRIGGERS the matching login engine (xtream / m3u) — the same
 *     engines a manual login uses — then DELETEs the bridge record
 *     (zero-retention; the poll itself is read-once, so the sweep is a
 *     belt-and-braces no-op).
 *  4. Errors surface as toasts; success hands off to the accounts page
 *     exactly like a manual login.
 *
 * Design: our VuPinGateDialog idiom — scrim #A6000000, panel #2B2B37,
 * 10dp radius, sdp-scaled sizes, White/Cyan/#9F94A4 text, PanelOverlay
 * pills, D-pad-focusable CLOSE button.
 */
@Composable
fun VuQrLoginFeature(
    loginViewModel: LoginViewModel,
    onFinished: () -> Unit,
    onDismiss: () -> Unit
) {
    val s = rememberVuSdp()
    val context = LocalContext.current
    val ui by loginViewModel.ui.collectAsStateWithLifecycle()

    var tvCode by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isQrListening by remember { mutableStateOf(true) }
    var loggingIn by remember { mutableStateOf(false) }
    val closeFocus = remember { FocusRequester() }

    // ── Open: generate the TV code + QR bitmap (reference: click handler) ──
    LaunchedEffect(Unit) {
        val code = String.format("%06d", (0..999999).random())
        tvCode = code
        // The reference generates the bitmap on the caller thread; we keep
        // the engine call identical but move it off the UI thread (the
        // 512×512 setPixel loop would jank the dialog's entrance otherwise).
        val bmp = withContext(Dispatchers.Default) {
            QrBridgeClient.generateQrBitmap(QrBridgeClient.qrUrlFor(code))
        }
        qrBitmap = bmp?.asImageBitmap()
        runCatching { closeFocus.requestFocus() }
    }

    // ── The polling loop (reference: LaunchedEffect(tvCode), verbatim) ─────
    LaunchedEffect(tvCode) {
        while (isQrListening && tvCode.isNotEmpty()) {
            delay(2000)
            val rawPayload = QrBridgeClient.pollBridgeForPayload(tvCode)
            if (rawPayload != null) {
                isQrListening = false
                // Decode Base64 JSON payload
                val payload = QrBridgeClient.QrPayload.fromBase64(rawPayload)
                if (payload != null) {
                    loggingIn = true
                    // Auto-trigger login after a short delay for the dialog
                    // to settle (the reference's 300ms pre-login delay).
                    delay(300)
                    when {
                        payload.isXtream -> loginViewModel.loginXtream(
                            payload.profile, payload.host, payload.user, payload.pass
                        )
                        payload.isM3u -> loginViewModel.loginM3U(
                            payload.profile, payload.m3u
                        )
                        else -> {
                            loggingIn = false
                            Toast.makeText(
                                context,
                                context.getString(R.string.vu_qr_invalid_payload_short),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                // Zero-Retention: Delete the record immediately
                QrBridgeClient.deleteBridgeRecord(tvCode)
                break
            }
        }
    }

    // ── Engine success → the accounts hand-off (same contract as the form) ─
    LaunchedEffect(ui.success) {
        if (ui.success) {
            loggingIn = false
            onFinished()
        }
    }

    // ── Engine errors → toast + back to the grid (the app's portal-error
    //    idiom: LaunchedEffect(error) → Toast → consume). ──────────────────
    LaunchedEffect(ui.error) {
        ui.error?.let { err ->
            loggingIn = false
            Toast.makeText(
                context, qrErrorText(context, err, ui.errorDetail), Toast.LENGTH_LONG
            ).show()
            loginViewModel.consumeError()
            onDismiss()
        }
    }

    fun close() {
        isQrListening = false
        onDismiss()
    }

    // Back (phone or TV remote) closes the dialog, not the whole screen.
    BackHandler { close() }

    // ── The dialog (our design idiom; v1.19.13 — the scrim CONSUMES taps:
    //    before, a tap outside the panel fell through to the login form
    //    behind it, exactly like the PIN gate's old pass-through bug) ──────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xA6000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { close() }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = s.d(350), height = s.d(420))
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF2B2B37))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* consume — modal body */ }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize()
            ) {
                // txt_header — the pin dialog's header idiom (15sdp white).
                Text(
                    stringResource(R.string.vu_qr_title),
                    color = VuPalette.White,
                    fontSize = s.t(15),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = s.d(15))
                )
                // The scan hint (9sdp grey under the header).
                Text(
                    stringResource(R.string.vu_qr_scan_hint),
                    color = VuPalette.TintGrey,
                    fontSize = s.t(9),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = s.d(6), start = s.d(20), end = s.d(20))
                )

                // The QR code — white plate so cameras can read it.
                Spacer(Modifier.height(s.d(15)))
                Box(
                    modifier = Modifier
                        .size(s.d(190))
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .padding(s.d(12)),
                    contentAlignment = Alignment.Center
                ) {
                    val qr = qrBitmap
                    if (qr != null) {
                        Image(
                            bitmap = qr,
                            contentDescription = "QR Code",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        CircularProgressIndicator(
                            color = VuPalette.Cyan,
                            strokeWidth = s.d(2),
                            modifier = Modifier.size(s.d(28))
                        )
                    }
                }

                // The 6-digit verification code (24sdp white, tracked).
                Spacer(Modifier.height(s.d(12)))
                Text(
                    tvCode,
                    color = VuPalette.White,
                    fontSize = s.t(24),
                    fontWeight = FontWeight.Black,
                    letterSpacing = s.t(6),
                    maxLines = 1
                )
                Text(
                    stringResource(R.string.vu_qr_verify),
                    color = VuPalette.TintGrey,
                    fontSize = s.t(8),
                    modifier = Modifier.padding(top = s.d(3))
                )

                // Listening indicator — our pulsing cyan bar.
                Spacer(Modifier.height(s.d(12)))
                if (isQrListening) {
                    val pulse = rememberInfiniteTransition(label = "qrPulse")
                    val pulseAlpha by pulse.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween<Float>(durationMillis = 800),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "qrPulseAlpha"
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = s.d(180), height = s.d(3))
                                .clip(RoundedCornerShape(s.d(2)))
                                .background(VuPalette.Cyan.copy(alpha = pulseAlpha))
                        )
                        Text(
                            stringResource(R.string.vu_qr_listening),
                            color = VuPalette.TintGrey.copy(
                                alpha = 0.5f + (pulseAlpha * 0.5f)
                            ),
                            fontSize = s.t(9),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = s.d(8))
                        )
                    }
                } else if (loggingIn || ui.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = VuPalette.Cyan,
                            strokeWidth = s.d(2),
                            modifier = Modifier.size(s.d(14))
                        )
                        Text(
                            stringResource(R.string.vu_qr_connecting),
                            color = VuPalette.White,
                            fontSize = s.t(9),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = s.d(8))
                        )
                    }
                }

                // CLOSE — our PanelOverlay pill (D-pad focusable, TV-ready).
                Spacer(Modifier.weight(1f))
                var closeFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .padding(bottom = s.d(18))
                        .size(width = s.d(150), height = s.d(30))
                        .clip(RoundedCornerShape(s.d(15)))
                        .background(VuPalette.PanelOverlay)
                        .then(
                            if (closeFocused) Modifier.background(VuPalette.PurpleBrush)
                            else Modifier
                        )
                        .focusRequester(closeFocus)
                        .onFocusChanged { closeFocused = it.isFocused }
                        .focusable()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { close() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.vu_qr_close),
                        color = if (closeFocused) VuPalette.White else VuPalette.TintGrey,
                        fontSize = s.t(10),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * The QR flow's engine-error mapping — the same resources the form's
 * vuLoginErrorText uses, resolved through Context so it can be called from
 * inside LaunchedEffect (stringResource is composable-only).
 */
private fun qrErrorText(
    context: android.content.Context,
    error: LoginError,
    detail: String?
): String = when (error) {
    LoginError.ALL_FIELDS -> context.getString(R.string.error_all_fields)
    LoginError.INVALID_URL -> context.getString(R.string.error_invalid_url)
    LoginError.AUTH -> context.getString(R.string.error_auth_failed)
    LoginError.TIMEOUT -> context.getString(R.string.error_timeout)
    LoginError.HOST -> context.getString(R.string.error_host)
    LoginError.SSL -> context.getString(R.string.error_ssl)
    LoginError.M3U_DOWNLOAD -> context.getString(R.string.error_m3u_download)
    LoginError.M3U_EMPTY -> context.getString(R.string.error_m3u_empty)
    LoginError.NOT_PANEL -> context.getString(R.string.error_not_panel)
    LoginError.SERVER -> context.getString(R.string.error_server, detail ?: "")
    LoginError.NETWORK -> context.getString(R.string.error_network, detail ?: "")
    LoginError.CODE_FORMAT -> context.getString(R.string.error_code_format)
    LoginError.CODE_LIMIT -> {
        val seconds = detail?.toIntOrNull()
        if (seconds != null && seconds > 0) context.getString(R.string.error_code_limit, seconds)
        else context.getString(R.string.error_code_limit_generic)
    }
    LoginError.CODE_ACCOUNTS -> context.getString(R.string.error_code_accounts)
    LoginError.CODE_NO_ACCOUNTS -> context.getString(R.string.error_code_no_accounts)
    // v2.1.0 — the free 3-account ceiling (repository backstop).
    LoginError.ACCOUNT_LIMIT -> context.getString(
        R.string.error_account_limit,
        com.superz.iptvplayer.ui.theme.PremiumPolicy.FREE_ACCOUNT_LIMIT
    )
}
