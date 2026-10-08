package com.superz.iptvplayer.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * v2.0.0 — the remote config parser's contract (plain JVM; org.json is a
 * real test dependency).
 *
 * The panel can only ADD, never break: a dead gateway, an empty DB or a
 * partially-migrated schema must all yield a COMPLETE config with the
 * built-in defaults in every missing slot.
 */
class RemoteConfigTest {

    @Test
    fun emptyJsonYieldsCompleteDefaults() {
        val cfg = RemoteConfig.parse(JSONObject())
        assertEquals(RemoteConfig.DEFAULTS, cfg)
        assertEquals("#D4AF37", cfg.branding.accentColor)
        assertEquals(Defaults.VERSION_CODE, cfg.update.versionCode)
        assertEquals(Defaults.VERSION_NAME, cfg.update.versionName)
        assertEquals(Defaults.QR_API_URL, cfg.servers.qrApiUrl)
        assertTrue(cfg.branding.logoUrl.isEmpty())
        assertFalse(cfg.announcement.enabled)
        assertFalse(cfg.premium.enabled)
    }

    @Test
    fun fullValidJsonParsesEveryField() {
        val json = JSONObject(
            """
            {
              "branding": { "accentColor": "#00AAFF", "logoUrl": "/api/asset/abc.png",
                            "bgGlobalUrl": "/api/asset/global.jpg",
                            "bgEntryUrl": "https://cdn.example.com/entry.jpg",
                            "bgLoginUrl": "https://cdn.example.com/login.jpg",
                            "bgAccountsUrl": "/api/asset/accounts.jpg",
                            "bgLoadingUrl": "https://cdn.example.com/loading.jpg",
                            "appName": "ORIA TV",
                            "appIconUrl": "/api/asset/icon.png" },
              "servers": { "qrPageUrl": "https://q.example.com/",
                           "qrApiUrl": "https://q.example.com/api/qr",
                           "codeApiUrl": "https://c.example.com/generate" },
              "update": { "versionCode": 99, "versionName": "9.9.9",
                          "notes": "notes", "apkUrl": "https://x.example.com/app.apk",
                          "mandatory": true },
              "announcement": { "enabled": true, "title": "مرحبا", "body": "نص الإعلان",
                              "imageUrl": "/api/asset/ad.png", "linkUrl": "https://shop.example.com",
                              "buttonText": "اشترك الآن" },
              "premium": { "enabled": true, "host": "http://s.example.com:8080",
                           "whatsapp": "+212600000000",
                           "messageTemplate": "tpl {plan}",
                           "plans": [
                             { "name": "شهر", "price": "50", "currency": "DH",
                               "durationDays": 30, "popular": true },
                             { "name": "", "price": "9" }
                           ] }
            }
            """.trimIndent()
        )
        val cfg = RemoteConfig.parse(json)
        assertEquals("#00AAFF", cfg.branding.accentColor)
        assertEquals("/api/asset/abc.png", cfg.branding.logoUrl)
        assertEquals("https://cdn.example.com/entry.jpg", cfg.branding.bgEntryUrl)
        assertEquals("https://q.example.com/api/qr", cfg.servers.qrApiUrl)
        assertEquals(99, cfg.update.versionCode)
        assertTrue(cfg.update.mandatory)
        assertTrue(cfg.announcement.enabled)
        assertEquals("مرحبا", cfg.announcement.title)
        // v2.0.2 — the ad card's visual/link parts
        assertEquals("/api/asset/ad.png", cfg.announcement.imageUrl)
        assertEquals("https://shop.example.com", cfg.announcement.linkUrl)
        assertEquals("اشترك الآن", cfg.announcement.buttonText)
        assertEquals("مرحبا|نص الإعلان|/api/asset/ad.png|https://shop.example.com|اشترك الآن",
            cfg.announcement.signature)
        // v2.2.0 — the ONE global background parses like its siblings
        assertEquals("/api/asset/global.jpg", cfg.branding.bgGlobalUrl)
        // v2.0.3 — the new branding slots parse like their siblings
        assertEquals("/api/asset/accounts.jpg", cfg.branding.bgAccountsUrl)
        assertEquals("https://cdn.example.com/loading.jpg", cfg.branding.bgLoadingUrl)
        assertEquals("ORIA TV", cfg.branding.appName)
        assertEquals("/api/asset/icon.png", cfg.branding.appIconUrl)
        assertTrue(cfg.premium.enabled)
        assertEquals("+212600000000", cfg.premium.whatsapp)
        // the nameless plan is dropped, the valid one survives
        assertEquals(1, cfg.premium.plans.size)
        assertEquals("شهر", cfg.premium.plans[0].name)
        assertEquals(30, cfg.premium.plans[0].durationDays)
        assertTrue(cfg.premium.plans[0].popular)
    }

    @Test
    fun invalidFieldsFallBackInsteadOfBreaking() {
        val json = JSONObject(
            """
            {
              "branding": { "accentColor": "yellow", "logoUrl": "javascript:alert(1)",
                            "bgGlobalUrl": "javascript:evil", 
                            "bgEntryUrl": "not-a-url", "bgAccountsUrl": "ftp://nope",
                            "bgLoadingUrl": "javascript:x", "appIconUrl": "not-a-url",
                            "appName": "  " },
              "update": { "versionCode": -5, "apkUrl": "ftp://nope" },
              "premium": { "whatsapp": "0042!" }
            }
            """.trimIndent()
        )
        val cfg = RemoteConfig.parse(json)
        // bad hex → the classic gold
        assertEquals("#D4AF37", cfg.branding.accentColor)
        // non-http(s) and non-asset URLs are treated as absent
        assertEquals("", cfg.branding.logoUrl)
        assertEquals("", cfg.branding.bgGlobalUrl)   // v2.2.0 — bad global URL = absent
        assertEquals("", cfg.branding.bgEntryUrl)
        assertEquals("", cfg.branding.bgAccountsUrl)
        assertEquals("", cfg.branding.bgLoadingUrl)
        assertEquals("", cfg.branding.appIconUrl)
        assertEquals("", cfg.branding.appName)   // blank-trimmed to nothing
        assertEquals("", cfg.update.apkUrl)
        // negative versionCode stays the default (optInt fallback)
        assertEquals(Defaults.VERSION_CODE, cfg.update.versionCode)
        assertEquals("", cfg.premium.whatsapp)
    }

    @Test
    fun v220GlobalBackgroundSlotRoundTripsAndDefaultsToEmpty() {
        // a missing/legacy panel body (every config saved before v2.2.0)
        // must parse with the slot EMPTY — no page changes for old installs.
        val legacy = RemoteConfig.parse(JSONObject("""{"branding":{"accentColor":"#D4AF37"}}"""))
        assertEquals("", legacy.branding.bgGlobalUrl)

        // a https URL and an uploaded panel asset both survive verbatim…
        val set = RemoteConfig.parse(JSONObject(
            """{"branding":{"bgGlobalUrl":"https://cdn.example.com/bg.jpg"}}"""))
        assertEquals("https://cdn.example.com/bg.jpg", set.branding.bgGlobalUrl)
        val asset = RemoteConfig.parse(JSONObject(
            """{"branding":{"bgGlobalUrl":"/api/asset/bg.png"}}"""))
        assertEquals("/api/asset/bg.png", asset.branding.bgGlobalUrl)

        // …and the DEFAULTS baseline carries the same empty slot.
        assertEquals("", RemoteConfig.DEFAULTS.branding.bgGlobalUrl)
    }

    @Test
    fun v203BrandingSlotsAreSanitized() {
        // v2.0.3 — the app name is capped at 30 chars and trimmed; the icon
        // and the two new backgrounds follow the same URL rules as the logo.
        val json = JSONObject(
            """{"branding":{"appName":"  ORIA TV  ",
                "appIconUrl":"/api/asset/icon.png",
                "bgAccountsUrl":"https://cdn.example.com/a.jpg",
                "bgLoadingUrl":"/api/asset/l.jpg"}}"""
        )
        val cfg = RemoteConfig.parse(json)
        assertEquals("ORIA TV", cfg.branding.appName)   // trimmed
        assertEquals("/api/asset/icon.png", cfg.branding.appIconUrl)
        assertEquals("https://cdn.example.com/a.jpg", cfg.branding.bgAccountsUrl)
        assertEquals("/api/asset/l.jpg", cfg.branding.bgLoadingUrl)

        // overlong names are capped at 30 characters
        val long = JSONObject()
            .put("branding", JSONObject().put("appName", "X".repeat(40)))
        assertEquals(30, RemoteConfig.parse(long).branding.appName.length)
    }

    @Test
    fun relativeAssetUrlsSurviveForGatewayResolution() {
        // the panel's own uploaded images travel as relative /api/asset links;
        // the app resolves them against GATEWAY_BASE at draw time.
        val json = JSONObject("""{"branding":{"bgLoginUrl":"/api/asset/x.jpg"}}""")
        val cfg = RemoteConfig.parse(json)
        assertEquals("/api/asset/x.jpg", cfg.branding.bgLoginUrl)
    }

    @Test
    fun announcementAdFieldsValidateLikeEveryOtherUrl() {
        // v2.0.2 — the ad image/link go through the same optUrl rules: only
        // http(s) / relative-asset links survive, everything else is absent
        // (the card degrades to text mode instead of breaking).
        val json = JSONObject(
            """{"announcement":{"enabled":true,"title":"t",
                "imageUrl":"javascript:alert(1)","linkUrl":"ftp://nope",
                "buttonText":"  "}}"""
        )
        val cfg = RemoteConfig.parse(json)
        assertEquals("", cfg.announcement.imageUrl)
        assertEquals("", cfg.announcement.linkUrl)
        assertEquals("", cfg.announcement.buttonText)   // blank-trimmed
    }

    @Test
    fun defaultsMatchTheShippedBuild() {
        // The built-in baseline IS the current build — an empty panel must
        // never make every installed app think an update exists.
        assertEquals(71, Defaults.VERSION_CODE)
        assertEquals("2.0.6", Defaults.VERSION_NAME)
    }

    // ═══════════ v2.0.6 — the entry-screen button controls (RemoteConfig.Entry) ═══════════

    @Test
    fun entryDefaultsKeepBothButtonsVisibleWithBuiltInLabels() {
        // A pre-v2.0.6 panel body (or an empty one) must change NOTHING:
        // both buttons visible, both labels blank → the app's localized
        // defaults keep rendering.
        val cfg = RemoteConfig.parse(JSONObject())
        assertTrue(cfg.entry.upgradeVisible)
        assertTrue(cfg.entry.codeVisible)
        assertEquals("", cfg.entry.upgradeText)
        assertEquals("", cfg.entry.codeText)

        val emptyBlock = RemoteConfig.parse(JSONObject("""{"entry":{}}"""))
        assertTrue(emptyBlock.entry.upgradeVisible)
        assertTrue(emptyBlock.entry.codeVisible)
    }

    @Test
    fun entryFieldsParseVisibilityAndCustomLabels() {
        val json = JSONObject(
            """{"entry":{"upgradeVisible":false,"upgradeText":"Go GOLD",
                "codeVisible":true,"codeText":"كود 6 أرقام"}}"""
        )
        val cfg = RemoteConfig.parse(json)
        assertFalse(cfg.entry.upgradeVisible)
        assertEquals("Go GOLD", cfg.entry.upgradeText)
        assertTrue(cfg.entry.codeVisible)
        assertEquals("كود 6 أرقام", cfg.entry.codeText)
    }

    @Test
    fun entryInvalidValuesFallBackToVisibleDefaults() {
        // Non-boolean visibility values and non-string/garbage labels can
        // NEVER turn a button off or inject junk — same contract as every
        // other block: fallback, never break.
        val json = JSONObject(
            """{"entry":{"upgradeVisible":"yes","codeVisible":0,
                "upgradeText":"   ","codeText":123}}"""
        )
        val cfg = RemoteConfig.parse(json)
        assertTrue(cfg.entry.upgradeVisible)   // "yes" is not a boolean → default
        assertTrue(cfg.entry.codeVisible)      // 0 is not a boolean → default
        assertEquals("", cfg.entry.upgradeText)   // whitespace-trimmed to blank
        // numeric labels degrade to their string form, capped at 30 chars
        assertEquals("123", cfg.entry.codeText)
    }

    @Test
    fun entryLabelsAreCappedAtThirtyChars() {
        val json = JSONObject(
            """{"entry":{"upgradeText":"${"U".repeat(45)}",
                "codeText":"${"C".repeat(45)}"}}"""
        )
        val cfg = RemoteConfig.parse(json)
        assertEquals(30, cfg.entry.upgradeText.length)
        assertEquals(30, cfg.entry.codeText.length)
    }

    @Test
    fun premiumPerLanguageTemplatesParse() {
        val json = JSONObject(
            """{"premium":{"enabled":true,"host":"http://s.example.com:8080",
                "whatsapp":"+212600000000","messageTemplate":"legacy {plan}",
                "messageTemplateAr":"مرحبا {plan}",
                "messageTemplateEn":"Hello {plan}"}}"""
        )
        val cfg = RemoteConfig.parse(json)
        assertEquals("مرحبا {plan}", cfg.premium.messageTemplateAr)
        assertEquals("Hello {plan}", cfg.premium.messageTemplateEn)
        assertEquals("legacy {plan}", cfg.premium.messageTemplate)
    }

    @Test
    fun premiumPerLanguageTemplatesAreCappedAtThreeHundred() {
        val json = JSONObject(
            """{"premium":{"messageTemplateAr":"${"ا".repeat(400)}",
                "messageTemplateEn":"${"E".repeat(400)}"}}"""
        )
        val cfg = RemoteConfig.parse(json)
        assertEquals(300, cfg.premium.messageTemplateAr.length)
        assertEquals(300, cfg.premium.messageTemplateEn.length)
    }

    @Test
    fun premiumMissingPerLanguageTemplatesFallToBuiltInDefaults() {
        // a pre-v2.1.3 panel body: the single template parses as before and
        // the per-language slots carry the app's built-in defaults.
        val json = JSONObject(
            """{"premium":{"enabled":true,"host":"http://s.example.com:8080",
                "whatsapp":"+212600000000","messageTemplate":"tpl {plan}"}}"""
        )
        val cfg = RemoteConfig.parse(json)
        assertEquals("tpl {plan}", cfg.premium.messageTemplate)
        assertEquals(Defaults.PREMIUM_TEMPLATE_AR, cfg.premium.messageTemplateAr)
        assertEquals(Defaults.PREMIUM_TEMPLATE_EN, cfg.premium.messageTemplateEn)
    }
}
