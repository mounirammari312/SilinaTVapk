package com.superz.iptvplayer.ui.login

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.R
import com.superz.iptvplayer.data.PlaylistRepository
import com.superz.iptvplayer.data.code.CodeServerClient
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.xtream.PortalUrl
import com.superz.iptvplayer.data.xtream.XtreamException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginMode { XTREAM, M3U }

enum class LoginError {
    ALL_FIELDS, INVALID_URL, AUTH, TIMEOUT, HOST, SSL,
    M3U_DOWNLOAD, M3U_EMPTY, SERVER, NETWORK, NOT_PANEL,
    CODE_FORMAT, CODE_LIMIT, CODE_ACCOUNTS, CODE_NO_ACCOUNTS,
    // v2.1.0 — the free 3-account ceiling (repository backstop).
    ACCOUNT_LIMIT
}

class LoginViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val mode: LoginMode = LoginMode.XTREAM,
        val loading: Boolean = false,
        val authStage: Boolean = true,      // true = authenticating, false = loading channels
        val channelProgress: Int = 0,
        val error: LoginError? = null,
        val errorDetail: String? = null,
        val success: Boolean = false
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val repo: PlaylistRepository = PlaylistRepository.get(getApplication<IPTVApp>())

    fun setMode(mode: LoginMode) = _ui.update { it.copy(mode = mode) }

    fun loginXtream(name: String, server: String, username: String, password: String) {
        // Reference-grade: a URL pasted with embedded credentials (get.php /
        // player_api.php?username=…&password=…) may leave the fields blank.
        val embedded = PortalUrl.parse(server)
            ?.let { !it.username.isNullOrBlank() && !it.password.isNullOrBlank() } == true
        if (server.isBlank() || (!embedded && (username.isBlank() || password.isBlank()))) {
            _ui.update { it.copy(error = LoginError.ALL_FIELDS) }
            return
        }
        if (PortalUrl.parse(server) == null) {
            _ui.update { it.copy(error = LoginError.INVALID_URL) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, authStage = true, channelProgress = 0) }
            try {
                val playlist = repo.loginXtream(name.trim(), server.trim(), username.trim(), password.trim())
                syncAndFinish(playlist)
            } catch (e: XtreamException) {
                _ui.update { it.copy(loading = false, error = mapXtreamError(e), errorDetail = e.message) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = LoginError.NETWORK, errorDetail = e.message) }
            }
        }
    }

    fun loginM3U(name: String, url: String) {
        if (url.isBlank()) {
            _ui.update { it.copy(error = LoginError.ALL_FIELDS) }
            return
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            _ui.update { it.copy(error = LoginError.INVALID_URL) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, authStage = false, channelProgress = 0) }
            try {
                val playlist = repo.loginM3U(name.trim(), url.trim())
                syncAndFinish(playlist)
            } catch (e: XtreamException) {
                _ui.update { it.copy(loading = false, error = mapXtreamError(e), errorDetail = e.message) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = LoginError.NETWORK, errorDetail = e.message) }
            }
        }
    }

    /**
     * Login succeeded (auth passed, playlist stored as ACTIVE). The channel
     * sync itself runs on the dedicated LOADING screen (reference flow:
     * LoginActivity authenticates, HubActivity's sync stage fetches channels)
     * — so we surface success immediately and navigate on.
     */
    private suspend fun syncAndFinish(playlist: Playlist) {
        _ui.update { it.copy(loading = false, success = true) }
    }

    /**
     * v1.13.0 — 6-digit code login (the agreed flow):
     *  1. POST {code} to the generation server → 3 verified Xtream accounts
     *     (the button spinner covers the Render cold start, up to ~60s).
     *  2. Each account is added exactly like a manual XC login
     *     (authenticate + store); a failing account is skipped.
     *  3. The FIRST successful account is left ACTIVE — the user lands on
     *     the accounts page with all generated accounts listed, and enters
     *     the loading/sync stage on tap, exactly like a manual login.
     *  4. All accounts failed → CODE_ACCOUNTS ("accounts expired, retry").
     */
    fun loginWithCode(rawCode: String) {
        val code = rawCode.filter { it.isDigit() }
        if (code.length != 6) {
            _ui.update { it.copy(error = LoginError.CODE_FORMAT) }
            return
        }
        val appCtx = getApplication<Application>()
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            // v2.1.0 — the free 3-account ceiling: a user already holding
            // the limit gets the upsell instead of three more accounts.
            if (com.superz.iptvplayer.ui.theme.PremiumAccess.accountLimitReached()) {
                _ui.update { it.copy(loading = false, error = LoginError.ACCOUNT_LIMIT) }
                return@launch
            }
            try {
                val accounts = CodeServerClient.generateAccounts(
                    code, (appCtx as IPTVApp).okHttp
                )
                var firstOk: Playlist? = null
                var added = 0
                val baseCount = com.superz.iptvplayer.ui.theme.PremiumAccess.accountCount
                for ((i, acc) in accounts.withIndex()) {
                    // v2.1.0 — stop at the ceiling MID-LOOP too: the
                    // generated accounts land one by one, each one counts.
                    if (com.superz.iptvplayer.ui.theme.PremiumPolicy.accountLimitReached(
                            baseCount + added,
                            com.superz.iptvplayer.ui.theme.PremiumAccess.active
                        )
                    ) break
                    try {
                        val name = appCtx.getString(R.string.account_name, i + 1)
                        val pl = repo.loginXtream(name, acc.host, acc.user, acc.pass)
                        if (firstOk == null) firstOk = pl
                        added++
                    } catch (e: Exception) {
                        // Single account expired/unreachable → skip, keep going.
                        Log.w(TAG, "code login: account ${i + 1} failed: ${e.message}")
                    }
                }
                val active = firstOk
                if (added == 0 || active == null) {
                    _ui.update { it.copy(loading = false, error = LoginError.CODE_ACCOUNTS) }
                } else {
                    // loginXtream activates each new playlist; force account #1 active.
                    runCatching { repo.activate(active.id) }
                    Log.i(TAG, "code login: $added account(s) added, active=${active.id}")
                    _ui.update { it.copy(loading = false, success = true) }
                }
            } catch (e: CodeServerClient.CodeServerException) {
                _ui.update {
                    it.copy(
                        loading = false,
                        error = mapCodeError(e),
                        errorDetail = e.retryAfter?.toString() ?: e.code
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = LoginError.NETWORK, errorDetail = e.message) }
            }
        }
    }

    private fun mapCodeError(e: CodeServerClient.CodeServerException): LoginError = when (e.code) {
        "INVALID_CODE" -> LoginError.CODE_FORMAT
        "RATE_LIMIT" -> LoginError.CODE_LIMIT
        "NO_ACCOUNTS" -> LoginError.CODE_NO_ACCOUNTS
        "TIMEOUT" -> LoginError.TIMEOUT
        "HOST_NOT_FOUND" -> LoginError.HOST
        "SSL_ERROR" -> LoginError.SSL
        else -> LoginError.SERVER
    }

    private fun mapXtreamError(e: XtreamException): LoginError {
        val code = e.message?.substringBefore('|') ?: e.message ?: ""
        return when {
            code.startsWith("AUTH_DENIED") -> LoginError.AUTH
            code == "ACCOUNT_LIMIT" -> LoginError.ACCOUNT_LIMIT
            code == "TIMEOUT" -> LoginError.TIMEOUT
            code == "HOST_NOT_FOUND" -> LoginError.HOST
            code == "SSL_ERROR" -> LoginError.SSL
            code == "M3U_EMPTY" -> LoginError.M3U_EMPTY
            code == "NOT_XTREAM_PANEL" -> LoginError.NOT_PANEL
            code == "INVALID_URL" -> LoginError.INVALID_URL
            code == "ALL_FIELDS" -> LoginError.ALL_FIELDS
            code.startsWith("M3U_URL") || code.startsWith("DOWNLOAD") || code.startsWith("GET_PHP") -> LoginError.M3U_DOWNLOAD
            code.startsWith("HTTP_") || code.startsWith("HTTP ") -> LoginError.SERVER
            code.startsWith("EMPTY_RESPONSE") || code.startsWith("INVALID_RESPONSE") -> LoginError.NOT_PANEL
            else -> LoginError.NETWORK
        }
    }

    fun consumeError() = _ui.update { it.copy(error = null, errorDetail = null) }

    private companion object {
        const val TAG = "LoginViewModel"
    }
}
