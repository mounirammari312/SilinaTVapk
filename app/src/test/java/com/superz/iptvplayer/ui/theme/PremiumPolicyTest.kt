package com.superz.iptvplayer.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 — the premium subscription rules, provable on paper (the
 * PremiumLayoutTest pattern).
 *
 * THE model (user, 2026-10-07): "تعتمد تفعيلها بتاريخ بداية ونهاية حساب
 * xtream بحيث عند تاريخ نهاية حسابة xtream هو تاريخ نهاية نسخة premium"
 * — premium is DATE-BASED: the app is premium while it holds an account on
 * the panel's premium host whose Xtream exp_date is still ahead; the day
 * that account expires, the premium edition ends with it.
 */
class PremiumPolicyTest {

    private val host = "http://darplayer.xyz:8080"
    private val now = 1_800_000_000L   // a fixed "today"

    private fun acc(
        server: String? = host,
        expiry: Long? = now + 30 * 86_400L,
        created: Long = 1_700_000_000_000L,
        name: String = "Premium"
    ) = PremiumPolicy.Account(server = server, expiryDate = expiry, createdAt = created, name = name)

    // ── THE CORE RULE: date-based activation ─────────────────────────

    @Test
    fun premiumAccountWithFutureExpiryIsActive() {
        val st = PremiumPolicy.status(host, listOf(acc()), now)
        assertTrue(st.active)
        assertEquals("Premium", st.sourceName)
        assertEquals(now + 30 * 86_400L, st.expiryDate)
        assertEquals(30, st.daysLeft)
        assertEquals(1_700_000_000_000L, st.memberSince)
    }

    @Test
    fun expiredAccountEndsThePremiumEdition() {
        // THE rule: exp_date reached → premium over, even on the locked host.
        val st = PremiumPolicy.status(host, listOf(acc(expiry = now - 1)), now)
        assertFalse(st.active)
        assertNull(st.daysLeft)               // no "days left" once dead
        assertEquals(now - 1, st.expiryDate)  // the expiry is still reported
    }

    @Test
    fun expiryExactlyNowIsExpired() {
        assertFalse(PremiumPolicy.status(host, listOf(acc(expiry = now)), now).active)
    }

    @Test
    fun nullExpiryMeansNoKnownEnd() {
        // the panel did not report exp_date → the locked host keeps premium
        val st = PremiumPolicy.status(host, listOf(acc(expiry = null)), now)
        assertTrue(st.active)
        assertNull(st.expiryDate)
        assertNull(st.daysLeft)
    }

    @Test
    fun noPremiumHostMeansNeverActive() {
        assertFalse(PremiumPolicy.status(null, listOf(acc()), now).active)
        assertFalse(PremiumPolicy.status("   ", listOf(acc()), now).active)
    }

    @Test
    fun plainAccountsNeverActivatePremium() {
        val free = listOf(
            acc(server = "http://other.xyz:8080"),
            acc(server = null),
            acc(server = "http://free.host/get.php")
        )
        assertFalse(PremiumPolicy.status(host, free, now).active)
    }

    @Test
    fun hostComparisonNormalizesSchemeCaseAndSlash() {
        val variants = listOf(
            "http://darplayer.xyz:8080",
            "https://darplayer.xyz:8080/",
            "DARPLAYER.XYZ:8080",
            " http://Darplayer.xyz:8080/ "
        )
        for (v in variants) {
            assertTrue("variant [$v] must match", PremiumPolicy.sameHost(v, host))
        }
        assertFalse(PremiumPolicy.sameHost(null, host))
        assertFalse(PremiumPolicy.sameHost("http://darplayer.xyz:8081", host))
    }

    @Test
    fun rePurchaseWithLaterExpiryWins() {
        // an old expired line + a fresh purchase → active, and the FRESH
        // expiry is the one reported (a re-purchase replaces the old line).
        val st = PremiumPolicy.status(
            host,
            listOf(acc(expiry = now - 10), acc(expiry = now + 10 * 86_400L)),
            now
        )
        assertTrue(st.active)
        assertEquals(10, st.daysLeft)
    }

    @Test
    fun daysLeftRoundsUpToWholeDays() {
        // 1 second remaining = the last day, not 0 days
        assertEquals(1, PremiumPolicy.status(host, listOf(acc(expiry = now + 1)), now).daysLeft)
        // 25h = 2 days
        assertEquals(2, PremiumPolicy.status(host, listOf(acc(expiry = now + 25 * 3600)), now).daysLeft)
    }

    // ── the free 3-account ceiling ───────────────────────────────────

    @Test
    fun freeUsersAreCappedAtThreeAccounts() {
        assertTrue(PremiumPolicy.accountLimitReached(3, premiumActive = false))
        assertTrue(PremiumPolicy.accountLimitReached(5, premiumActive = false))
        assertFalse(PremiumPolicy.accountLimitReached(2, premiumActive = false))
    }

    @Test
    fun premiumUsersAreUnlimited() {
        assertFalse(PremiumPolicy.accountLimitReached(3, premiumActive = true))
        assertFalse(PremiumPolicy.accountLimitReached(100, premiumActive = true))
    }

    // ── one free trial per feature ───────────────────────────────────

    @Test
    fun everyFeatureGetsExactlyOneFreeUse() {
        assertTrue(PremiumPolicy.trialAvailable(usedTimes = 0))
        assertFalse(PremiumPolicy.trialAvailable(usedTimes = 1))
        assertFalse(PremiumPolicy.trialAvailable(usedTimes = 7))
    }
}
