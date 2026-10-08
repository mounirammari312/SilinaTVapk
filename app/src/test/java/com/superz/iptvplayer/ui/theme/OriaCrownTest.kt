package com.superz.iptvplayer.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * v2.0.5 — THE CROWN REGRESSION GUARDS.
 *
 * The v2.0.4 field report: "ايقونة بريميوم سوداء وهذا غير احترافي نهائيا
 * هي غير ضاهرة نهائيا لانها سوداء". Two distinct failure classes, two
 * guards:
 *
 *  1. PALETTE — the crown's own colors must be GOLD, never black. Guarded
 *     numerically against [CrownMetallic] / [CrownBronzeRelief]: the dark
 *     face is BRIGHT warm gold, the solid-gold face is a clearly DARKER
 *     bronze relief (a real contrast flip, not a repaint).
 *
 *  2. RENDER PATH — Material `Icon()` applies `tint = LocalContentColor`
 *     by default, which REPLACES every fill/stroke the vector carries and
 *     collapsed the crown into one flat dark silhouette. Guarded by a
 *     source scan: the tainted call form (`Icon( imageVector = OriaCrown…`)
 *     must appear NOWHERE in the main source set, and the canonical
 *     renderer [VuCrownIcon] (Image + rememberVectorPainter, NO color
 *     filter) must exist.
 */
class OriaCrownTest {

    // ── 1. the palettes ─────────────────────────────────────────────────

    private fun lum(c: Color) =
        0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    @Test
    fun `metallic palette is bright warm gold - never black`() {
        // The crown reads GOLD: the RAMP (body + band) may never sink to
        // black-silhouette territory (the v2.0.4 field report) and must
        // stay warm. The ramp's darkest stops (bronze foot, band foot) are
        // deep bronze BY DESIGN — they give the metal its depth — so they
        // gate against black, not against dark.
        listOf(
            CrownMetallic.bodyTop, CrownMetallic.bodyMid, CrownMetallic.bodyFoot,
            CrownMetallic.bandTop, CrownMetallic.bandFoot
        ).forEach { c ->
            assertTrue("crown color reads black: $c", lum(c) > 0.25f)
            // warm = red clearly above blue (neutral white/gray would be
            // 1.0; champagne tops ≈ 1.56, rich golds ≈ 2.7+ — 1.4 gates
            // anything cold/blue while letting the lightest champagne pass)
            assertTrue("crown color not warm (gold): $c", c.red > c.blue * 1.4f)
        }
        // the crown's dominant visible area (the body's middle) is LIT gold
        assertTrue("crown body is not lit gold", lum(CrownMetallic.bodyMid) > 0.55f)
        // the jewels are the brightest accents on the icon — near-white
        // champagne BY DESIGN (they only need to gleam, not be warm)
        assertTrue(lum(CrownMetallic.jewel) > 0.85f)
    }

    @Test
    fun `bronze relief is clearly darker than the metallic face`() {
        // the focused-state flip must be a REAL contrast change, else the
        // crown would dissolve into the solid-gold pill face it sits on.
        assertTrue(lum(CrownMetallic.bodyMid) > lum(CrownBronzeRelief.bodyMid) + 0.25f)
        assertTrue(lum(CrownMetallic.bodyTop) > lum(CrownBronzeRelief.bodyTop) + 0.25f)
        // …but the relief must still not be pure black (it sits on GOLD —
        // it needs enough body to read as engraved metal, not a hole).
        assertTrue(lum(CrownBronzeRelief.bodyTop) > 0.20f)
    }

    // ── 2. the vectors ──────────────────────────────────────────────────

    @Test
    fun `both crown vectors build with distinct identities`() {
        assertEquals("OriaCrown", OriaCrown.name)
        assertEquals("OriaCrownOnGold", OriaCrownOnGold.name)
        assertNotEquals(OriaCrown.hashCode(), OriaCrownOnGold.hashCode())
    }

    @Test
    fun `viewport contract is 24dp 24x24`() {
        listOf(OriaCrown, OriaCrownOnGold).forEach { v ->
            assertEquals(24f, v.defaultWidth.value)
            assertEquals(24f, v.defaultHeight.value)
            assertEquals(24f, v.viewportWidth)
            assertEquals(24f, v.viewportHeight)
        }
    }

    // ── 3. the render-path source guard ─────────────────────────────────

    private fun mainSourceRoot(): File? {
        // Gradle's Android unit tests run with the MODULE dir as cwd
        // (iptv-player/app); fall back to the repo layout for IDE runs.
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java")
        )
        return candidates.firstOrNull { it.isDirectory }
    }

    private fun kotlinSources(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no source renders the crown through Material Icon - the tint trap`() {
        val root = mainSourceRoot()
        assumeTrue("main source root not found from cwd — skipping source scan", root != null)
        val sources = kotlinSources(root!!)
        assumeTrue(sources.isNotEmpty())

        // THE v2.0.4 bug form: Icon(imageVector = OriaCrown… / if (…) OriaCrownOnGold else OriaCrown
        // — Icon()'s default tint replaces every vector color with one flat
        // dark fill ("the black crown"). Only the untinted renderer is legal.
        val tainted = sources.filter { f ->
            val text = f.readText()
            text.contains("imageVector = OriaCrown") ||
                text.contains("imageVector = if (focused) OriaCrownOnGold else OriaCrown")
        }
        assertTrue(
            "crown rendered through tinted Icon() in: ${tainted.map { it.name }} — " +
                "draw it with VuCrownIcon (Image + rememberVectorPainter, no colorFilter)",
            tainted.isEmpty()
        )
    }

    @Test
    fun `the canonical renderer exists and attaches no color filter`() {
        val root = mainSourceRoot()
        assumeTrue("main source root not found from cwd — skipping source scan", root != null)
        val crown = kotlinSources(root!!).singleOrNull { it.name == "OriaCrown.kt" }
        assumeTrue(crown != null)
        val text = crown!!.readText()

        assertTrue(
            "VuCrownIcon renderer missing from OriaCrown.kt",
            text.contains("fun VuCrownIcon(")
        )
        assertTrue(
            "VuCrownIcon must paint via rememberVectorPainter",
            text.contains("rememberVectorPainter(if (onGold) OriaCrownOnGold else OriaCrown)")
        )
        // the renderer body must stay filter-free: the Image call between
        // "fun VuCrownIcon" and the file end must not ASSIGN a colorFilter
        // (word mentions in comments are fine — an assignment is not).
        val rendererBody = text.substringAfter("fun VuCrownIcon(")
        assertTrue(
            "VuCrownIcon must not apply a colorFilter — the crown carries its own metal",
            !rendererBody.contains("colorFilter =")
        )
    }
}
