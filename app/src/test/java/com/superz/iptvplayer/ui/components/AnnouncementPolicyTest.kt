package com.superz.iptvplayer.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.4 — THE ANNOUNCEMENT SHOW POLICY's contract (pure JVM).
 *
 * The v2.0.3 bug this suite locks out: the card's visibility was keyed on
 * the announcement signature with a persisted seen-marker, so a config
 * refresh whose signature differed from the cached one (the hand-built
 * cache JSON dropped the v2.0.2+ fields!) re-evaluated `visible` to false
 * WHILE THE CARD WAS ON SCREEN — the announcement appeared, blinked, and
 * vanished on every app entry.
 *
 * The policy now has, by construction, NO path that hides a showing card:
 * [AnnouncementPolicy.arms] returns false whenever `isShowing` is true,
 * whatever the config does.
 */
class AnnouncementPolicyTest {

    @Test
    fun armsWhenEnabledAndFreshWhileClosed() {
        assertTrue(
            AnnouncementPolicy.arms(
                enabled = true, signature = "t|b|img|link|btn",
                armedSignature = "", isShowing = false
            )
        )
    }

    @Test
    fun neverArmsWhileShowing_evenIfEverythingElseWould() {
        // THE anti-flicker contract: while the card is on screen, no config
        // state — enabled, disabled, same signature, different signature —
        // can arm (and therefore re-key / hide / re-open) anything.
        assertFalse(
            AnnouncementPolicy.arms(
                enabled = true, signature = "new-sig",
                armedSignature = "old-sig", isShowing = true
            )
        )
        assertFalse(
            AnnouncementPolicy.arms(
                enabled = false, signature = "",
                armedSignature = "", isShowing = true
            )
        )
    }

    @Test
    fun armsOnlyOncePerSignaturePerEntry() {
        // after arming, the same signature never re-arms (dismissal is final
        // for the session — professional ad cards do not nag)
        assertFalse(
            AnnouncementPolicy.arms(
                enabled = true, signature = "sig",
                armedSignature = "sig", isShowing = false
            )
        )
    }

    @Test
    fun reArmsOnlyWhenContentChangesWhileClosed() {
        // a panel edit re-arms a CLOSED card (the next entry / a later
        // refresh shows the new content) — but never an open one
        assertTrue(
            AnnouncementPolicy.arms(
                enabled = true, signature = "new-content",
                armedSignature = "old-content", isShowing = false
            )
        )
    }

    @Test
    fun disabledOrBlankSignatureNeverArms() {
        assertFalse(
            AnnouncementPolicy.arms(
                enabled = false, signature = "sig",
                armedSignature = "", isShowing = false
            )
        )
        assertFalse(
            AnnouncementPolicy.arms(
                enabled = true, signature = "",
                armedSignature = "", isShowing = false
            )
        )
        assertFalse(
            AnnouncementPolicy.arms(
                enabled = true, signature = "   ",
                armedSignature = "", isShowing = false
            )
        )
    }
}
