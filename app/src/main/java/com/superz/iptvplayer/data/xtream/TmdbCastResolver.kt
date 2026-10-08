package com.superz.iptvplayer.data.xtream

import com.superz.iptvplayer.IPTVApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v1.4.5 — TMDB cast credits, EXACTLY as the decompiled reference app
 * (VU IPTV Player 1.0.1, vuiptv.player) does it:
 *
 *   XCMovieInfoActivity.setModelInfo():
 *     tmdb_id = info.tmdb_id
 *     if (tmdb_id non-empty) → TMDB /movie/{tmdb_id}/credits?api_key=…
 *   CastRecyclerAdapter:
 *     image = https://image.tmdb.org/t/p/w500 + profile_path
 *
 * v1.4.6 — TMDB_KEY switched to the USER'S OWN account key (their
 * explicit request, live-tested from the sandbox against movie/550
 * and tv/1399 — full cast + profile_paths) replacing the reference
 * app's embedded key.
 *
 * Contract: fully silent. A missing id, a network fault, a blocked API
 * or a weird body simply yields an empty list — the caller then keeps
 * its Wikipedia/name fallbacks. Results cached in memory 30 minutes.
 */
object TmdbCastResolver {

    private const val TMDB_MOVIE_API = "https://api.themoviedb.org/3/movie/"
    private const val TMDB_TV_API = "https://api.themoviedb.org/3/tv/"
    private const val TMDB_IMAGE_PREF = "https://image.tmdb.org/t/p/w500"

    /** The user's own TMDB API key (v1.4.6). */
    const val TMDB_KEY = "65687d1e167bc35f38ee0c88c3a37b74"

    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val MAX_MEMBERS = 10

    /** Which TMDB endpoint family the id belongs to. */
    enum class Kind(val endpoint: String) {
        MOVIE(TMDB_MOVIE_API),
        TV(TMDB_TV_API)
    }

    /** One TMDB cast entry, already resolved to a displayable photo URL. */
    data class Member(
        val name: String,
        val character: String?,
        val photoUrl: String?
    )

    /** (kind, id) → (recordedAt, members) — empty list = known miss. */
    private val cache = HashMap<String, Pair<Long, List<Member>>>()
    private val lock = Any()

    private val http by lazy {
        IPTVApp.get().okHttp.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Resolves the cast credits for a TMDB id. Returns an empty list for
     * blank/zero ids (most series have no tmdb_id on Xtream panels) and
     * for every failure mode — callers fall back to name-based rows.
     */
    suspend fun resolveCredits(tmdbId: String?, kind: Kind): List<Member> {
        val id = tmdbId?.trim()?.takeIf { it.isNotEmpty() && it != "0" } ?: return emptyList()
        val cacheKey = "${kind.name}:$id"

        val now = System.currentTimeMillis()
        synchronized(lock) {
            cache[cacheKey]?.let { (at, members) ->
                if (now - at < CACHE_TTL_MS) return members
            }
        }

        val members = fetch(id, kind)
        synchronized(lock) { cache[cacheKey] = now to members }
        return members
    }

    /** v1.4.6 — URL contract, extracted as a pure builder for tests. */
    internal fun creditsUrl(id: String, kind: Kind): String =
        "${kind.endpoint}$id/credits?api_key=$TMDB_KEY"

    private suspend fun fetch(id: String, kind: Kind): List<Member> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(creditsUrl(id, kind)).get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string() ?: return@withContext emptyList()
                    parse(body)
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

    /** Tolerant parse: cast array → Members (photo-less entries kept). */
    internal fun parse(body: String): List<Member> {
        return try {
            val cast = JSONObject(body).optJSONArray("cast") ?: return emptyList()
            val out = ArrayList<Member>()
            for (i in 0 until cast.length()) {
                if (out.size >= MAX_MEMBERS) break
                val entry = cast.optJSONObject(i) ?: continue
                val name = entry.optString("name", "").trim()
                if (name.isEmpty() || name == "null") continue
                val character = entry.optString("character", "").trim()
                    .takeIf { it.isNotEmpty() && it != "null" }
                val profilePath = entry.optString("profile_path", "").trim()
                    .takeIf { it.isNotEmpty() && it != "null" && it != "false" }
                out.add(
                    Member(
                        name = name,
                        character = character,
                        photoUrl = profilePath?.let { TMDB_IMAGE_PREF + it }
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
