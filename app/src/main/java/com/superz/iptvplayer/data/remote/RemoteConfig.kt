package com.superz.iptvplayer.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * v2.0.0 — THE REMOTE CONFIG MODEL.
 *
 * Everything the admin panel controls lives in one JSON document served by
 * GET {GATEWAY}/api/config. The app parses it into these immutable structs
 * and falls back to [Defaults] for every missing/invalid field — so a brand
 * new install, a dead gateway or a partially-deployed panel all yield a
 * complete, usable config (graceful degradation: the app NEVER hard-depends
 * on the panel).
 *
 * JSON shape (mirrors download/oria-admin lib/shared.js DEFAULT_CONFIG):
 * {
 *   "schemaVersion": 1,
 *   "branding": { "accentColor": "#D4AF37", "logoUrl": "",
 *                 "bgGlobalUrl": "",
 *                 "bgEntryUrl": "", "bgLoginUrl": "",
 *                 "bgAccountsUrl": "", "bgLoadingUrl": "",
 *                 "appName": "", "appIconUrl": "" },
 *   "servers":   { "qrPageUrl": "...", "qrApiUrl": "...", "codeApiUrl": "..." },
 *   "update":    { "versionCode": 64, "versionName": "1.19.15",
 *                  "notes": "", "apkUrl": "", "mandatory": false },
 *   "announcement": { "enabled": false, "title": "", "body": "",
 *                  "imageUrl": "", "linkUrl": "", "buttonText": "" },
 *   "premium":   { "enabled": false, "host": "", "whatsapp": "",
 *                  "messageTemplate": "...", "plans": [ {...} ] },
 *   "entry":     { "upgradeVisible": true, "upgradeText": "",
 *                  "codeVisible": true, "codeText": "" }
 * }
 */
data class RemoteConfig(
    val branding: Branding = Branding(),
    val servers: Servers = Servers(),
    val update: UpdateInfo = UpdateInfo(),
    val announcement: Announcement = Announcement(),
    val premium: Premium = Premium(),
    val entry: Entry = Entry()
) {

    data class Branding(
        val accentColor: String = Defaults.ACCENT_COLOR,
        /** '' → the app's built-in oria_logo.png */
        val logoUrl: String = "",
        /** v2.2.0 — THE GLOBAL background: one image the panel sets once and
         *  EVERY page paints (home, channels grid, mini player, info pages —
         *  current and future). Blank → each page keeps its own slot/stock
         *  background, exactly as before. */
        val bgGlobalUrl: String = "",
        /** background of the app-opening / connection-method picker page */
        val bgEntryUrl: String = "",
        /** background of the login form page */
        val bgLoginUrl: String = "",
        /** v2.0.3 — background of the accounts list page */
        val bgAccountsUrl: String = "",
        /** v2.0.3 — background of the loading/sync screen */
        val bgLoadingUrl: String = "",
        /** v2.0.3 — white-label app name ('' → the built-in badge). Shown
         *  in-app instantly; the LAUNCHER label needs a rebuilt APK. */
        val appName: String = "",
        /** v2.0.3 — white-label launcher icon asset. In-app logo comes from
         *  logoUrl; this slot feeds the white-label APK rebuild. */
        val appIconUrl: String = ""
    )

    data class Servers(
        val qrPageUrl: String = Defaults.QR_PAGE_URL,
        val qrApiUrl: String = Defaults.QR_API_URL,
        val codeApiUrl: String = Defaults.CODE_API_URL
    )

    data class UpdateInfo(
        val versionCode: Int = Defaults.VERSION_CODE,
        val versionName: String = Defaults.VERSION_NAME,
        val notes: String = "",
        val apkUrl: String = "",
        val mandatory: Boolean = false
    )

    data class Announcement(
        val enabled: Boolean = false,
        val title: String = "",
        val body: String = "",
        /** v2.0.2 — the ad image ('' → text-only card). */
        val imageUrl: String = "",
        /** v2.0.2 — opened when the user taps the image / CTA ('' → no link). */
        val linkUrl: String = "",
        /** v2.0.2 — CTA label ('' → the app's localized default "زيارة"). */
        val buttonText: String = ""
    ) {
        /** Stable identity of the content — the app shows the card once per change. */
        val signature: String get() = "$title|$body|$imageUrl|$linkUrl|$buttonText"
    }

    data class Premium(
        val enabled: Boolean = false,
        val host: String = "",
        val whatsapp: String = "",
        val messageTemplate: String = Defaults.PREMIUM_TEMPLATE,
        /** v2.1.3 — the per-language WhatsApp templates (user: the message
         *  must follow the APP's language). Blank = fall through to
         *  [messageTemplate] (Arabic) / the app's built-in default. */
        val messageTemplateAr: String = Defaults.PREMIUM_TEMPLATE_AR,
        val messageTemplateEn: String = Defaults.PREMIUM_TEMPLATE_EN,
        val plans: List<Plan> = emptyList()
    ) {
        data class Plan(
            val name: String,
            val price: String,
            val currency: String,
            val durationDays: Int,
            val popular: Boolean
        )
    }

    /**
     * v2.0.6 — the panel-controlled buttons of the login/method-picker page
     * (the admin panel's appearance tab → "أزرار شاشة الدخول"):
     * hide/show + custom label for (1) the shared Upgrade-to-Premium banner
     * (entry page + accounts + settings) and (2) the 6-digit CODE pill.
     * Every field defaults to the app's built-in behavior, so an older
     * panel config (or a cached pre-v2.0.6 body) changes nothing.
     */
    data class Entry(
        /** false → the golden Upgrade-to-Premium banner pills disappear. */
        val upgradeVisible: Boolean = true,
        /** '' → the app's localized premium_cta default. */
        val upgradeText: String = "",
        /** false → the 6-digit code pill disappears; the row re-flows. */
        val codeVisible: Boolean = true,
        /** '' → the app's localized vu_login_code default. */
        val codeText: String = ""
    )

    companion object {

        /** The app's built-in baseline — MUST mirror the panel's DEFAULT_CONFIG. */
        val DEFAULTS = RemoteConfig()

        fun parse(json: JSONObject): RemoteConfig = RemoteConfig(
            branding = json.optJSONObject("branding").let { b ->
                Branding(
                    accentColor = b?.optHex("accentColor") ?: Defaults.ACCENT_COLOR,
                    logoUrl = b?.optUrl("logoUrl") ?: "",
                    // v2.2.0 — the ONE global background (see Branding.bgGlobalUrl).
                    bgGlobalUrl = b?.optUrl("bgGlobalUrl") ?: "",
                    bgEntryUrl = b?.optUrl("bgEntryUrl") ?: "",
                    bgLoginUrl = b?.optUrl("bgLoginUrl") ?: "",
                    // v2.0.3 — the two new panel-controlled backgrounds +
                    // the white-label app name / launcher icon slot.
                    bgAccountsUrl = b?.optUrl("bgAccountsUrl") ?: "",
                    bgLoadingUrl = b?.optUrl("bgLoadingUrl") ?: "",
                    appName = b?.optString("appName")?.take(30)?.trim() ?: "",
                    appIconUrl = b?.optUrl("appIconUrl") ?: ""
                )
            },
            servers = json.optJSONObject("servers").let { s ->
                Servers(
                    qrPageUrl = s?.optUrl("qrPageUrl") ?: Defaults.QR_PAGE_URL,
                    qrApiUrl = s?.optUrl("qrApiUrl") ?: Defaults.QR_API_URL,
                    codeApiUrl = s?.optUrl("codeApiUrl") ?: Defaults.CODE_API_URL
                )
            },
            update = json.optJSONObject("update").let { u ->
                UpdateInfo(
                    // sanitize like the panel's own validation (1..2,000,000):
                    // a malformed/negative code must never masquerade as a
                    // newer version on any installed app.
                    versionCode = u?.optInt("versionCode", Defaults.VERSION_CODE)
                        ?.takeIf { it in 1..2_000_000 } ?: Defaults.VERSION_CODE,
                    versionName = u?.optString("versionName", Defaults.VERSION_NAME) ?: Defaults.VERSION_NAME,
                    notes = u?.optString("notes")?.take(2000) ?: "",
                    apkUrl = u?.optUrl("apkUrl") ?: "",
                    mandatory = u?.optBoolean("mandatory") ?: false
                )
            },
            announcement = json.optJSONObject("announcement").let { a ->
                Announcement(
                    enabled = a?.optBoolean("enabled") ?: false,
                    title = a?.optString("title")?.take(80) ?: "",
                    body = a?.optString("body")?.take(300) ?: "",
                    // v2.0.2 — the Google-ads-style card's visual/link parts.
                    imageUrl = a?.optUrl("imageUrl") ?: "",
                    linkUrl = a?.optUrl("linkUrl") ?: "",
                    buttonText = a?.optString("buttonText")?.take(24)?.trim() ?: ""
                )
            },
            premium = json.optJSONObject("premium").let { p ->
                Premium(
                    enabled = p?.optBoolean("enabled") ?: false,
                    host = p?.optUrl("host") ?: "",
                    // sanitize exactly like the panel (digits and + only,
                    // 5..20 chars) — a malformed number never reaches the
                    // WhatsApp intent builder.
                    whatsapp = p?.optString("whatsapp")?.take(20)
                        ?.takeIf { Regex("^[+0-9]{5,20}$").matches(it) } ?: "",
                    messageTemplate = p?.optString("messageTemplate")?.take(300) ?: Defaults.PREMIUM_TEMPLATE,
                    // v2.1.3 — optString(key) returns "" for a MISSING key;
                    // with null as the explicit fallback a pre-v2.1.3 panel
                    // body parses into the built-in defaults instead of blank
                    // slots (an explicit blank on the panel still survives —
                    // the seller may deliberately disable customization).
                    messageTemplateAr = p?.optString("messageTemplateAr", null)?.take(300) ?: Defaults.PREMIUM_TEMPLATE_AR,
                    messageTemplateEn = p?.optString("messageTemplateEn", null)?.take(300) ?: Defaults.PREMIUM_TEMPLATE_EN,
                    plans = p?.optJSONArray("plans")?.mapPlans() ?: emptyList()
                )
            },
            entry = json.optJSONObject("entry").let { e ->
                Entry(
                    // optBoolean(key, fallback) keeps the built-in behavior
                    // for every missing/non-boolean value — an old panel
                    // body can never turn a button off by accident.
                    upgradeVisible = e?.optBoolean("upgradeVisible", true) ?: true,
                    upgradeText = e?.optString("upgradeText")?.take(30)?.trim() ?: "",
                    codeVisible = e?.optBoolean("codeVisible", true) ?: true,
                    codeText = e?.optString("codeText")?.take(30)?.trim() ?: ""
                )
            }
        )

        private fun JSONArray.mapPlans(): List<Premium.Plan> =
            (0 until length()).mapNotNull { i ->
                val p = optJSONObject(i) ?: return@mapNotNull null
                val name = p.optString("name").trim().take(40)
                val price = p.optString("price").trim().take(12)
                if (name.isEmpty() || price.isEmpty()) return@mapNotNull null
                Premium.Plan(
                    name = name,
                    price = price,
                    currency = p.optString("currency").trim().take(8),
                    durationDays = p.optInt("durationDays", 30).coerceIn(1, 3650),
                    popular = p.optBoolean("popular")
                )
            }

        /** A URL-ish string or "" — anything else is treated as absent. */
        private fun JSONObject.optUrl(key: String): String? {
            val v = optString(key, "") ?: return ""
            val t = v.trim()
            if (t.isEmpty()) return ""
            if (t.startsWith("/api/asset")) return t
            return if (t.startsWith("http://") || t.startsWith("https://")) t.take(2048) else ""
        }

        /** #RRGGBB or null (invalid → default). */
        private fun JSONObject.optHex(key: String): String? {
            val v = optString(key, "") ?: return null
            return if (Regex("^#[0-9A-Fa-f]{6}$").matches(v)) v.uppercase() else null
        }
    }
}

/** Values shared between the model defaults and the legacy engine constants. */
object Defaults {
    const val ACCENT_COLOR = "#D4AF37"

    // Must stay byte-identical to the pre-v2.0.0 constants so the built-in
    // fallback equals the behavior users already have.
    const val QR_PAGE_URL = "https://silinatv-qr-page.vercel.app/"
    const val QR_API_URL = "https://silinatv-qr-page.vercel.app/api/qr"
    const val CODE_API_URL = "https://empreinte-zkfk.onrender.com/app/generate"

    const val VERSION_CODE = 71
    const val VERSION_NAME = "2.0.6"

    const val PREMIUM_TEMPLATE = "السلام عليكم، أريد الاشتراك في خطة {plan} في تطبيق ORIA"

    /** v2.1.3 — the per-language template defaults (the app falls back to
     *  its own localized string when the panel sends nothing). */
    const val PREMIUM_TEMPLATE_AR = PREMIUM_TEMPLATE
    const val PREMIUM_TEMPLATE_EN = "Hello, I would like to subscribe to the {plan} plan in the ORIA app"
}
