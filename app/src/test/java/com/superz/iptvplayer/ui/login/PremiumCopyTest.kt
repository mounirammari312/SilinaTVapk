package com.superz.iptvplayer.ui.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.2 — [PremiumCopy]'s WhatsApp deep-link contract (plain JVM).
 *
 * The panel authors the number + template; the app must turn them into a
 * safe wa.me link with the {plan} placeholder filled — never a broken or
 * injected URL (everything is URL-encoded, the number is digit-normalized).
 */
class PremiumCopyTest {

    @Test
    fun buildsTheLinkWithThePlanFilledIn() {
        val url = PremiumCopy.whatsappUrl(
            number = "+213555001122",
            template = "السلام عليكم، أريد الاشتراك في خطة {plan} في تطبيق ORIA",
            planName = "شهر"
        )
        assertTrue(url!!.startsWith("https://wa.me/213555001122?text="))
        // the Arabic message rides URL-encoded; the {plan} token is gone
        assertTrue(!url.contains("{plan}"))
        assertTrue(url.contains("%D8%B4%D9%87%D8%B1"))          // "شهر" percent-encoded
        assertTrue(url.contains("%D8%A7%D9%84%D8%B3%D9%84%D8%A7%D9%85"))  // "السلام"
    }

    @Test
    fun pluslessNumbersAndSpacesAreNormalized() {
        val url = PremiumCopy.whatsappUrl("0550 054 633", "tpl {plan}", "شهر")
        assertEquals("https://wa.me/0550054633?text=tpl%20%D8%B4%D9%87%D8%B1", url)
    }

    @Test
    fun nullPlanDropsThePlaceholderPolitely() {
        val url = PremiumCopy.whatsappUrl("+212600000000", "أريد الاشتراك في {plan}", null)
        assertTrue(url!!.startsWith("https://wa.me/212600000000?text="))
        assertTrue(!url.contains("{plan}"))
        // the double space the replacement leaves behind collapses to one
        assertTrue(!url.contains("%20%20"))
    }

    @Test
    fun unusableNumbersReturnNull() {
        assertNull(PremiumCopy.whatsappUrl("", "tpl", "شهر"))
        assertNull(PremiumCopy.whatsappUrl("12", "tpl", "شهر"))          // < 5 digits
        assertNull(PremiumCopy.whatsappUrl("not-a-number", "tpl", null))
    }

    @Test
    fun emptyTemplateStillYieldsAValidLink() {
        // an empty message must not crash the intent builder — wa.me takes
        // an empty text just fine.
        val url = PremiumCopy.whatsappUrl("+213555001122", "", null)
        assertEquals("https://wa.me/213555001122?text=", url)
    }

    // ── v2.1.3 — THE LANGUAGE-AWARE TEMPLATE ────────────────────────────
    // (user: the WhatsApp message must follow the APP's language — Arabic
    // for Arabic users, English for English users.)

    private val legacy = "السلام عليكم، أريد الاشتراك في خطة {plan} في تطبيق ORIA"
    private val appDefaultEn = "Hello, I would like to subscribe to the {plan} plan in the ORIA app"
    private val appDefaultAr = "السلام عليكم، أريد الاشتراك في خطة {plan} في تطبيق ORIA"

    @Test
    fun arabicAppGetsTheArabicTemplate() {
        assertEquals(
            "مرحبا، أريد خطة {plan}",
            PremiumCopy.templateFor("ar", "مرحبا، أريد خطة {plan}", "Hi", legacy, appDefaultEn)
        )
    }

    @Test
    fun englishAppGetsTheEnglishTemplate() {
        assertEquals(
            "Hi, I want the {plan} plan",
            PremiumCopy.templateFor("en", "مرحبا", "Hi, I want the {plan} plan", legacy, appDefaultEn)
        )
    }

    @Test
    fun arabicSideFallsBackToTheLegacySingleTemplate() {
        // a pre-v2.1.3 panel sends ONLY messageTemplate (Arabic default) —
        // the Arabic app keeps receiving exactly what it received before.
        assertEquals(
            legacy,
            PremiumCopy.templateFor("ar", "", "", legacy, appDefaultEn)
        )
    }

    @Test
    fun englishSideNeverInheritsTheLegacyArabicTemplate() {
        // THE BUG being fixed: an English app on a pre-v2.1.3 panel used to
        // send the Arabic default. The legacy field only ever feeds the
        // Arabic side; the English side falls to the app's own localized
        // default instead.
        assertEquals(
            appDefaultEn,
            PremiumCopy.templateFor("en", "", "", legacy, appDefaultEn)
        )
    }

    @Test
    fun blankEverythingFallsToTheAppDefault() {
        assertEquals(
            appDefaultAr,
            PremiumCopy.templateFor("ar", "", "", "", appDefaultAr)
        )
        assertEquals(
            appDefaultEn,
            PremiumCopy.templateFor("en", "", "", "", appDefaultEn)
        )
    }

    @Test
    fun unknownTagsAreEnglish() {
        // AppLang only ever produces "ar"/"en"; anything else still behaves
        // deterministically (English chain) rather than crashing.
        assertEquals(
            appDefaultEn,
            PremiumCopy.templateFor("fr", "", "", legacy, appDefaultEn)
        )
    }

    @Test
    fun theLanguageAwareTemplateStillFeedsTheUrlBuilder() {
        // end-to-end: an English app with the English panel template gets a
        // wa.me link carrying the ENGLISH percent-encoded message.
        val template = PremiumCopy.templateFor(
            "en", legacy, "Hello, I want the {plan} plan", legacy, appDefaultEn
        )
        val url = PremiumCopy.whatsappUrl("+213555001122", template, "Monthly")
        assertTrue(url!!.startsWith("https://wa.me/213555001122?text="))
        assertTrue(url.contains("Hello%2C%20I%20want%20the%20Monthly%20plan"))
        assertTrue(!url.contains("%D8%A7%D9%84%D8%B3%D9%84%D8%A7%D9%85"))   // no Arabic
    }
}
