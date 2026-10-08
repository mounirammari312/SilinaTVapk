package com.superz.iptvplayer.ui.theme

import com.superz.iptvplayer.data.remote.RemoteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * v2.0.0 — the live branding state: hex parsing, announcement projection
 * and the dynamic gold family's re-derivation (plain JVM — snapshot state
 * is readable outside composition).
 */
class OriaBrandingTest {

    @Test
    fun parseHexAcceptsOnlyStrictRRGGBB() {
        assertNotNull(OriaBranding.parseHex("#D4AF37"))
        assertNotNull(OriaBranding.parseHex("#00aaff"))
        // missing #, 3/8-digit, garbage, named colors → null (keep default)
        assertNull(OriaBranding.parseHex("D4AF37"))
        assertNull(OriaBranding.parseHex("#FFF"))
        assertNull(OriaBranding.parseHex("#FFAA00CC"))
        assertNull(OriaBranding.parseHex("yellow"))
        assertNull(OriaBranding.parseHex(""))
    }

    @Test
    fun applyConfigProjectsBrandingAndAnnouncementState() {
        val cfg = RemoteConfig(
            branding = RemoteConfig.Branding(
                accentColor = "#00AAFF",
                logoUrl = "/api/asset/logo.png",
                bgGlobalUrl = "/api/asset/global.jpg",
                bgEntryUrl = "https://cdn.example.com/e.jpg",
                bgLoginUrl = "https://cdn.example.com/l.jpg"
            ),
            announcement = RemoteConfig.Announcement(
                enabled = true, title = "عنوان", body = "نص",
                imageUrl = "/api/asset/ad.jpg", linkUrl = "https://shop.example.com",
                buttonText = "زيارة"
            )
        )
        OriaBranding.applyConfig(cfg)
        assertEquals("/api/asset/logo.png", OriaBranding.logoUrl)
        // v2.2.0 — the ONE global background projects into Compose state
        assertEquals("/api/asset/global.jpg", OriaBranding.bgGlobalUrl)
        assertEquals("https://cdn.example.com/e.jpg", OriaBranding.bgEntryUrl)
        assertEquals("https://cdn.example.com/l.jpg", OriaBranding.bgLoginUrl)
        assertEquals(true, OriaBranding.announcementEnabled)
        assertEquals("عنوان", OriaBranding.announcementTitle)
        assertEquals("نص", OriaBranding.announcementBody)
        // v2.0.2 — the ad card's parts project alongside the text
        assertEquals("/api/asset/ad.jpg", OriaBranding.announcementImageUrl)
        assertEquals("https://shop.example.com", OriaBranding.announcementLinkUrl)
        assertEquals("زيارة", OriaBranding.announcementButtonText)
        assertEquals("عنوان|نص|/api/asset/ad.jpg|https://shop.example.com|زيارة",
            OriaBranding.announcementSignature)

        // a disabled announcement blanks every slot
        OriaBranding.applyConfig(RemoteConfig())
        assertEquals(false, OriaBranding.announcementEnabled)
        // v2.2.0 — a default config blanks the global background too
        assertEquals("", OriaBranding.bgGlobalUrl)
        assertEquals("", OriaBranding.announcementTitle)
        assertEquals("", OriaBranding.announcementBody)
        assertEquals("", OriaBranding.announcementImageUrl)
        assertEquals("", OriaBranding.announcementLinkUrl)
        assertEquals("", OriaBranding.announcementButtonText)
        assertEquals("", OriaBranding.announcementSignature)
    }

    @Test
    fun applyConfigProjectsV203BrandingState() {
        // v2.0.3 — the accounts/loading backgrounds, the white-label app
        // name and the launcher-icon slot all project into live state.
        OriaBranding.applyConfig(
            RemoteConfig(
                branding = RemoteConfig.Branding(
                    bgAccountsUrl = "/api/asset/a.jpg",
                    bgLoadingUrl = "https://cdn.example.com/l.jpg",
                    appName = "ORIA TV",
                    appIconUrl = "/api/asset/icon.png"
                )
            )
        )
        assertEquals("/api/asset/a.jpg", OriaBranding.bgAccountsUrl)
        assertEquals("https://cdn.example.com/l.jpg", OriaBranding.bgLoadingUrl)
        assertEquals("ORIA TV", OriaBranding.appName)
        assertEquals("/api/asset/icon.png", OriaBranding.appIconUrl)

        // a default config blanks every slot again
        OriaBranding.applyConfig(RemoteConfig())
        assertEquals("", OriaBranding.bgAccountsUrl)
        assertEquals("", OriaBranding.bgLoadingUrl)
        assertEquals("", OriaBranding.appName)
        assertEquals("", OriaBranding.appIconUrl)
    }

    @Test
    fun premiumGateRequiresEnabledAndHost() {
        // v2.0.2 — the Phase-2 entry banner shows only when the panel BOTH
        // enables premium AND carries a real host.
        OriaBranding.applyConfig(
            RemoteConfig(premium = RemoteConfig.Premium(enabled = true, host = "http://darplayer.xyz:8080/"))
        )
        assertEquals(true, OriaBranding.premiumEnabled)

        OriaBranding.applyConfig(
            RemoteConfig(premium = RemoteConfig.Premium(enabled = true, host = ""))
        )
        assertEquals(false, OriaBranding.premiumEnabled)

        OriaBranding.applyConfig(
            RemoteConfig(premium = RemoteConfig.Premium(enabled = false, host = "http://x.example.com"))
        )
        assertEquals(false, OriaBranding.premiumEnabled)
    }

    @Test
    fun applyConfigProjectsV206EntryButtonState() {
        // v2.0.6 — the appearance tab's button controls project into the
        // live state the entry/accounts/settings pages read.
        OriaBranding.applyConfig(
            RemoteConfig(
                entry = RemoteConfig.Entry(
                    upgradeVisible = false, upgradeText = "Go GOLD",
                    codeVisible = false, codeText = "كود 6 أرقام"
                )
            )
        )
        assertEquals(false, OriaBranding.premiumCtaVisible)
        assertEquals("Go GOLD", OriaBranding.premiumCtaText)
        assertEquals(false, OriaBranding.codeLoginVisible)
        assertEquals("كود 6 أرقام", OriaBranding.codeLoginText)

        // a default config restores the built-in buttons everywhere
        OriaBranding.applyConfig(RemoteConfig())
        assertEquals(true, OriaBranding.premiumCtaVisible)
        assertEquals("", OriaBranding.premiumCtaText)
        assertEquals(true, OriaBranding.codeLoginVisible)
        assertEquals("", OriaBranding.codeLoginText)
    }

    @Test
    fun classicGoldRestoresTheHandTunedFamily() {
        // #D4AF37 IS the shipped metallic gold → the hand-tuned 9-member
        // family must be restored byte-for-byte, not re-derived.
        OriaBranding.applyConfig(RemoteConfig())
        assertEquals(androidx.compose.ui.graphics.Color(0xFFD4AF37), VuGold.Gold)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFFEDA6), VuGold.Pale)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF8A6415), VuGold.Deep)
    }

    @Test
    fun customAccentReDerivesAFamilyAroundIt() {
        OriaBranding.applyConfig(
            RemoteConfig(branding = RemoteConfig.Branding(accentColor = "#00AAFF"))
        )
        assertEquals(androidx.compose.ui.graphics.Color(0xFF00AAFF), VuGold.Gold)
        // the family shifts around the new base — sane relations preserved
        // (restore the classic gold afterwards for other tests in the suite)
        OriaBranding.applyConfig(RemoteConfig())
        assertEquals(androidx.compose.ui.graphics.Color(0xFFD4AF37), VuGold.Gold)
    }

    @Test
    fun remoteUrlsResolveAgainstTheGateway() {
        assertNull(resolveRemoteUrl(""))
        assertNull(resolveRemoteUrl("javascript:alert(1)"))
        assertEquals(
            "https://oria-admin-nine.vercel.app/api/asset/x.png",
            resolveRemoteUrl("/api/asset/x.png")
        )
        assertEquals(
            "https://cdn.example.com/a.png",
            resolveRemoteUrl("https://cdn.example.com/a.png")
        )
    }
}
