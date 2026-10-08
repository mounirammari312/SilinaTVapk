package com.superz.iptvplayer.data.xtream

import com.superz.iptvplayer.IPTVApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v1.4.3 — cast headshots for the movie/series info pages.
 *
 * Reference apps (e.g. VU) show a horizontal cast row with round photos;
 * Xtream panels only send the cast NAMES. This resolver enriches names
 * with thumbnail URLs from the Wikipedia pageimages API — no API key,
 * generous rate limits, fast CDN.
 *
 * Contract: silent degradation everywhere. Unknown names, network
 * failure or a blocked wiki simply leave the photo map without that
 * entry — the UI falls back to initials avatars. Results are cached in
 * memory for 30 minutes (per session).
 */
object CastPhotoResolver {

    private const val ENDPOINT = "https://en.wikipedia.org/w/api.php"
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val MAX_NAMES = 10

    /** name -> (recordedAt, url?) — null URL = known miss (cached). */
    private val cache = HashMap<String, Pair<Long, String?>>()
    private val lock = Any()

    private val http by lazy {
        IPTVApp.get().okHttp.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** Resolves photos for the given names (cached hits first, then one batched API call). */
    suspend fun resolve(names: List<String>): Map<String, String> {
        val wanted = names.take(MAX_NAMES)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (wanted.isEmpty()) return emptyMap()

        val out = HashMap<String, String>()
        val toFetch = ArrayList<String>()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            for (n in wanted) {
                val hit = cache[n]
                if (hit != null && now - hit.first < CACHE_TTL_MS) {
                    hit.second?.let { out[n] = it }
                } else {
                    toFetch.add(n)
                }
            }
        }
        if (toFetch.isEmpty()) return out

        val fetched = batchFetch(toFetch)
        synchronized(lock) {
            for (n in toFetch) {
                val url = fetched[n]
                cache[n] = now to url
                if (url != null) out[n] = url
            }
        }
        return out
    }

    private suspend fun batchFetch(names: List<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            try {
                val titles = names.joinToString("|") {
                    java.net.URLEncoder.encode(it, "UTF-8")
                }
                val url = "$ENDPOINT?action=query&format=json&prop=pageimages" +
                    "&piprop=thumbnail&pithumbsize=240&pilimit=50&redirects=1&titles=$titles"
                val request = Request.Builder().url(url).get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyMap()
                    val body = response.body?.string() ?: return@withContext emptyMap()
                    parseThumbnails(body, names)
                }
            } catch (e: Exception) {
                emptyMap()
            }
        }

    /** Maps requested names to thumbnails, following normalize/redirect aliases. */
    private fun parseThumbnails(body: String, requested: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val root = JSONObject(body)
            val query = root.optJSONObject("query") ?: return out

            val alias = HashMap<String, String>()
            query.optJSONArray("normalized")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    alias[o.optString("from")] = o.optString("to")
                }
            }
            query.optJSONArray("redirects")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    alias[o.optString("from")] = o.optString("to")
                }
            }
            val byTitle = HashMap<String, String>()
            query.optJSONObject("pages")?.let { pages ->
                for (key in pages.keys()) {
                    val p = pages.optJSONObject(key) ?: continue
                    val thumb = p.optJSONObject("thumbnail")?.optString("source") ?: continue
                    if (thumb.isNotBlank()) byTitle[p.optString("title")] = thumb
                }
            }
            fun resolveTitle(n: String): String {
                var t = n
                var hops = 0
                while (alias.containsKey(t) && hops < 4) {
                    t = alias[t] ?: break
                    hops++
                }
                return t
            }
            for (n in requested) {
                byTitle[resolveTitle(n)]?.let { out[n] = it }
            }
        } catch (e: Exception) {
            // silent — initials avatars remain
        }
        return out
    }
}
