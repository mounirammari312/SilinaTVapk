package com.superz.iptvplayer.ui.hub

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.6.0 — the home page's pure text contracts, replicated from the
 * reference app (activity_home / HomeActivity / AccountInfoDlgFragment):
 *
 *  • [reloadedTimeAgo]  — Utils.getReloadedTimeAgo VERBATIM (the reference
 *    hardcodes the English words; only the digits localize). Drives the
 *    "Last Update :" strips under the three cards.
 *  • [homeExpirationValue] — HomeActivity.onCreate's portal/xc/else ladder
 *    for the ly_expiration pill.
 *  • [homeLoggedInValue]   — the ly_logged pill's identity ladder.
 *  • [accountUserValue] / [accountExpiryValue] — the account dialog's
 *    per-type values (the reference leaves the m3u rows empty).
 */
object VuHomeContract {

    /**
     * Utils.getReloadedTimeAgo, line for line:
     *  • never synced (0)          → ""            (the strip shows only the label)
     *  • clock skew / just synced  → "00mins ago"
     *  • < 1h                      → "%02dmins ago"
     *  • ≥ 1h                      → "%02dhours %02dmins ago"
     */
    fun reloadedTimeAgo(
        nowMillis: Long,
        lastSyncEpochSeconds: Long,
        locale: Locale = Locale.getDefault()
    ): String {
        if (lastSyncEpochSeconds == 0L) return ""
        val diffSeconds = nowMillis / 1000L - lastSyncEpochSeconds
        if (diffSeconds <= 0L) return "00mins ago"
        val hours = diffSeconds / 3600
        val minutes = (diffSeconds % 3600) / 60
        return if (hours > 0) {
            String.format(locale, "%02dhours %02dmins ago", hours, minutes)
        } else {
            String.format(locale, "%02dmins ago", minutes)
        }
    }

    /**
     * The ly_expiration pill's value (the label + space is prepended by the UI):
     *  • PORTAL        → "" (our portals store no expiry; the reference shows
     *    the bare label when the portal profile has no expire_date)
     *  • XTREAM        → "MMMM dd, yyyy" (HomeActivity's format), "" when the
     *    epoch is 0, literal "unlimited" when null (Utils.getDate's fallback)
     *  • M3U / BROWSER → the "Unlimited" resource text
     */
    fun homeExpirationValue(
        type: String,
        expiryEpochSeconds: Long?,
        unlimitedText: String,
        locale: Locale = Locale.getDefault()
    ): String = when (type) {
        "PORTAL" -> ""
        "XTREAM" -> when {
            expiryEpochSeconds == null -> "unlimited"
            expiryEpochSeconds == 0L -> ""
            else -> SimpleDateFormat("MMMM dd, yyyy", locale)
                .format(Date(expiryEpochSeconds * 1000L))
        }
        else -> unlimitedText
    }

    /**
     * The ly_logged pill's identity: portal → the MAC, xc → the username.
     * The reference prints the device MAC for m3u/browser accounts; we have
     * no device MAC, so the playlist's own name stands in (documented
     * deviation — the row must still say SOMETHING coherent).
     */
    fun homeLoggedInValue(type: String, username: String?, playlistName: String): String =
        when (type) {
            "PORTAL", "XTREAM" -> username ?: ""
            else -> playlistName
        }

    /** The account dialog's "User Info" row: m3u rows stay EMPTY (verbatim). */
    fun accountUserValue(type: String, username: String?): String = when (type) {
        "PORTAL", "XTREAM" -> username ?: ""
        else -> ""
    }

    /**
     * The account dialog's "Expiry Date" row (dd/MM/yyyy — the dialog's own
     * format, unlike the home pill's "MMMM dd, yyyy"): portal/m3u → "",
     * xc → date / "" for 0 / literal "unlimited" for null.
     */
    fun accountExpiryValue(
        type: String,
        expiryEpochSeconds: Long?,
        locale: Locale = Locale.getDefault()
    ): String = when (type) {
        "PORTAL" -> ""
        "XTREAM" -> when {
            expiryEpochSeconds == null -> "unlimited"
            expiryEpochSeconds == 0L -> ""
            else -> SimpleDateFormat("dd/MM/yyyy", locale)
                .format(Date(expiryEpochSeconds * 1000L))
        }
        else -> ""
    }

    /**
     * The account dialog's "Account Status" row: the reference prints the
     * portal's hardcoded "Active" and the xc server's status string (which
     * is "Active" on every working line). We persist no status → "Active".
     */
    const val ACCOUNT_STATUS_ACTIVE = "Active"
}
