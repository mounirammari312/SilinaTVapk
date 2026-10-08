package com.superz.iptvplayer.ui.login

/**
 * v1.4.11 — the login flow's pure model, replicated from the reference
 * app's Java (AddPortalActivity / EditPortalActivity) and its layouts.
 * Pure Kotlin on purpose so the whole flow contract is unit-tested
 * (VuLoginFlowTest) without Android.
 *
 * v1.19.12 — the ONBOARDING PAGES ARE GONE (user directive): the intro
 * slider and the first-run language selection were removed — the app
 * now follows the device locale out of the box (Arabic device → Arabic,
 * anything else → English) and the first screen is the portal method
 * picker. The language GRID survives only as the Settings language
 * page, carrying exactly the two locales the app actually ships
 * (values/ + values-ar/).
 */
object VuLoginFlow {

    /**
     * v1.19.12 — the languages the app ACTUALLY ships (values/ English +
     * values-ar/ Arabic). The reference's 17-entry list was cut to these
     * two (user directive: the others do not exist in the app — selecting
     * them changed nothing but the checkmark).
     */
    data class VuLanguage(val display: String, val tag: String)

    val LANGUAGES: List<VuLanguage> = listOf(
        VuLanguage("ENGLISH (US)", "en"),
        VuLanguage("عربي (AR)", "ar")
    )

    /** The fallback language when a stored tag matches nothing. */
    const val DEFAULT_TAG = "en"

    fun languageForTag(tag: String?): VuLanguage =
        LANGUAGES.firstOrNull { it.tag == tag } ?: LANGUAGES.first()

    /** AddPortalActivity's "type" extra → EditPortalActivity's field matrix. */
    enum class PortalType { M3U, XC, BROWSER, PORTAL, CODE, QR }

    /** EditPortalActivity.onCreate's header switch, as data. */
    enum class Header { LOGIN_DETAILS, STALKER, BROWSE_PLAYLIST }

    /** Hint keys — mapped to string resources by the screen (keeps this pure). */
    object Hint {
        const val USERNAME = "username"
        const val PASSWORD = "password"
        const val PORTAL_URL = "portal_url"
        const val M3U_URL = "m3u_url"
        const val MAC_ADDRESS = "mac_address"
        const val M3U_FILE_PATH = "m3u_file_path"
        const val CODE = "code"
    }

    /**
     * EditPortalActivity.onCreate's switch (type → which views are VISIBLE),
     * as data. et_name is always visible; the MAC row is GONE in every
     * branch (the reference generates a MAC but keeps the row hidden).
     */
    data class FormFields(
        val username: Boolean,
        val password: Boolean,
        val address: Boolean,
        val addressEnabled: Boolean,
        val browseButton: Boolean,
        val header: Header,
        val usernameHint: String,
        val addressHint: String
    )

    fun fieldsFor(type: PortalType): FormFields = when (type) {
        PortalType.XC -> FormFields(
            username = true, password = true, address = true, addressEnabled = true,
            browseButton = false, header = Header.LOGIN_DETAILS,
            usernameHint = Hint.USERNAME, addressHint = Hint.PORTAL_URL
        )
        PortalType.M3U -> FormFields(
            username = false, password = false, address = true, addressEnabled = true,
            browseButton = false, header = Header.LOGIN_DETAILS,
            usernameHint = Hint.USERNAME, addressHint = Hint.M3U_URL
        )
        PortalType.PORTAL -> FormFields(
            username = true, password = false, address = true, addressEnabled = true,
            browseButton = false, header = Header.STALKER,
            usernameHint = Hint.MAC_ADDRESS, addressHint = Hint.PORTAL_URL
        )
        PortalType.BROWSER -> FormFields(
            username = false, password = false, address = true, addressEnabled = false,
            browseButton = true, header = Header.BROWSE_PLAYLIST,
            usernameHint = Hint.USERNAME, addressHint = Hint.M3U_FILE_PATH
        )
        // v1.13.0 — 6-digit code login: the generation server delivers 3
        // verified Xtream accounts; the single field holds the code itself.
        PortalType.CODE -> FormFields(
            username = false, password = false, address = true, addressEnabled = true,
            browseButton = false, header = Header.LOGIN_DETAILS,
            usernameHint = Hint.USERNAME, addressHint = Hint.CODE
        )
        // v1.14.0 — QR login: no form at all. The QR pill on the method grid
        // opens the bridge dialog directly (QR + 6-digit TV code + bridge
        // polling), and the decoded payload feeds the existing login engines.
        // This FormFields entry exists only to keep the model exhaustive.
        PortalType.QR -> FormFields(
            username = false, password = false, address = false, addressEnabled = false,
            browseButton = false, header = Header.LOGIN_DETAILS,
            usernameHint = Hint.USERNAME, addressHint = Hint.CODE
        )
    }

    /**
     * The app's entry gate (MainActivity's startDestination):
     * an existing user (playlists in Room) goes straight to the accounts
     * page; a brand-new install lands on the method-selection page —
     * v1.19.12 removed the intro-slider step (and its introDone pref):
     * the very first screen is the portal picker, already speaking the
     * device's own language.
     */
    fun entryRoute(hasPlaylists: Boolean): String =
        if (hasPlaylists) "accounts" else "addPortal"
}
