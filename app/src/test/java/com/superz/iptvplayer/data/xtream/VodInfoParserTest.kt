package com.superz.iptvplayer.data.xtream

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VodInfoParser tests (v1.4.0) — plain-JVM, real org.json (same setup as
 * EpgParserTest). Covers the panel-shape variance seen in the wild:
 * nested vs flat movie info, season-array vs episodes-map series shapes,
 * string/number confusion, missing fields, broken JSON.
 */
class VodInfoParserTest {

    // ── get_vod_info ─────────────────────────────────────────────

    @Test
    fun `movie standard shape`() {
        val raw = """
        {
          "info": {
            "duration_secs": 5400,
            "movie_image": "http://poster.jpg",
            "plot": "A plot.",
            "releasedate": "2018-03-13",
            "genre": "Action, Thriller",
            "director": "Ridley Scott",
            "cast": "Actor One, Actor Two",
            "rating": 5
          },
          "movie_data": {
            "stream_id": 12345,
            "name": "EN - Great Movie",
            "container_extension": "mkv"
          }
        }
        """.trimIndent()
        val info = VodInfoParser.parseMovieInfo(raw)!!
        assertEquals("EN - Great Movie", info.name)
        assertEquals("http://poster.jpg", info.poster)
        assertEquals("A plot.", info.plot)
        assertEquals("Action, Thriller", info.genre)
        assertEquals("Ridley Scott", info.director)
        assertEquals("Actor One, Actor Two", info.cast)
        assertEquals("2018-03-13", info.releaseDate)
        assertEquals(12345L, info.streamId)
        assertEquals("mkv", info.containerExtension)
        assertEquals("1h 30m", info.duration)     // 5400 secs formatted
        assertEquals("5", info.rating)            // numeric coerced to string
    }

    @Test
    fun `movie flat shape without movie_data`() {
        val raw = """
        {
          "name": "Flat Movie",
          "stream_id": "777",
          "cover_big": "http://cover.jpg",
          "plot": "Flat plot.",
          "year": "1999"
        }
        """.trimIndent()
        val info = VodInfoParser.parseMovieInfo(raw)!!
        assertEquals("Flat Movie", info.name)
        assertEquals("http://cover.jpg", info.poster)
        assertEquals("1999", info.releaseDate)
        assertEquals(777L, info.streamId)
        assertNull(info.containerExtension)
        assertNull(info.director)
    }

    @Test
    fun `movie with duration string wins over secs`() {
        val raw = """
        { "info": { "duration": "1h 45m", "duration_secs": 6300 }, "movie_data": { "name": "X" } }
        """.trimIndent()
        assertEquals("1h 45m", VodInfoParser.parseMovieInfo(raw)!!.duration)
    }

    @Test
    fun `movie garbage json returns null`() {
        assertNull(VodInfoParser.parseMovieInfo("<html>not json</html>"))
        assertNull(VodInfoParser.parseMovieInfo(""))
    }

    @Test
    fun `movie json with junk prefix is salvaged`() {
        val raw = "junk-prefix{\"info\":{\"plot\":\"P\"},\"movie_data\":{\"name\":\"N\"}}"
        val info = VodInfoParser.parseMovieInfo(raw)!!
        assertEquals("N", info.name)
        assertEquals("P", info.plot)
    }

    // ── get_series_info ──────────────────────────────────────────

    @Test
    fun `series seasons-array shape`() {
        val raw = """
        {
          "info": {
            "name": "Old Christine",
            "cover": "http://cover.jpg",
            "plot": "Single working mom…",
            "genre": "Comedy",
            "cast": "Julia Louis-Dreyfus, Clark Gregg",
            "releaseDate": "2006-03-13"
          },
          "seasons": [
            { "season_number": "1", "episodes": [
              { "id": "501", "episode_num": "2", "title": "Second",
                "container_extension": "mp4",
                "info": { "movie_image": "http://e2.jpg", "plot": "Ep2 plot." } },
              { "id": "500", "episode_num": "1", "title": "First",
                "container_extension": "mp4",
                "info": { "movie_image": "http://e1.jpg" } }
            ] },
            { "season_number": "2", "episodes": [
              { "id": "600", "episode_num": "1", "title": "S2E1" }
            ] }
          ]
        }
        """.trimIndent()
        val info = VodInfoParser.parseSeriesInfo(raw)!!
        assertEquals("Old Christine", info.name)
        assertEquals("http://cover.jpg", info.poster)
        assertEquals("Comedy", info.genre)
        assertEquals("2006-03-13", info.releaseDate)
        assertEquals(2, info.seasons.size)
        // Episodes sorted by episode_num even when listed out of order
        val s1 = info.seasons[0]
        assertEquals(1, s1.seasonNumber)
        assertEquals(2, s1.episodes.size)
        assertEquals(500L, s1.episodes[0].id)
        assertEquals("First", s1.episodes[0].title)
        assertEquals("http://e1.jpg", s1.episodes[0].thumbnail)
        assertEquals(501L, s1.episodes[1].id)
        assertEquals("Ep2 plot.", s1.episodes[1].plot)
        assertEquals(1, info.seasons[1].episodes.size)
    }

    @Test
    fun `series episodes-map shape`() {
        val raw = """
        {
          "info": { "name": "Map Show", "cover": "http://c.jpg" },
          "episodes": {
            "1": [ { "id": 100, "episode_num": 1, "title": "One" } ],
            "2": [ { "id": 200, "episode_num": 1, "title": "Two-1" },
                   { "id": 201, "episode_num": 2, "title": "Two-2" } ]
          }
        }
        """.trimIndent()
        val info = VodInfoParser.parseSeriesInfo(raw)!!
        assertEquals(2, info.seasons.size)
        assertEquals(listOf(1, 2), info.seasons.map { it.seasonNumber })
        assertEquals(1, info.seasons[0].episodes.size)
        assertEquals(2, info.seasons[1].episodes.size)
        // season inferred from the map key
        assertEquals(2, info.seasons[1].episodes[1].season)
        assertEquals("Two-2", info.seasons[1].episodes[1].title)
    }

    @Test
    fun `series both shapes merged and deduped`() {
        val raw = """
        {
          "info": { "name": "Merged" },
          "seasons": [
            { "season_number": 1, "episodes": [ { "id": 1, "episode_num": 1, "title": "FromSeasons" } ] }
          ],
          "episodes": {
            "1": [ { "id": 1, "episode_num": 1, "title": "DUPLICATE-IGNORED" },
                   { "id": 2, "episode_num": 2, "title": "FromMap" } ]
          }
        }
        """.trimIndent()
        val info = VodInfoParser.parseSeriesInfo(raw)!!
        assertEquals(1, info.seasons.size)
        assertEquals(2, info.seasons[0].episodes.size)
        assertEquals("FromSeasons", info.seasons[0].episodes[0].title)
        assertEquals("FromMap", info.seasons[0].episodes[1].title)
    }

    @Test
    fun `series episode missing title falls back`() {
        val raw = """
        { "info": {"name":"T"}, "episodes": { "1": [ { "id": 9, "episode_num": 4 } ] } }
        """.trimIndent()
        val ep = VodInfoParser.parseSeriesInfo(raw)!!.seasons[0].episodes[0]
        assertEquals("Episode 4", ep.title)
        assertEquals(4, ep.episodeNumber)
    }

    @Test
    fun `series episode without id is skipped`() {
        val raw = """
        { "info": {"name":"T"}, "episodes": { "1": [ { "episode_num": 1, "title": "NoId" } ] } }
        """.trimIndent()
        val info = VodInfoParser.parseSeriesInfo(raw)!!
        assertTrue(info.seasons.isEmpty())
    }

    @Test
    fun `series empty and garbage shapes`() {
        // Valid object, no seasons → parsed with empty seasons
        val empty = VodInfoParser.parseSeriesInfo("""{"info":{"name":"Solo"}}""")!!
        assertTrue(empty.seasons.isEmpty())
        assertEquals("Solo", empty.name)
        // Garbage → null
        assertNull(VodInfoParser.parseSeriesInfo("%%%not json%%%"))
    }

    @Test
    fun `series seasons sorted ascending with string numbers`() {
        val raw = """
        {
          "info": {"name":"SortMe"},
          "seasons": [
            { "season_number": "10", "episodes": [ { "id": 1, "episode_num": 1, "title": "A" } ] },
            { "season_number": "2", "episodes": [ { "id": 2, "episode_num": 1, "title": "B" } ] },
            { "season_number": "1", "episodes": [ { "id": 3, "episode_num": 1, "title": "C" } ] }
          ]
        }
        """.trimIndent()
        val seasons = VodInfoParser.parseSeriesInfo(raw)!!.seasons
        assertEquals(listOf(1, 2, 10), seasons.map { it.seasonNumber })
    }

    @Test
    fun `episode duration from inner info object`() {
        val raw = """
        {
          "info": {"name":"D"},
          "episodes": { "1": [ { "id": 5, "episode_num": 1, "title": "E",
            "info": { "duration": "30 mins", "duration_secs": 1800 } } ] }
        }
        """.trimIndent()
        val ep = VodInfoParser.parseSeriesInfo(raw)!!.seasons[0].episodes[0]
        assertEquals("30 mins", ep.duration)
    }

    @Test
    fun `numeric fields as json numbers`() {
        // stream_id / rating as raw numbers, not strings
        val raw = JSONObject()
            .put("info", JSONObject()
                .put("name", "NumMovie")
                .put("rating", 8)
                .put("duration_secs", 3720))
            .put("movie_data", JSONObject()
                .put("stream_id", 999)
                .put("container_extension", "avi"))
            .toString()
        val info = VodInfoParser.parseMovieInfo(raw)!!
        assertEquals(999L, info.streamId)
        assertEquals("8", info.rating)
        assertEquals("1h 2m", info.duration)
        assertEquals("avi", info.containerExtension)
    }

    // ── v1.4.2 additions ──────────────────────────────────────────

    @Test
    fun `series info flat root without info wrapper`() {
        // v1.4.2: some panels put the metadata at the TOP level next to episodes
        val raw = """
        {
          "name": "FlatShow",
          "cover": "https://x/flat.jpg",
          "plot": "Flat plot",
          "genre": "Action",
          "director": "Flat Director",
          "releasedate": "2020-05-05",
          "episodes": { "1": [ { "id": 77, "episode_num": 1, "title": "F1" } ] }
        }
        """.trimIndent()
        val info = VodInfoParser.parseSeriesInfo(raw)!!
        assertEquals("FlatShow", info.name)
        assertEquals("https://x/flat.jpg", info.poster)
        assertEquals("Flat plot", info.plot)
        assertEquals("Action", info.genre)
        assertEquals("Flat Director", info.director)
        assertEquals("2020-05-05", info.releaseDate)
        assertEquals(1, info.seasons.size)
        assertEquals(1, info.seasons[0].episodes.size)
        assertEquals("F1", info.seasons[0].episodes[0].title)
    }

    @Test
    fun `movie duration as plain seconds string is formatted`() {
        // v1.4.2: "5400" in the duration field → "1h 30m", not a raw number
        val raw = JSONObject()
            .put("info", JSONObject()
                .put("name", "SecsMovie")
                .put("duration", "5400"))
            .toString()
        assertEquals("1h 30m", VodInfoParser.parseMovieInfo(raw)!!.duration)
        // "45" minutes only
        val raw2 = JSONObject()
            .put("info", JSONObject().put("duration", "45"))
            .toString()
        assertEquals("<1m", VodInfoParser.parseMovieInfo(raw2)!!.duration)
        // Human strings pass through untouched
        val raw3 = JSONObject()
            .put("info", JSONObject().put("duration", "1h 30m"))
            .toString()
        assertEquals("1h 30m", VodInfoParser.parseMovieInfo(raw3)!!.duration)
    }

    @Test
    fun `movie plot and director fallback keys`() {
        // v1.4.2: description/directors variants used by some panels
        val raw = JSONObject()
            .put("info", JSONObject()
                .put("description", "Desc plot")
                .put("directors", "Alt Director"))
            .toString()
        val info = VodInfoParser.parseMovieInfo(raw)!!
        assertEquals("Desc plot", info.plot)
        assertEquals("Alt Director", info.director)
    }

    // ── v1.4.3 additions ──────────────────────────────────────

    @Test
    fun `movie metadata split across info and movie_data`() {
        // Some XUI variants put stream/name in movie_data but the rest
        // (or everything) under data — every field is now read from BOTH.
        val raw = """
        {
          "info": { "stream_id": 555 },
          "movie_data": {
            "name": "Split Movie",
            "plot": "Split plot.",
            "genre": "Drama",
            "director": "Split Director",
            "cast": "A, B",
            "releasedate": "2024-01-01",
            "rating": "7.5",
            "duration_secs": 6000
          }
        }
        """.trimIndent()
        val info = VodInfoParser.parseMovieInfo(raw)!!
        assertEquals("Split Movie", info.name)
        assertEquals("Split plot.", info.plot)
        assertEquals("Drama", info.genre)
        assertEquals("Split Director", info.director)
        assertEquals("A, B", info.cast)
        assertEquals("2024-01-01", info.releaseDate)
        assertEquals("7.5", info.rating)
        assertEquals("1h 40m", info.duration)
    }

    @Test
    fun `movie info wrapped as array is unwrapped`() {
        val raw = """
        {
          "info": [ { "plot": "Array plot.", "genre": "Sci-Fi", "cast": ["X", "Y"] } ],
          "movie_data": { "name": "Array Movie" }
        }
        """.trimIndent()
        val info = VodInfoParser.parseMovieInfo(raw)!!
        assertEquals("Array Movie", info.name)
        assertEquals("Array plot.", info.plot)
        assertEquals("Sci-Fi", info.genre)
        assertEquals("X, Y", info.cast)   // array cast joined
    }

    @Test
    fun `movie cast as json array is joined with commas`() {
        val raw = JSONObject()
            .put("info", JSONObject()
                .put("name", "M")
                .put("cast", org.json.JSONArray().put("Naomi Baker").put("Jay Reeves")))
            .toString()
        assertEquals("Naomi Baker, Jay Reeves", VodInfoParser.parseMovieInfo(raw)!!.cast)
    }

    @Test
    fun `movie data alias object is read`() {
        val raw = """
        { "data": { "name": "Alias Movie", "plot": "Alias plot." } }
        """.trimIndent()
        val info = VodInfoParser.parseMovieInfo(raw)!!
        assertEquals("Alias Movie", info.name)
        assertEquals("Alias plot.", info.plot)
    }

    @Test
    fun `movie broken json is rescued by regex extraction`() {
        // Trailing comma + unquoted value break JSONObject; the fields
        // are still present and quoted → regex path recovers them.
        val raw = """{ "info": { "plot": "Broken plot.", "genre": "Crime", "cast": "P1, P2", "duration": "1h 30m", "rating": "6.4", }, "movie_data": { "name": "Broken Movie" } }"""
        val info = VodInfoParser.parseMovieInfo(raw)!!
        assertEquals("Broken plot.", info.plot)
        assertEquals("Crime", info.genre)
        assertEquals("P1, P2", info.cast)
        assertEquals("1h 30m", info.duration)
        assertEquals("6.4", info.rating)
    }

    @Test
    fun `movie root json array picks the payload element`() {
        val raw = """
        [ { "junk": 1 }, { "info": { "plot": "Rooted plot." }, "movie_data": { "name": "Root Movie" } } ]
        """.trimIndent()
        val info = VodInfoParser.parseMovieInfo(raw)!!
        assertEquals("Root Movie", info.name)
        assertEquals("Rooted plot.", info.plot)
    }

    @Test
    fun `series broken json is rescued by regex extraction`() {
        val raw = """{ "info": { "name": "Broken Show", "plot": "Series plot.", "cast": "C1, C2", "genre": "Comedy", } }"""
        val info = VodInfoParser.parseSeriesInfo(raw)!!
        assertEquals("Broken Show", info.name)
        assertEquals("Series plot.", info.plot)
        assertEquals("Comedy", info.genre)
        assertEquals("C1, C2", info.cast)
        assertTrue(info.seasons.isEmpty())
    }

    @Test
    fun `garbage body without any field yields null`() {
        assertNull(VodInfoParser.parseMovieInfo("<html><body>403 Forbidden</body></html>"))
        assertNull(VodInfoParser.parseMovieInfo(""))
    }

    // ── v1.4.4 — REAL panel payloads (admagnun.net, user-supplied
    // credentials, captured via VodTrace on-device + re-fetched raw) ──

    /**
     * Real get_series_info body for "AR - ذات (بنت اسمها ذات)"
     * (series_id=56657): standard nested shape, 1 season, 31 episodes,
     * full ffprobe video/audio objects inside every episode's info, and
     * NO movie_image/thumbnail key anywhere in episodes (this panel sends
     * no episode stills — episodes must fall back to the series poster).
     */
    @Test
    fun `real admagnun series info - Zaat parses with all 31 episodes`() {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("series_zaat_real.json")!!
            .readBytes().decodeToString()
        val info = VodInfoParser.parseSeriesInfo(raw)!!
        assertEquals("AR - ذات (بنت اسمها ذات)", info.name)
        assertEquals("دراما", info.genre)
        assertEquals("خيري بشارة", info.director)
        assertTrue(info.cast!!.contains("نيللى كريم"))
        assertEquals("2013-08-02", info.releaseDate)
        assertEquals("8", info.rating)
        assertTrue(info.plot!!.contains("ذات"))
        assertEquals(1, info.seasons.size)
        val s1 = info.seasons[0]
        assertEquals(1, s1.seasonNumber)
        assertEquals(31, s1.episodes.size)
        val e1 = s1.episodes.first { it.id == 1490952L }
        assertEquals(1, e1.episodeNumber)
        assertEquals("mp4", e1.containerExtension)
        assertEquals("00:43:12", e1.duration)
        assertTrue(e1.plot!!.contains("Sonallah Ibrahim"))
        assertEquals(1490953L, s1.episodes[1].id)
        // Episodes ordered by episode_num, ids ascending after that.
        assertEquals((1..31).toList(), s1.episodes.map { it.episodeNumber })
        // This panel sends no episode thumbnails — documented gap.
        assertTrue(s1.episodes.all { it.thumbnail == null })
        // v1.4.4 — the seasons array carries artwork even when it carries
        // no episode arrays (admagnun.net pattern): the season cover is
        // captured and used as the episode-card fallback.
        assertEquals(
            "https://image.tmdb.org/t/p/w600_and_h900_bestv2/427ocgikzJGbhK75is5ApNuaC7j.jpg",
            s1.cover
        )
    }

    @Test
    fun `season covers and episode direct_source are captured`() {
        val raw = """
        {
          "info": { "name": "Show", "plot": "p" },
          "seasons": [
            { "season_number": 1, "cover": "http://s1.jpg" },
            { "season_number": 2, "cover_big": "http://s2big.jpg",
              "episodes": [ { "id": 20, "episode_num": 1, "title": "E1", "container_extension": "mp4" } ] }
          ],
          "episodes": {
            "1": [ { "id": 10, "episode_num": 1, "title": "S1E1",
                     "container_extension": "mp4",
                     "direct_source": "http://panel.direct/10.mkv" } ]
          }
        }
        """.trimIndent()
        val info = VodInfoParser.parseSeriesInfo(raw)!!
        assertEquals(2, info.seasons.size)
        // cover from a season object that carries NO episodes array
        assertEquals("http://s1.jpg", info.seasons[0].cover)
        // cover_big preferred over cover
        assertEquals("http://s2big.jpg", info.seasons[1].cover)
        // absolute direct_source captured for playback
        assertEquals("http://panel.direct/10.mkv", info.seasons[0].episodes[0].directSource)
    }

    @Test
    fun `episode relative direct_source is ignored`() {
        val raw = """
        { "episodes": { "1": [ { "id": 1, "episode_num": 1,
            "direct_source": "/relative/path.mp4" } ] } }
        """.trimIndent()
        val info = VodInfoParser.parseSeriesInfo(raw)!!
        assertNull(info.seasons[0].episodes[0].directSource)
    }

    // ── v1.4.5 — reference-app parity (VU IPTV) ─────────────────

    @Test
    fun `movie tmdb_id is read from info`() {
        val raw = """
        {
          "info": {
            "name": "M", "tmdb_id": "550", "plot": "p"
          },
          "movie_data": { "stream_id": 9, "name": "M" }
        }
        """.trimIndent()
        val info = VodInfoParser.parseMovieInfo(raw)!!
        assertEquals("550", info.tmdbId)
    }

    @Test
    fun `movie tmdb_id falls back to movie_data and drops zero`() {
        val raw = """
        {
          "info": { "name": "A", "tmdb_id": "0", "plot": "p" },
          "movie_data": { "stream_id": 9, "name": "A", "tmdb_id": "27205" }
        }
        """.trimIndent()
        val info = VodInfoParser.parseMovieInfo(raw)!!
        assertEquals("27205", info.tmdbId)

        val zeroBoth = """
        { "info": { "name": "B", "tmdb_id": "0", "plot": "p" } }
        """.trimIndent()
        assertNull(VodInfoParser.parseMovieInfo(zeroBoth)!!.tmdbId)
    }

    @Test
    fun `movie description takes priority over plot (reference parity)`() {
        // The decompiled VU IPTV reads info.description for movies; when the
        // panel fills BOTH, description wins — plot stays the fallback.
        val raw = """
        {
          "info": { "name": "M", "description": "The description.",
                    "plot": "The plot." }
        }
        """.trimIndent()
        val info = VodInfoParser.parseMovieInfo(raw)!!
        assertEquals("The description.", info.plot)
    }

    @Test
    fun `movie plot still used when description is absent`() {
        val raw = """
        { "info": { "name": "M", "plot": "Only plot." } }
        """.trimIndent()
        val info = VodInfoParser.parseMovieInfo(raw)!!
        assertEquals("Only plot.", info.plot)
    }

    @Test
    fun `series tmdb_id is read from info`() {
        val raw = """
        {
          "info": { "name": "S", "tmdb_id": "1399", "plot": "p" },
          "episodes": { "1": [ { "id": 1, "episode_num": 1 } ] }
        }
        """.trimIndent()
        val info = VodInfoParser.parseSeriesInfo(raw)!!
        assertEquals("1399", info.tmdbId)
    }

    @Test
    fun `regex rescue carries tmdb_id and description-first plot`() {
        val raw = "junk{\"info\":{\"tmdb_id\":\"603\",\"description\":\"D\",\"plot\":\"P\"}}"
        val info = VodInfoParser.parseMovieInfo(raw)!!
        assertEquals("603", info.tmdbId)
        assertEquals("D", info.plot)
    }

    // ── v1.4.5 — TmdbCastResolver.parse (pure JVM part) ─────────

    @Test
    fun `tmdb credits parse builds photo urls and caps the row`() {
        val body = """
        {
          "cast": [
            { "name": "Actor One", "character": "Hero",
              "profile_path": "/abc.jpg" },
            { "name": "Actor Two", "character": "",
              "profile_path": null },
            { "name": "", "character": "Ghost", "profile_path": "/x.jpg" },
            { "name": "Actor Three", "profile_path": "/three.jpg" }
          ]
        }
        """.trimIndent()
        val members = TmdbCastResolver.parse(body)
        assertEquals(3, members.size)
        assertEquals("Actor One", members[0].name)
        assertEquals("Hero", members[0].character)
        assertEquals("https://image.tmdb.org/t/p/w500/abc.jpg", members[0].photoUrl)
        assertNull(members[1].character)
        assertNull(members[1].photoUrl)
        assertNull(members[2].character)
    }

    @Test
    fun `tmdb credits parse tolerates garbage`() {
        assertTrue(TmdbCastResolver.parse("not json").isEmpty())
        assertTrue(TmdbCastResolver.parse("""{"cast":[]}""").isEmpty())
        assertTrue(TmdbCastResolver.parse("""{"results":[]}""").isEmpty())
    }
}
