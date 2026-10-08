package com.superz.iptvplayer.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.superz.iptvplayer.data.remote.RemoteConfig

/**
 * v2.0.0 — THE LIVE BRANDING STATE.
 *
 * Everything the admin panel can restyle at runtime lives here as Compose
 * snapshot state, so ANY screen reading it recomposes the moment the panel
 * saves a change:
 *
 *  • [logoUrl] / [bgEntryUrl] / [bgLoginUrl] — remote images (empty = the
 *    app's built-in drawables; Coil's error/placeholder fallback keeps the
 *    stock look while loading or when the URL dies).
 *  • the GOLD FAMILY — delegated through [VuGold] (see VuGolden.kt). A new
 *    accent color re-derives the whole metallic family in place, and every
 *    read site (131 of them across 14 files, all through the VuGold object)
 *    picks it up without a single usage-site change.
 *
 * [applyConfig] is called by OriaRemote when a config is applied (cached at
 * process start, refreshed after the network fetch).
 */
object OriaBranding {

    /** Remote logo — '' → built-in oria_logo.png. */
    var logoUrl by mutableStateOf("")
        private set

    /** v2.2.0 — THE GLOBAL background: one image for EVERY page (home,
     *  channels grid, mini player, info pages — current and future).
     *  '' → each page keeps its own slot / stock background. */
    var bgGlobalUrl by mutableStateOf("")
        private set

    /** Background of the connection-method picker (app-opening page). */
    var bgEntryUrl by mutableStateOf("")
        private set

    /** Background of the login form page. */
    var bgLoginUrl by mutableStateOf("")
        private set

    /** v2.0.3 — background of the accounts list page. */
    var bgAccountsUrl by mutableStateOf("")
        private set

    /** v2.0.3 — background of the loading/sync screen. */
    var bgLoadingUrl by mutableStateOf("")
        private set

    /** v2.0.3 — white-label app name ('' → the built-in "Premium" badge). */
    var appName by mutableStateOf("")
        private set

    /** v2.0.3 — white-label launcher icon asset (feeds the APK rebuild). */
    var appIconUrl by mutableStateOf("")
        private set

    /** Last applied announcement signature ("" while nothing shown yet). */
    var announcementSignature by mutableStateOf("")
        private set

    /** True once a config with an enabled announcement has arrived. */
    var announcementEnabled by mutableStateOf(false)
        private set

    /** The announcement's title/body as Compose state (panel-authored text). */
    var announcementTitle by mutableStateOf("")
        private set
    var announcementBody by mutableStateOf("")
        private set

    /** v2.0.2 — the Google-ads-style card: image, link and CTA label. */
    var announcementImageUrl by mutableStateOf("")
        private set
    var announcementLinkUrl by mutableStateOf("")
        private set
    var announcementButtonText by mutableStateOf("")
        private set

    /** v2.0.2 — the Premium section, alive for the login flow's entry banner. */
    var premiumEnabled by mutableStateOf(false)
        private set

    /** v2.0.6 — the panel-controlled entry buttons (RemoteConfig.Entry):
     *  visibility + optional label override for the Upgrade-to-Premium
     *  banner and the 6-digit CODE pill. Defaults = the built-in look. */
    var premiumCtaVisible by mutableStateOf(true)
        private set
    var premiumCtaText by mutableStateOf("")
        private set
    var codeLoginVisible by mutableStateOf(true)
        private set
    var codeLoginText by mutableStateOf("")
        private set

    fun applyConfig(config: RemoteConfig) {
        logoUrl = config.branding.logoUrl
        bgGlobalUrl = config.branding.bgGlobalUrl
        bgEntryUrl = config.branding.bgEntryUrl
        bgLoginUrl = config.branding.bgLoginUrl
        bgAccountsUrl = config.branding.bgAccountsUrl
        bgLoadingUrl = config.branding.bgLoadingUrl
        appName = config.branding.appName
        appIconUrl = config.branding.appIconUrl
        announcementEnabled = config.announcement.enabled
        announcementTitle = if (config.announcement.enabled) config.announcement.title else ""
        announcementBody = if (config.announcement.enabled) config.announcement.body else ""
        announcementImageUrl = if (config.announcement.enabled) config.announcement.imageUrl else ""
        announcementLinkUrl = if (config.announcement.enabled) config.announcement.linkUrl else ""
        announcementButtonText = if (config.announcement.enabled) config.announcement.buttonText else ""
        announcementSignature = if (config.announcement.enabled) config.announcement.signature else ""
        premiumEnabled = config.premium.enabled && config.premium.host.isNotBlank()
        // v2.0.6 — the appearance tab's button controls (panel v2.0.4+).
        premiumCtaVisible = config.entry.upgradeVisible
        premiumCtaText = config.entry.upgradeText
        codeLoginVisible = config.entry.codeVisible
        codeLoginText = config.entry.codeText
        // The gold family: parse #RRGGBB → re-derive the 9 metallic members.
        VuGold.applyAccent(parseHex(config.branding.accentColor) ?: Color(0xFFD4AF37))
    }

    /** "#RRGGBB" → Color, or null when malformed (caller keeps the default). */
    fun parseHex(hex: String): Color? {
        if (!Regex("^#[0-9A-Fa-f]{6}$").matches(hex)) return null
        val r = Integer.parseInt(hex.substring(1, 3), 16)
        val g = Integer.parseInt(hex.substring(3, 5), 16)
        val b = Integer.parseInt(hex.substring(5, 7), 16)
        return Color(r, g, b)
    }
}
