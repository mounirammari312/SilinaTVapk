package com.superz.iptvplayer.ui.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.6 — the account-tier contract (plain JVM).
 *
 * An account is PREMIUM exactly when its stored server IS the live config's
 * premium host — that is precisely how the premium page creates accounts
 * (PremiumScreen → loginXtream(server = premium.host)). Everything else is
 * FREE. The URL comparison must survive the panel's and the app's slightly
 * different spellings of the same host.
 *
 * v2.1.1 — the tier is DATE-AWARE (tierOf): a premium-host account with a
 * passed Xtream exp_date is EXPIRED — the professional subscription state —
 * matching PremiumPolicy.status's activation rule exactly.
 */
class VuAccountTierTest {

    private val host = "http://darplayer.xyz:8080/"   // the panel's live form
    private val now = 1_760_000_000L                  // a fixed paper clock

    @Test
    fun tierLabelsAreLiteralEnglishNeverLocalized() {
        // user directive: "Free" stays "Free" (neon green) even when the app
        // runs in Arabic — same contract for the crowned "Premium" card and
        // the v2.1.1 "Expired" state (the professional apps' own wording).
        assertEquals("Free", VuAccountTier.FREE_LABEL)
        assertEquals("Premium", VuAccountTier.PREMIUM_LABEL)
        assertEquals("Expired", VuAccountTier.EXPIRED_LABEL)
    }

    @Test
    fun exactMatchIsPremium() {
        assertTrue(VuAccountTier.isPremium(host, host))
    }

    @Test
    fun trailingSlashSchemeAndCaseDifferencesStillMatch() {
        // the app may store the normalized "http://darplayer.xyz:8080"
        // (no trailing slash); the panel may carry "HTTP://DARPLAYER.XYZ:8080/"
        assertTrue(VuAccountTier.isPremium("http://darplayer.xyz:8080", host))
        assertTrue(VuAccountTier.isPremium("https://darplayer.xyz:8080/", host))
        assertTrue(VuAccountTier.isPremium("HTTP://DARPLAYER.XYZ:8080/", host))
        assertTrue(VuAccountTier.isPremium("  http://darplayer.xyz:8080/  ", host))
    }

    @Test
    fun differentServerIsFree() {
        assertFalse(VuAccountTier.isPremium("http://other.example.com:8080", host))
        assertFalse(VuAccountTier.isPremium("http://darplayer.xyz:8081", host))
        assertFalse(VuAccountTier.isPremium("darplayer.xyz.evil.com", host))
    }

    @Test
    fun blankHostOrServerIsNeverPremium() {
        // premium disabled / panel never configured → everyone is FREE
        assertFalse(VuAccountTier.isPremium("http://darplayer.xyz:8080", ""))
        assertFalse(VuAccountTier.isPremium("http://darplayer.xyz:8080", "   "))
        assertFalse(VuAccountTier.isPremium(null, host))
        assertFalse(VuAccountTier.isPremium("", host))
        assertFalse(VuAccountTier.isPremium("   ", host))
    }

    // ══════════ v2.1.1 — the date-aware tier decision ══════════

    @Test
    fun premiumHostWithFutureExpiryIsPremium() {
        assertEquals(
            VuAccountTier.Tier.PREMIUM,
            VuAccountTier.tierOf(host, host, now + 86_400L * 30, now)
        )
    }

    @Test
    fun premiumHostWithPassedExpiryIsExpired() {
        // user directive: the crown/account must read NOT-premium once the
        // subscription ends — the professional "Expired" state.
        assertEquals(
            VuAccountTier.Tier.EXPIRED,
            VuAccountTier.tierOf(host, host, now - 1L, now)
        )
    }

    @Test
    fun expiryExactlyNowIsExpired() {
        // the subscription ends AT the expiry instant — same rule as
        // PremiumPolicy.status (active only while exp_date > now).
        assertEquals(
            VuAccountTier.Tier.EXPIRED,
            VuAccountTier.tierOf(host, host, now, now)
        )
    }

    @Test
    fun premiumHostWithUnknownExpiryStaysPremium() {
        // the panel's own locked server may not report an exp_date — an
        // open-ended line keeps the account premium (PremiumPolicy rule).
        assertEquals(
            VuAccountTier.Tier.PREMIUM,
            VuAccountTier.tierOf(host, host, null, now)
        )
    }

    @Test
    fun foreignServerIsFreeWhateverItsDates() {
        assertEquals(
            VuAccountTier.Tier.FREE,
            VuAccountTier.tierOf("http://other.example.com:8080", host, now + 999L, now)
        )
        assertEquals(
            VuAccountTier.Tier.FREE,
            VuAccountTier.tierOf("http://other.example.com:8080", host, now - 999L, now)
        )
        assertEquals(
            VuAccountTier.Tier.FREE,
            VuAccountTier.tierOf(null, host, null, now)
        )
    }

    @Test
    fun tierOfSurvivesHostSpellingDifferences() {
        assertEquals(
            VuAccountTier.Tier.PREMIUM,
            VuAccountTier.tierOf("HTTP://DARPLAYER.XYZ:8080", host, now + 10L, now)
        )
        assertEquals(
            VuAccountTier.Tier.EXPIRED,
            VuAccountTier.tierOf("  https://darplayer.xyz:8080/ ", host, now - 10L, now)
        )
    }
}
