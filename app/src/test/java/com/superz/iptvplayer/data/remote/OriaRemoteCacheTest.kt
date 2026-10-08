package com.superz.iptvplayer.data.remote

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v2.0.4 — THE CONFIG CACHE ROUND-TRIP regression (Robolectric: real
 * SharedPreferences).
 *
 * The v2.0.0–v2.0.3 [OriaRemote] rebuilt the cached JSON by hand (jsonOf)
 * and SILENTLY DROPPED every field added after v2.0.0 — announcement
 * imageUrl/linkUrl/buttonText and the whole v2.0.3 branding block
 * (bgAccountsUrl/bgLoadingUrl/appName/appIconUrl). Consequences on every
 * cold start: the cached announcement signature differed from the live
 * one, so the fresh fetch re-keyed the announcement card away mid-display
 * ("يرمش ويختفي"), and the custom backgrounds flashed stock→custom.
 *
 * v2.0.4 stores the panel's RAW response body verbatim; these tests pin
 * that contract so no future cache format can quietly lose fields again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OriaRemoteCacheTest {

    /** The live panel's config shape (as served 2026-10-07) with EVERY
     *  v2.0.2/v2.0.3 field populated — exactly what fetch() now persists. */
    private val liveSample = """
        {
          "schemaVersion": 1,
          "branding": {
            "accentColor": "#D4AF00",
            "logoUrl": "/api/asset?id=f7fc80c6e9c4f5e310dbc035",
            "bgEntryUrl": "",
            "bgLoginUrl": "https://cdn.example.com/login.jpg",
            "bgAccountsUrl": "/api/asset?id=accbg01",
            "bgLoadingUrl": "/api/asset?id=loadbg02",
            "appName": "ORIA TV",
            "appIconUrl": "/api/asset?id=7001baab14d6d39c3f06f4d8"
          },
          "servers": {
            "qrPageUrl": "https://silinatv-qr-page.vercel.app/",
            "qrApiUrl": "https://silinatv-qr-page.vercel.app/api/qr",
            "codeApiUrl": "https://empreinte-zkfk.onrender.com/app/generate"
          },
          "update": {
            "versionCode": 69, "versionName": "2.0.4",
            "notes": "", "apkUrl": "", "mandatory": false
          },
          "announcement": {
            "enabled": true,
            "title": "محمد علي عبدالل",
            "body": "",
            "imageUrl": "/api/asset?id=c2f466e63d63d1f06ad31f2c",
            "linkUrl": "https://t.me/D200bot",
            "buttonText": "زيارة"
          },
          "premium": {
            "enabled": true,
            "host": "http://darplayer.xyz:8080/",
            "whatsapp": "213550054633",
            "messageTemplate": "السلام عليكم، أريد الاشتراك في خطة {plan}",
            "plans": [
              {"name": "1 شهر", "price": "7", "currency": "$", "durationDays": 30, "popular": true}
            ]
          }
        }
    """.trimIndent()

    private fun context(): Context = RuntimeEnvironment.getApplication()

    @Test
    fun `cache round-trip preserves the v2_0_2 announcement media fields`() {
        OriaRemote.persist(context(), liveSample)
        val fromCache = OriaRemote.readCache(context())
        assertNotNull(fromCache)
        assertEquals("محمد علي عبدالل", fromCache!!.announcement.title)
        assertEquals("/api/asset?id=c2f466e63d63d1f06ad31f2c", fromCache.announcement.imageUrl)
        assertEquals("https://t.me/D200bot", fromCache.announcement.linkUrl)
        assertEquals("زيارة", fromCache.announcement.buttonText)
    }

    @Test
    fun `cache round-trip preserves the v2_0_3 branding block`() {
        OriaRemote.persist(context(), liveSample)
        val fromCache = OriaRemote.readCache(context())!!
        assertEquals("/api/asset?id=accbg01", fromCache.branding.bgAccountsUrl)
        assertEquals("/api/asset?id=loadbg02", fromCache.branding.bgLoadingUrl)
        assertEquals("ORIA TV", fromCache.branding.appName)
        assertEquals("/api/asset?id=7001baab14d6d39c3f06f4d8", fromCache.branding.appIconUrl)
        assertEquals("https://cdn.example.com/login.jpg", fromCache.branding.bgLoginUrl)
        // the premium block survives whole (plans included)
        assertEquals(1, fromCache.premium.plans.size)
        assertEquals("213550054633", fromCache.premium.whatsapp)
    }

    @Test
    fun `cache signature equals the live signature - the flicker regression`() {
        // THE v2.0.3 bug: the cache produced a DIFFERENT announcement
        // signature than the network response, so the fresh fetch re-keyed
        // the showing card and it vanished. The raw-verbatim cache makes
        // the two signatures identical BY CONSTRUCTION.
        OriaRemote.persist(context(), liveSample)
        val live = RemoteConfig.parse(JSONObject(liveSample))
        val cached = OriaRemote.readCache(context())!!
        assertEquals(live.announcement.signature, cached.announcement.signature)
        assertEquals(
            "محمد علي عبدالل||/api/asset?id=c2f466e63d63d1f06ad31f2c|https://t.me/D200bot|زيارة",
            cached.announcement.signature
        )
    }

    @Test
    fun `a fresh install with no cache reads null`() {
        assertNull(OriaRemote.readCache(context()))
    }

    @Test
    fun `a corrupt cache is discarded not fatal`() {
        OriaRemote.persist(context(), "not-json-{")
        assertNull(OriaRemote.readCache(context()))
        // and a valid later write recovers cleanly
        OriaRemote.persist(context(), liveSample)
        assertNotNull(OriaRemote.readCache(context()))
    }
}
