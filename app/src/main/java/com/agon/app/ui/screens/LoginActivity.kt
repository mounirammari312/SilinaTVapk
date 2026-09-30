package com.agon.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.agon.app.R
import com.agon.app.data.ProfileRepository
import com.agon.app.ui.theme.AccentCyan
import com.agon.app.ui.theme.AccentIndigo
import com.agon.app.ui.theme.AccentIndigoLight
import com.agon.app.ui.theme.GlassSurface
import com.agon.app.ui.theme.SlateBorder
import com.agon.app.ui.theme.SlateTextMuted
import com.agon.app.ui.theme.glassmorphicPill
import com.agon.app.ui.theme.glassmorphicCard
import com.agon.app.ui.theme.PremiumSpring
import com.agon.app.ui.theme.SmoothSpring
import com.agon.app.config.AppConfig
import com.agon.app.data.ServerProfile
import com.agon.app.data.repository.PlaylistRepository
import com.agon.app.ui.util.applyImmersiveFullscreen
import com.agon.app.ui.util.AppBackgroundImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full-screen immersive mode — hide status & navigation bars
        applyImmersiveFullscreen()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SilinaLoginScreen()
            }
        }
    }
}

fun parseM3uToXtream(m3uUrl: String): Triple<String, String, String>? {
    return try {
        val url = m3uUrl.trim().trimEnd('/')
        val uri = URI(url)
        val host = "${uri.scheme}://${uri.host}" + (if (uri.port != -1) ":${uri.port}" else "")
        val query = uri.query ?: uri.rawQuery ?: ""
        var user = ""
        var pass = ""
        for (param in query.split("&")) {
            val kv = param.split("=", limit = 2)
            if (kv.size == 2) {
                when (kv[0].lowercase()) {
                    "username" -> user = kv[1]
                    "password" -> pass = kv[1]
                }
            }
        }
        if (user.isNotBlank() && pass.isNotBlank()) {
            Triple(host, user, pass)
        } else null
    } catch (e: Exception) {
        null
    }
}

// ═══════════════════════════════════════════════════════════════
// SMART CONNECT — Supabase Bridge Helpers
// ═══════════════════════════════════════════════════════════════
// V8.0 §IV: The SUPABASE_URL, SUPABASE_ANON_KEY, and VERCEL_QR_BASE
// constants were EXTRACTED from this file and isolated in
// [com.agon.app.config.AppConfig] so each buyer can plug in their own
// Supabase project. See AppConfig for the TODO replacement guide.
// ═══════════════════════════════════════════════════════════════
private const val VERCEL_QR_BASE = com.agon.app.config.AppConfig.VERCEL_QR_BASE

private fun generateQrBitmap(text: String, size: Int = 512): ImageBitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap.asImageBitmap()
    } catch (e: Exception) { null }
}

private suspend fun pollSupabaseForPayload(tvCode: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("${com.agon.app.config.AppConfig.SUPABASE_URL}/rest/v1/auth_bridge?tv_code=eq.$tvCode&select=payload&limit=1&order=created_at.desc")
                .addHeader("apikey", com.agon.app.config.AppConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer ${com.agon.app.config.AppConfig.SUPABASE_ANON_KEY}")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val array = JSONArray(body)
            if (array.length() > 0) {
                array.getJSONObject(0).optString("payload", "")
            } else null
        } catch (e: Exception) { null }
    }
}

private suspend fun deleteSupabaseRecord(tvCode: String) {
    withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("${com.agon.app.config.AppConfig.SUPABASE_URL}/rest/v1/auth_bridge?tv_code=eq.$tvCode")
                .addHeader("apikey", com.agon.app.config.AppConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer ${com.agon.app.config.AppConfig.SUPABASE_ANON_KEY}")
                .delete()
                .build()
            client.newCall(request).execute()
        } catch (e: Exception) { }
    }
}

private fun decodeBase64Json(base64: String): JSONObject? {
    return try {
        val jsonStr = String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
        JSONObject(jsonStr)
    } catch (e: Exception) { null }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SilinaLoginScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { ProfileRepository.getInstance(context) }

    // BackHandler — pressing BACK on the login screen exits the app
    // (standard Android behavior for the root authentication screen).
    androidx.activity.compose.BackHandler(enabled = true) {
        (context as? ComponentActivity)?.finish()
    }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var isProfileReady by remember { mutableStateOf(false) }
    var showAdminPinDialog by remember { mutableStateOf(false) }
    var adminPin by remember { mutableStateOf("") }

    // ═══════════════════════════════════════════════════════════════
    //  V8.0 §III — HOST MASKING SYSTEM (قناع النطاق الموجه)
    //  ═══════════════════════════════════════════════════════════════
    //  When AppConfig.FORCED_HOST_URL is non-empty, the server URL + profile
    //  name are AUTO-FILLED in the background from AppConfig, and the
    //  corresponding login fields are HIDDEN from the user. The user only
    //  enters Username + Password.
    //
    //  When empty (default — clean player), all fields are visible and the
    //  variables start blank. This is the Google Play safe mode.
    // ═══════════════════════════════════════════════════════════════
    val hostMaskingActive = AppConfig.FORCED_HOST_URL.isNotEmpty()
    var profileName by remember { mutableStateOf(AppConfig.FORCED_PROFILE_NAME) }
    var loginMode by remember { mutableStateOf("xtream") }
    var serverUrl by remember { mutableStateOf(AppConfig.FORCED_HOST_URL) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var m3uUrl by remember { mutableStateOf("") }
    var showQrDialog by remember { mutableStateOf(false) }
    var tvCode by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isQrListening by remember { mutableStateOf(false) }

    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    fun navigateToProfiles() {
        context.startActivity(Intent(context, ProfilesActivity::class.java))
        (context as? ComponentActivity)?.finish()
    }

    LaunchedEffect(isProfileReady) {
        if (isProfileReady) navigateToProfiles()
    }

    LaunchedEffect(errorMessage) {
        if (errorMessage.isNotEmpty()) {
            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
            errorMessage = ""
        }
    }

    fun saveProfileAndContinue(
        name: String,
        srvUrl: String,
        user: String,
        pass: String,
        m3u: String = ""
    ) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val profile = ServerProfile(
                    id = UUID.randomUUID().toString(),
                    name = name.ifBlank { "Xtream: $user" },
                    m3uUrl = m3u.ifBlank {
                        if (srvUrl.isNotBlank() && user.isNotBlank())
                            "$srvUrl/get.php?username=$user&password=$pass&type=m3u_plus"
                        else ""
                    },
                    serverUrl = srvUrl,
                    username = user,
                    password = pass,
                    isActive = true,
                    isAutoGenerated = false
                )
                repo.addManualProfile(profile)
                PlaylistRepository.saveToCache(context)
            }
            isProfileReady = true
        }
    }

    fun triggerConnection() {
        scope.launch {
            isLoading = true
            withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder()
                        // V8.2: URL comes from AppConfig.SMART_CONNECT_GENERATE_URL.
                        .url("${AppConfig.SMART_CONNECT_GENERATE_URL}?count=5&fingerprint=$androidId")
                        .build()
                    val response = client.newCall(request).execute()
                    val resBody = response.body?.string() ?: ""
                    val json = JSONObject(resBody)

                    if (json.optString("status") == "limit_reached") {
                        withContext(Dispatchers.Main) {
                            errorMessage = "Cooldown Active. Try again later."
                        }
                    } else {
                        val lines = json.optJSONArray("lines")
                        if (lines != null) {
                            val validProfiles = coroutineScope {
                                (0 until lines.length()).map { i ->
                                    async {
                                        val line = lines.getJSONObject(i)
                                        val host = line.optString("host")
                                        val user = line.optString("user")
                                        val pass = line.optString("pass")
                                        try {
                                            val infoReq = Request.Builder()
                                                // V8.2: URL comes from AppConfig.SMART_CONNECT_SERVER_INFO_URL.
                                                .url("${AppConfig.SMART_CONNECT_SERVER_INFO_URL}?host=$host&user=$user&pass=$pass")
                                                .build()
                                            val infoRes = client.newCall(infoReq).execute()
                                            val infoJson = JSONObject(infoRes.body?.string() ?: "")
                                            if (infoJson.optString("status") == "ok") {
                                                val accStatus = infoJson.optJSONObject("user_info")
                                                    ?.optString("status")?.lowercase() ?: ""
                                                if (!accStatus.contains("expir") && !accStatus.contains("disabled")) {
                                                    val m3u = "$host/get.php?username=$user&password=$pass&type=m3u_plus"
                                                    return@async ServerProfile(
                                                        id = UUID.randomUUID().toString(),
                                                        name = "Silina Premium ${i + 1}",
                                                        m3uUrl = m3u,
                                                        serverUrl = host,
                                                        username = user,
                                                        password = pass,
                                                        isActive = false,
                                                        isAutoGenerated = true
                                                    )
                                                }
                                            }
                                        } catch (e: Exception) { /* Ignore */ }
                                        return@async null
                                    }
                                }.awaitAll().filterNotNull().take(3)
                            }

                            if (validProfiles.isNotEmpty()) {
                                repo.saveAutoProfiles(validProfiles)
                                withContext(Dispatchers.Main) {
                                    isProfileReady = true
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    errorMessage = "No valid servers found."
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "Connection Error: ${e.localizedMessage}"
                    }
                }
            }
            isLoading = false
        }
    }

    fun triggerManualLogin() {
        if (profileName.isBlank() || serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
            errorMessage = "All fields are required."
            return
        }
        scope.launch {
            isLoading = true
            val result = withContext(Dispatchers.IO) {
                try {
                    var testBase = serverUrl.trim().trimEnd('/')
                    if (testBase.lowercase().endsWith("/player_api.php")) {
                        testBase = testBase.substring(0, testBase.length - "/player_api.php".length)
                    }
                    if (!testBase.startsWith("http://", ignoreCase = true) && !testBase.startsWith("https://", ignoreCase = true)) {
                        testBase = "http://$testBase"
                    }
                    val testUrl = "$testBase/player_api.php?username=$username&password=$password"
                    val testClient = OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build()
                    val testReq = Request.Builder().url(testUrl).build()
                    val testRes = testClient.newCall(testReq).execute()
                    val testBody = testRes.body?.string() ?: ""

                    if (!testRes.isSuccessful) return@withContext "Server returned HTTP ${testRes.code}"
                    if (testBody.isEmpty()) return@withContext "Empty response from server"

                    val testJson = JSONObject(testBody)
                    val userInfo = testJson.optJSONObject("user_info")
                    if (userInfo == null) return@withContext "Invalid server response"

                    val authVal = userInfo.optInt("auth", 0)
                    if (authVal != 1) {
                        val status = userInfo.optString("status", "unknown")
                        val msg = userInfo.optString("message", "")
                        return@withContext "Auth denied (status: $status${if (msg.isNotEmpty()) " - $msg" else ""})"
                    }
                    "OK"
                } catch (e: java.net.SocketTimeoutException) {
                    "Connection timed out."
                } catch (e: java.net.UnknownHostException) {
                    "Server not found."
                } catch (e: javax.net.ssl.SSLException) {
                    "SSL error. Try http://"
                } catch (e: Exception) {
                    "Error: ${e.localizedMessage}"
                }
            }
            if (result == "OK") {
                saveProfileAndContinue(profileName, serverUrl, username, password)
            } else {
                errorMessage = result
            }
            isLoading = false
        }
    }

    fun triggerM3uLogin() {
        if (profileName.isBlank() || m3uUrl.isBlank()) {
            errorMessage = "Name and M3U URL are required."
            return
        }
        scope.launch {
            isLoading = true
            val result = withContext(Dispatchers.IO) {
                try {
                    val xtream = parseM3uToXtream(m3uUrl)
                    if (xtream != null) {
                        val (host, user, pass) = xtream
                        val testUrl = "$host/player_api.php?username=$user&password=$pass"
                        val client = OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                            .build()
                        val req = Request.Builder().url(testUrl).build()
                        val res = client.newCall(req).execute()
                        val body = res.body?.string() ?: ""

                        if (res.isSuccessful && body.isNotEmpty()) {
                            val json = JSONObject(body)
                            val userInfo = json.optJSONObject("user_info")
                            if (userInfo != null && userInfo.optInt("auth", 0) == 1) {
                                withContext(Dispatchers.Main) {
                                    saveProfileAndContinue(profileName, host, user, pass, m3uUrl)
                                }
                                return@withContext "OK"
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        saveProfileAndContinue(profileName, "", "", "", m3uUrl)
                    }
                    "OK"
                } catch (e: java.net.SocketTimeoutException) {
                    "Connection timed out."
                } catch (e: java.net.UnknownHostException) {
                    "Server not found."
                } catch (e: Exception) {
                    "Error: ${e.localizedMessage}"
                }
            }
            if (result != "OK") {
                errorMessage = result
            }
            isLoading = false
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AppBackgroundImage(scrimAlpha = 0.5f)
            Row(modifier = Modifier.fillMaxSize()) {

                // ── LEFT PANE (40%) ──
                Column(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    // REMOVED: "< Back" text button — no meaningful destination
                    // from the login screen (it's the root of the back stack).
                    // The hardware BACK button + the BackHandler above already
                    // finish() the activity. Keeping a visual "< Back" here was
                    // confusing — it implied the user could navigate somewhere
                    // else, when in fact it just exited the app.

                    Spacer(Modifier.weight(1f))

                    // APP LOGO + List of User button grouped tightly together
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // APP LOGO (Admin Backdoor) — no border
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onLongClick = { showAdminPinDialog = true },
                                    onClick = { /* Do nothing to keep it hidden */ }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.mipmap.ic_launcher),
                                contentDescription = "App Logo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp),
                                contentScale = ContentScale.Fit
                            )
                        }

                        // Deep Space 2026 "List of User" BUTTON — accent gradient + spring + text shadow
                        var listUserBtnFocused by remember { mutableStateOf(false) }
                        val listUserBtnScale by animateFloatAsState(
                            if (listUserBtnFocused) 1.05f else 1f,
                            PremiumSpring, label = "listUserBtnScale"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.62f)
                                .height(40.dp)
                                .scale(listUserBtnScale)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(AccentIndigo, AccentCyan)
                                    )
                                )
                                .border(
                                    width = if (listUserBtnFocused) 2.dp else 1.dp,
                                    color = if (listUserBtnFocused) Color.White else Color.White.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(50)
                                )
                                .focusable()
                                .onFocusChanged { listUserBtnFocused = it.isFocused }
                                .clickable { navigateToProfiles() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "List of User",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                style = androidx.compose.ui.text.TextStyle(
                                    shadow = androidx.compose.ui.graphics.Shadow(
                                        color = Color.Black.copy(alpha = 0.4f),
                                        blurRadius = 3f
                                    )
                                )
                            )
                        }

                        // ═══════════════════════════════════════════════════════
                        //  CONNECT TO HUB BUTTON
                        //  ═══════════════════════════════════════════════════════
                        //  Small professional button below "List of User".
                        //  Searches for a Hub on the LAN and connects
                        //  automatically — no IP entry, no complexity.
                        // ═══════════════════════════════════════════════════════
                        var smartConnectBtnFocused by remember { mutableStateOf(false) }
                        val smartConnectBtnScale by animateFloatAsState(
                            if (smartConnectBtnFocused) 1.05f else 1f,
                            PremiumSpring, label = "smartBtnScale"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.62f)
                                .height(40.dp)
                                .scale(smartConnectBtnScale)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(AccentIndigo, AccentCyan)
                                    )
                                )
                                .border(
                                    width = if (smartConnectBtnFocused) 2.dp else 1.dp,
                                    color = if (smartConnectBtnFocused) Color.White else Color.White.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(50)
                                )
                                .focusable()
                                .onFocusChanged { smartConnectBtnFocused = it.isFocused }
                                .clickable {
                                    loginMode = "smart"
                                    val code = String.format("%06d", (0..999999).random())
                                    tvCode = code
                                    val qrUrl = "${VERCEL_QR_BASE}?code=$code"
                                    qrBitmap = generateQrBitmap(qrUrl)
                                    showQrDialog = true
                                    isQrListening = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = "Smart Connect",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "Smart Connect",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
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

                    Spacer(Modifier.weight(1f))
                }

                // ── RIGHT PANE (60%) ──
                Column(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                        .padding(horizontal = 32.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    Text(
                        "Enter Your Login Details",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))

                    // Compact pill tabs — smaller width so the row reads cleanly.
                    // V8.0 §III: When host masking is active (FORCED_HOST_URL set),
                    // the M3U + Smart Connect tabs are HIDDEN — the user must log
                    // in via the locked Xtream portal only.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeTab("Xtream API", loginMode == "xtream") { loginMode = "xtream" }
                        if (!hostMaskingActive) {
                            ModeTab("M3U Playlist", loginMode == "m3u") { loginMode = "m3u" }
                            
                        }
                    }
                    Spacer(Modifier.height(10.dp))

                    // Single Column with uniform spacing — same gap between every row
                    // (input-to-input AND input-to-button) so the layout looks consistent.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // ═══════════════════════════════════════════════════════
                        //  V8.0 §III — HOST MASKING: hide profile name field
                        //  when FORCED_PROFILE_NAME is set in AppConfig.
                        //  ═══════════════════════════════════════════════════════
                        if (AppConfig.FORCED_PROFILE_NAME.isEmpty()) {
                            LoginField(profileName, { profileName = it }, "Name (e.g., Living Room)")
                        }

                        if (loginMode == "xtream") {
                            // ═══════════════════════════════════════════════════════
                            //  V8.0 §III — HOST MASKING: hide Portal URL field
                            //  when FORCED_HOST_URL is set in AppConfig. The
                            //  serverUrl variable is pre-filled from AppConfig
                            //  so the login flow uses the locked server.
                            //  ═══════════════════════════════════════════════════════
                            if (AppConfig.FORCED_HOST_URL.isEmpty()) {
                                LoginField(serverUrl, { serverUrl = it }, "Portal URL")
                            }
                            LoginField(username, { username = it }, "Username")
                            LoginField(password, { password = it }, "Password", isPassword = true)
                        } else if (loginMode == "m3u") {
                            LoginField(m3uUrl, { m3uUrl = it }, "M3U URL")
                        }

                        // Deep Space 2026 "ADD USER" BUTTON — accent gradient + spring + glass border
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        val isFocusedBtn by interactionSource.collectIsFocusedAsState()
                        val scale by animateFloatAsState(
                            targetValue = when {
                                isPressed -> 0.97f
                                isFocusedBtn -> 1.04f
                                else -> 1f
                            },
                            animationSpec = PremiumSpring,
                            label = "addUserBtnScale"
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .scale(scale)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(AccentIndigo, AccentCyan)
                                    )
                                )
                                .border(
                                    width = if (isFocusedBtn) 2.dp else 1.dp,
                                    color = if (isFocusedBtn) Color.White else Color.White.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(50)
                                )
                                .clickable(interactionSource = interactionSource, indication = null) {
                                    if (loginMode == "m3u") triggerM3uLogin() else triggerManualLogin()
                                }
                                .focusable(interactionSource = interactionSource),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "ADD USER",
                                color = Color.White,
                                fontSize = 12.sp,
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

                    Spacer(Modifier.height(6.dp))

                    Text(
                        "Note - Do not connect any content that infringes copyrights on the application. We do not sell any playlist or subscriptions. This IPTV Player is a General Media Player that does not include any content.",
                        color = SlateTextMuted,
                        fontSize = 8.sp,
                        lineHeight = 11.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── LOADING OVERLAY ── Deep Space glassmorphic
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentIndigo)
                        Spacer(Modifier.height(16.dp))
                        Text("Authenticating...", color = Color.White, fontSize = 14.sp)
                    }
                }
            }

            // ── ADMIN PIN DIALOG ── Deep Space glassmorphic
            if (showAdminPinDialog) {
                Dialog(onDismissRequest = { showAdminPinDialog = false; adminPin = "" }) {
                    Box(
                        modifier = Modifier
                            .glassmorphicCard(cornerRadius = 20, focused = false)
                            .padding(32.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "SYSTEM CONFIGURATION",
                                color = AccentCyan,
                                fontWeight = FontWeight.Bold,
                                style = androidx.compose.ui.text.TextStyle(
                                    shadow = androidx.compose.ui.graphics.Shadow(
                                        color = Color.Black.copy(alpha = 0.5f),
                                        blurRadius = 4f
                                    )
                                )
                            )
                            Spacer(Modifier.height(24.dp))
                            LoginField(adminPin, { adminPin = it }, "Enter Admin PIN", isPassword = true)
                            Spacer(Modifier.height(24.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(AccentIndigo, AccentCyan)
                                        )
                                    )
                                    .clickable {
                                        if (adminPin == "1111111$") {
                                            showAdminPinDialog = false
                                            adminPin = ""
                                            triggerConnection()
                                        } else {
                                            Toast.makeText(context, "Access Denied", Toast.LENGTH_SHORT).show()
                                            showAdminPinDialog = false
                                            adminPin = ""
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "AUTHORIZE",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
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
            }

            // ── SMART CONNECT QR DIALOG ── Glassmorphic + Realtime Bridge
            if (showQrDialog) {
                // Start polling for Supabase payload when dialog opens
                LaunchedEffect(tvCode) {
                    while (isQrListening) {
                        kotlinx.coroutines.delay(2000)
                        val rawPayload = pollSupabaseForPayload(tvCode)
                        if (rawPayload != null) {
                            isQrListening = false
                            showQrDialog = false
                            // Decode Base64 JSON payload
                            val json = decodeBase64Json(rawPayload)
                            if (json != null) {
                                val mode = json.optString("mode", "")
                                if (mode == "xtream") {
                                    profileName = json.optString("profile", "")
                                    serverUrl = json.optString("host", "")
                                    username = json.optString("user", "")
                                    password = json.optString("pass", "")
                                    loginMode = "xtream"
                                    // Auto-trigger login after a short delay for fields to update
                                    kotlinx.coroutines.delay(300)
                                    triggerManualLogin()
                                } else if (mode == "m3u") {
                                    profileName = json.optString("profile", "")
                                    m3uUrl = json.optString("m3u", "")
                                    loginMode = "m3u"
                                    kotlinx.coroutines.delay(300)
                                    triggerM3uLogin()
                                }
                            }
                            // Zero-Retention: Delete the record immediately
                            deleteSupabaseRecord(tvCode)
                            break
                        }
                    }
                }

                Dialog(
                    onDismissRequest = {
                        showQrDialog = false
                        isQrListening = false
                        loginMode = "xtream"
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF0A0E1A).copy(alpha = 0.95f))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                            .padding(32.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "SMART CONNECT",
                                color = AccentCyan,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Scan with your phone to sync credentials",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 10.sp
                            )
                            Spacer(Modifier.height(20.dp))

                            // QR Code Image
                            val qr = qrBitmap
                            if (qr != null) {
                                Box(
                                    modifier = Modifier
                                        .size(220.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White)
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = qr,
                                        contentDescription = "QR Code",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(220.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = AccentCyan,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            // 6-digit verification code
                            Text(
                                tvCode,
                                color = Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 6.sp,
                                style = androidx.compose.ui.text.TextStyle(
                                    shadow = androidx.compose.ui.graphics.Shadow(
                                        color = AccentCyan.copy(alpha = 0.4f),
                                        blurRadius = 8f
                                    )
                                )
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Verification Code",
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 9.sp
                            )

                            Spacer(Modifier.height(16.dp))

                            // Listening indicator — elegant pulsing bar
                            if (isQrListening) {
                                val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
                                val pulseAlpha by infiniteTransition.animateFloat(
                                    initialValue = 0.2f,
                                    targetValue = 1f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween<Float>(durationMillis = 800),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "pulseAlpha"
                                )
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    // Pulsing status bar
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.7f)
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color.White.copy(alpha = 0.1f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(
                                                    Brush.horizontalGradient(
                                                        listOf(
                                                            AccentCyan.copy(alpha = pulseAlpha * 0.4f),
                                                            AccentCyan.copy(alpha = pulseAlpha),
                                                            AccentIndigo.copy(alpha = pulseAlpha),
                                                            AccentIndigo.copy(alpha = pulseAlpha * 0.4f)
                                                        )
                                                    )
                                                )
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Waiting for connection...",
                                        color = Color.White.copy(alpha = 0.3f + (pulseAlpha * 0.4f)),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            // Close button — focusable + visible focus indicator
                            // (required for D-Pad/remote users on Android TV).
                            var closeFocused by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.6f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (closeFocused) Color.White.copy(alpha = 0.25f)
                                        else Color.White.copy(alpha = 0.1f)
                                    )
                                    .border(
                                        1.5.dp,
                                        if (closeFocused) AccentCyan else Color.White.copy(alpha = 0.2f),
                                        RoundedCornerShape(50)
                                    )
                                    .focusable()
                                    .onFocusChanged { closeFocused = it.isFocused }
                                    .clickable {
                                        showQrDialog = false
                                        isQrListening = false
                                        loginMode = "xtream"
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "CLOSE",
                                    color = if (closeFocused) Color.White else Color.White.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════
            //  V8.3 — BANNER AD REMOVED (Login Screen)
            //  ═══════════════════════════════════════════════════════════════
            //  The Anchored Adaptive Banner (BannerAdView) was physically
            //  removed from the project in V8.3. The login screen now ends
            //  cleanly below the authentication form — no ad slot.
            // ═══════════════════════════════════════════════════════════════
        }
    }
}

@Composable
fun ModeTab(text: String, selected: Boolean, onClick: () -> Unit) {
    // Deep Space 2026 pill tab — glassmorphic + spring scale + accent glow.
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1f,
        animationSpec = SmoothSpring,
        label = "modeTabScale"
    )
    Box(
        modifier = Modifier
            .scale(scale)
            .glassmorphicPill(focused = selected || isFocused)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 6.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected || isFocused) AccentCyan else SlateTextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    isPassword: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CircleShape)
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = if (isFocused) AccentIndigoLight else Color.White.copy(alpha = 0.6f),
                shape = CircleShape
            )
            .background(GlassSurface.copy(alpha = if (isFocused) 0.75f else 0.50f), CircleShape)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                        when (event.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                focusManager.moveFocus(FocusDirection.Down); true
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                focusManager.moveFocus(FocusDirection.Up); true
                            }
                            else -> false
                        }
                    } else false
                }
        )
        if (value.isEmpty()) {
            Text(hint, color = SlateTextMuted.copy(alpha = 0.8f), fontSize = 11.sp)
        }
    }
}
