package com.superz.iptvplayer.data.subtitles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * v1.17.0 — the subtitle engine's contract. Pure-function tests:
 * name cleaning, episode markers, language mapping, the public service
 * endpoints, and the three fix surfaces of this version:
 *   • charset transcoding (CP1256 "؟؟؟؟" → UTF-8),
 *   • content validation (captcha/HTML pages can never become subtitles),
 *   • the charset candidate ladder.
 */
class SubtitleRepositoryTest {

    // ── The public endpoints — the reference's services ─────────────────

    @Test
    fun `cinemeta base url is the reference stremio catalog`() {
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/movie/top/search=",
            SubtitleRepository.SUBTITLE_CINEMETA_BASE_URL
        )
    }

    @Test
    fun `cinemeta series base url exists for episode lookups`() {
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/series/top/search=",
            SubtitleRepository.SUBTITLE_CINEMETA_SERIES_BASE_URL
        )
    }

    @Test
    fun `opensubtitles rest base url is the reference legacy api`() {
        assertEquals(
            "https://rest.opensubtitles.org/search/",
            SubtitleRepository.OPENSUBTITLES_REST_BASE_URL
        )
    }

    @Test
    fun `stremio addon base url is the reference v3 add-on`() {
        assertEquals(
            "https://opensubtitles-v3.strem.io/subtitles/movie/",
            SubtitleRepository.STREMIO_OPENSUBTITRES_BASE_URL
        )
    }

    // ── extractCleanMovieName — the fuzzy-name pipeline ──────────────────

    @Test
    fun `quality tags and year are stripped from the name`() {
        assertEquals(
            "Inception",
            SubtitleRepository.extractCleanMovieName("Inception 2010 1080p BluRay")
        )
    }

    @Test
    fun `bracket content is removed`() {
        assertEquals(
            "The Matrix",
            SubtitleRepository.extractCleanMovieName("The Matrix (1999) [HD]")
        )
    }

    @Test
    fun `multiple tags collapse into single spaces`() {
        assertEquals(
            "Interstellar",
            SubtitleRepository.extractCleanMovieName("Interstellar   4K   UHD   HEVC")
        )
    }

    @Test
    fun `space separated tags and year vanish completely`() {
        assertEquals(
            "Avatar",
            SubtitleRepository.extractCleanMovieName("Avatar 2009 1080p WEB-DL")
        )
    }

    @Test
    fun `v1 17 0 dotted release names are normalized to spaces`() {
        // The v1.15 engine left "Movie Name WEB DL" style artifacts and
        // searched Cinemeta with the dotted string.
        assertEquals(
            "The Nun II",
            SubtitleRepository.extractCleanMovieName("The.Nun.II.2023.1080p.WEB-DL")
        )
    }

    @Test
    fun `v1 17 0 HDR survives the HD tag`() {
        // The v1.15 tag order let "HD" eat the inside of "HDR" → " R".
        assertEquals(
            "Oppenheimer",
            SubtitleRepository.extractCleanMovieName("Oppenheimer HDR")
        )
    }

    @Test
    fun `arabic names survive the pipeline`() {
        assertEquals(
            "فيلم النمر",
            SubtitleRepository.extractCleanMovieName("فيلم النمر HD 1080P")
        )
    }

    @Test
    fun `a clean name passes through untouched`() {
        assertEquals("Coco", SubtitleRepository.extractCleanMovieName("Coco"))
    }

    // ── parseEpisodeMarker — series routing (v1.17.0) ────────────────────

    @Test
    fun `sxe pattern is parsed into series season episode`() {
        val m = SubtitleRepository.parseEpisodeMarker("Breaking Bad S01E05 1080p")
        assertNotNull(m)
        assertEquals("Breaking Bad", m!!.seriesName)
        assertEquals(1, m.season)
        assertEquals(5, m.episode)
    }

    @Test
    fun `x pattern is parsed into series season episode`() {
        val m = SubtitleRepository.parseEpisodeMarker("Friends 2x08")
        assertNotNull(m)
        assertEquals("Friends", m!!.seriesName)
        assertEquals(2, m.season)
        assertEquals(8, m.episode)
    }

    @Test
    fun `dotted episode names are parsed`() {
        val m = SubtitleRepository.parseEpisodeMarker("Money.Heist.S03E07.1080p.WEB-DL")
        assertNotNull(m)
        assertEquals("Money Heist", m!!.seriesName)
        assertEquals(3, m.season)
        assertEquals(7, m.episode)
    }

    @Test
    fun `plain movie names have no episode marker`() {
        assertNull(SubtitleRepository.parseEpisodeMarker("The Nun II"))
        assertNull(SubtitleRepository.parseEpisodeMarker("Inception 2010"))
    }

    // ── stripQualityTags — the lowercase fallback variant ─────────────────

    @Test
    fun `strip quality tags lowercases the result`() {
        assertEquals(
            "django unchained",
            SubtitleRepository.stripQualityTags("Django Unchained 2012 BluRay")
        )
    }

    // ── mapLanguageCode — ISO 639-1 → OpenSubtitles 3-letter codes ────────

    @Test
    fun `iso codes map to opensubtitles codes`() {
        assertEquals("ara", SubtitleRepository.mapLanguageCode("ar"))
        assertEquals("eng", SubtitleRepository.mapLanguageCode("en"))
        assertEquals("fre", SubtitleRepository.mapLanguageCode("fr"))
        assertEquals("spa", SubtitleRepository.mapLanguageCode("es"))
        assertEquals("tur", SubtitleRepository.mapLanguageCode("tr"))
    }

    @Test
    fun `english language names map too`() {
        assertEquals("ara", SubtitleRepository.mapLanguageCode("arabic"))
        assertEquals("eng", SubtitleRepository.mapLanguageCode("english"))
        assertEquals("ger", SubtitleRepository.mapLanguageCode("german"))
    }

    @Test
    fun `three letter codes pass through mapped`() {
        assertEquals("ara", SubtitleRepository.mapLanguageCode("ara"))
        assertEquals("eng", SubtitleRepository.mapLanguageCode("ENG".lowercase()))
    }

    @Test
    fun `unknown codes fall back to the input`() {
        assertEquals("xx", SubtitleRepository.mapLanguageCode("xx"))
    }

    // ── transcodeToUtf8 — the CP1256 "؟؟؟؟" fix (v1.17.0) ─────────────────

    /** "ترجمة" in Windows-1256 — the exact bytes found in the wild. */
    private val cp1256Sample = byteArrayOf(
        0xCA.toByte(), 0xD1.toByte(), 0xCC.toByte(), 0xE3.toByte(), 0xC9.toByte()
    )

    @Test
    fun `cp1256 arabic bytes are transcoded to utf8 arabic`() {
        val out = SubtitleRepository.transcodeToUtf8(cp1256Sample, declaredEncoding = "CP1256", languageCode = "ar")
        assertEquals("ترجمة", String(out, StandardCharsets.UTF_8))
    }

    @Test
    fun `cp1256 detected by language default without declared encoding`() {
        val out = SubtitleRepository.transcodeToUtf8(cp1256Sample, declaredEncoding = null, languageCode = "ar")
        assertEquals("ترجمة", String(out, StandardCharsets.UTF_8))
    }

    @Test
    fun `cp1256 detected by the generic ladder without any hints`() {
        val out = SubtitleRepository.transcodeToUtf8(cp1256Sample, declaredEncoding = null, languageCode = null)
        assertEquals("ترجمة", String(out, StandardCharsets.UTF_8))
    }

    @Test
    fun `valid utf8 passes through untouched`() {
        val utf8 = "مترجم إلى العربية".toByteArray(StandardCharsets.UTF_8)
        val out = SubtitleRepository.transcodeToUtf8(utf8, declaredEncoding = null, languageCode = "ar")
        assertArrayEquals(utf8, out)
    }

    @Test
    fun `utf8 bom is stripped`() {
        val body = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا".toByteArray(StandardCharsets.UTF_8)
        val withBom = ByteArray(3 + body.size)
        withBom[0] = 0xEF.toByte(); withBom[1] = 0xBB.toByte(); withBom[2] = 0xBF.toByte()
        System.arraycopy(body, 0, withBom, 3, body.size)
        val out = SubtitleRepository.transcodeToUtf8(withBom, null, null)
        assertEquals(String(body, StandardCharsets.UTF_8), String(out, StandardCharsets.UTF_8))
    }

    @Test
    fun `utf16 le bom is converted to utf8`() {
        val text = "ترجمة"
        val utf16 = text.toByteArray(StandardCharsets.UTF_16LE) // includes no BOM
        val withBom = ByteArray(2 + utf16.size)
        withBom[0] = 0xFF.toByte(); withBom[1] = 0xFE.toByte()
        System.arraycopy(utf16, 0, withBom, 2, utf16.size)
        val out = SubtitleRepository.transcodeToUtf8(withBom, null, null)
        assertEquals(text, String(out, StandardCharsets.UTF_8))
    }

    @Test
    fun `utf16 be bom is converted to utf8`() {
        val text = "ترجمة"
        val utf16 = text.toByteArray(StandardCharsets.UTF_16BE)
        val withBom = ByteArray(2 + utf16.size)
        withBom[0] = 0xFE.toByte(); withBom[1] = 0xFF.toByte()
        System.arraycopy(utf16, 0, withBom, 2, utf16.size)
        val out = SubtitleRepository.transcodeToUtf8(withBom, null, null)
        assertEquals(text, String(out, StandardCharsets.UTF_8))
    }

    @Test
    fun `ascii subtitle content is valid utf8 already`() {
        val out = SubtitleRepository.transcodeToUtf8(
            "1\n00:00:01,000 --> 00:00:02,000\nHello".toByteArray(), null, null
        )
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nHello", String(out, StandardCharsets.UTF_8))
    }

    @Test
    fun `encoding candidates start with the declared encoding`() {
        val ladder = SubtitleRepository.encodingCandidates("CP1256", "en")
        assertEquals("CP1256", ladder.first())
        assertTrue(ladder.contains("windows-1256"))
        assertTrue(ladder.contains("windows-1252"))
    }

    @Test
    fun `encoding candidates use the language default for arabic`() {
        val ladder = SubtitleRepository.encodingCandidates(null, "ar")
        assertEquals("windows-1256", ladder.first())
    }

    @Test
    fun `utf8 declared encoding is skipped in the ladder`() {
        val ladder = SubtitleRepository.encodingCandidates("UTF-8", "ar")
        assertFalse(ladder.contains("UTF-8"))
    }

    // ── looksLikeSubtitle — the captcha-page guard (v1.17.0) ─────────────

    @Test
    fun `a valid srt is recognized`() {
        assertTrue(SubtitleRepository.looksLikeSubtitle("1\n00:00:01,000 --> 00:00:02,000\nترجمة"))
    }

    @Test
    fun `a vtt is recognized`() {
        assertTrue(SubtitleRepository.looksLikeSubtitle("WEBVTT\n\n00:01.000 --> 00:02.000\nhi"))
    }

    @Test
    fun `an html captcha page is rejected`() {
        assertFalse(SubtitleRepository.looksLikeSubtitle("<html><body>captcha</body></html>"))
    }

    @Test
    fun `an empty body is rejected`() {
        assertFalse(SubtitleRepository.looksLikeSubtitle(""))
    }

    // ── v1.18.0 — the wrong-movie fix (three layers) ────────────────────

    @Test
    fun `v1 18 0 year is extracted from releasedate strings`() {
        assertEquals(2021, SubtitleRepository.extractYear("2021-10-22"))
        assertEquals(1999, SubtitleRepository.extractYear("1999"))
        assertEquals(2023, SubtitleRepository.extractYear("The.Nun.II.2023.1080p"))
        assertNull(SubtitleRepository.extractYear("no year here"))
        assertNull(SubtitleRepository.extractYear(null))
    }

    @Test
    fun `v1 18 0 exact name scores the maximum`() {
        assertEquals(100, SubtitleRepository.scoreMeta("Dune", null, "Dune", null))
    }

    @Test
    fun `v1 18 0 matching year adds its bonus`() {
        assertEquals(130, SubtitleRepository.scoreMeta("Dune", 2021, "Dune", 2021))
        // ±1 year still counts as a match (panel years drift)
        assertEquals(130, SubtitleRepository.scoreMeta("Dune", 2021, "Dune", 2020))
    }

    @Test
    fun `v1 18 0 a remake with the same title loses on year mismatch`() {
        // The 1984 Dune must NOT beat the 2021 one when the query says 2021.
        val right = SubtitleRepository.scoreMeta("Dune", 2021, "Dune", 2021)
        val wrong = SubtitleRepository.scoreMeta("Dune", 1984, "Dune", 2021)
        assertTrue(right > wrong)
        assertEquals(60, wrong) // 100 - 40
    }

    @Test
    fun `v1 18 0 unknown meta year is neutral`() {
        assertEquals(100, SubtitleRepository.scoreMeta("Dune", null, "Dune", 2021))
    }

    @Test
    fun `v1 18 0 normalization ignores case and punctuation`() {
        assertEquals(100, SubtitleRepository.scoreMeta("dune!", null, " DUNE ", null))
        assertEquals(100, SubtitleRepository.scoreMeta("النمر", null, "النمر", null))
    }

    @Test
    fun `v1 18 0 pickBestMeta prefers the right year over search order`() {
        // Search results ordered like Cinemeta's popular-first list: the
        // 1984 remake first, the 2021 film second. With year=2021 the
        // engine must pick the SECOND (index 1), not the first.
        val metas = org.json.JSONArray()
            .put(org.json.JSONObject().put("id", "tt0087182").put("name", "Dune").put("year", "1984"))
            .put(org.json.JSONObject().put("id", "tt1160419").put("name", "Dune").put("year", "2021"))
        val best = SubtitleRepository.pickBestMeta(metas, "Dune", 2021)
        assertNotNull(best)
        assertEquals("tt1160419", best!!.first.optString("id"))
        assertEquals("Dune (2021)", best.second)
    }

    @Test
    fun `v1 18 0 pickBestMeta falls back to the first result when nothing scores`() {
        val metas = org.json.JSONArray()
            .put(org.json.JSONObject().put("id", "tt1").put("name", "Unrelated"))
            .put(org.json.JSONObject().put("id", "tt2").put("name", "Also Unrelated"))
        val best = SubtitleRepository.pickBestMeta(metas, "Dune", 2021)
        assertNotNull(best)
        assertEquals("tt1", best!!.first.optString("id"))
    }

    @Test
    fun `v1 18 0 pickBestMeta reads releaseInfo when year is absent`() {
        val metas = org.json.JSONArray()
            .put(org.json.JSONObject().put("id", "tt1").put("name", "Dune").put("releaseInfo", "2021–2024"))
        val best = SubtitleRepository.pickBestMeta(metas, "Dune", 2021)
        assertNotNull(best)
        assertEquals("Dune (2021)", best!!.second)
    }

    @Test
    fun `v1 18 0 stremio series addon path exists`() {
        assertEquals(
            "https://opensubtitles-v3.strem.io/subtitles/series/",
            SubtitleRepository.STREMIO_OPENSUBTITRES_SERIES_BASE_URL
        )
    }

    @Test
    fun `v1 18 0 cinemeta meta endpoint exists`() {
        assertEquals(
            "https://v3-cinemeta.strem.io/meta/",
            SubtitleRepository.SUBTITLE_CINEMETA_META_BASE_URL
        )
    }
}
