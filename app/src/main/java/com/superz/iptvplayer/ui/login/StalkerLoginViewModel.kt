package com.superz.iptvplayer.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.data.PlaylistRepository
import com.superz.iptvplayer.data.stalker.StalkerException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * v1.5.0 — MAC/portal login controller, shaped EXACTLY like the existing
 * LoginViewModel (which stays byte-untouched): a tiny UiState
 * (loading / error / success) driven from the new StalkerClient engine.
 *
 * Flow = EditPortalActivity.checkPortalUrl → getToken → getProfile →
 * addPortalToRealm; the repository's loginPortal performs the ladder and
 * stores the account. On success the caller hands off to the loading
 * screen, which syncs the portal's channels (repo.syncStalker).
 */
class StalkerLoginViewModel(app: Application) : AndroidViewModel(app) {

    enum class Error { PORTAL_NOT_WORKING, DUPLICATE_NAME, INVALID_URL, NETWORK,
        // v2.1.0 — the free 3-account ceiling (repository backstop).
        ACCOUNT_LIMIT }

    data class UiState(
        val loading: Boolean = false,
        val error: Error? = null,
        val success: Boolean = false
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val repo: PlaylistRepository = PlaylistRepository.get(getApplication<IPTVApp>())

    /**
     * Add-mode MAC login: name + portal URL + MAC address.
     * The duplicate-name / empty-MAC checks happen in the SCREEN (the
     * reference toasts before any network call); this method performs the
     * network ladder only.
     */
    fun loginPortal(name: String, url: String, mac: String) {
        _ui.update { it.copy(loading = true, error = null, success = false) }
        viewModelScope.launch {
            try {
                repo.loginPortal(name.trim(), url.trim(), mac.trim())
                _ui.update { it.copy(loading = false, success = true) }
            } catch (e: StalkerException) {
                _ui.update { it.copy(loading = false, error = mapError(e)) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = Error.NETWORK) }
            }
        }
    }

    /**
     * Add-mode local-file login: name + SAF document uri (persistable read
     * permission already taken by the picker). No network involved — the
     * loading screen reads the file from disk.
     */
    fun loginBrowserFile(name: String, fileUri: String) {
        _ui.update { it.copy(loading = true, error = null, success = false) }
        viewModelScope.launch {
            try {
                repo.loginBrowserFile(name.trim(), fileUri)
                _ui.update { it.copy(loading = false, success = true) }
            } catch (e: StalkerException) {
                _ui.update { it.copy(loading = false, error = mapError(e)) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = Error.NETWORK) }
            }
        }
    }

    private fun mapError(e: StalkerException): Error = when (e.message) {
        "DUPLICATE_NAME" -> Error.DUPLICATE_NAME
        "INVALID_PORTAL_URL" -> Error.INVALID_URL
        "ACCOUNT_LIMIT" -> Error.ACCOUNT_LIMIT
        else -> Error.PORTAL_NOT_WORKING   // PORTAL_NOT_WORKING / HTTP_* / EMPTY_BODY
    }

    fun consumeError() = _ui.update { it.copy(error = null) }
}
