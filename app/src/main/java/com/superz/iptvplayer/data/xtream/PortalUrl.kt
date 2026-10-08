package com.superz.iptvplayer.data.xtream

import java.net.URI

/**
 * Smart portal-URL parser — accepts ANY form a user can paste into either
 * login mode, exactly like the reference app's login engine:
 *
 *  - `http://host:port`                        (plain Xtream base)
 *  - `http://host:port/path/`                  (panel behind a reverse-proxy path)
 *  - `http://host:port/player_api.php`         (± `?username=..&password=..`)
 *  - `http://host:port/get.php?username=..&password=..&type=m3u_plus` (full M3U link)
 *  - `host:port`                               (scheme missing)
 *  - any other M3U http(s) URL whose query carries username/password
 *
 * Produces a clean API base URL (endpoint paths and query string stripped,
 * reverse-proxy path preserved) plus the credentials embedded in the query
 * when present.
 */
object PortalUrl {

    data class Parsed(
        /** Clean Xtream API base: scheme://host[:port][/path] — no query, no endpoint file. */
        val base: String,
        /** Username found in the URL query (URL-decoded), if any. */
        val username: String?,
        /** Password found in the URL query (URL-decoded), if any. */
        val password: String?,
        /** True when the pasted URL pointed at get.php (an M3U link). */
        val hasGetPhp: Boolean,
        /** True when the pasted URL pointed at player_api.php. */
        val hasPlayerApi: Boolean,
        /** Original query string (may carry type=… / output=… needed to re-download). */
        val rawQuery: String?
    )

    fun parse(raw: String): Parsed? {
        var s = raw.trim()
        // Users sometimes paste URLs wrapped in quotes or surrounded by spaces
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) s = s.substring(1, s.length - 1)
        if (s.startsWith("<") && s.endsWith(">") && s.length >= 2) s = s.substring(1, s.length - 1)
        s = s.trim()
        if (s.isEmpty()) return null

        // No scheme → assume http (URI would otherwise parse host=null)
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            if (s.contains("://")) return null            // unknown scheme (ftp:// etc.)
            s = "http://$s"
        }

        return try {
            val uri = URI(s)
            val host = uri.host ?: return null
            if (host.isBlank()) return null
            val port = if (uri.port > 0) ":${uri.port}" else ""

            var path = uri.rawPath ?: ""
            val lower = path.lowercase()
            val hasPlayerApi = lower == "/player_api.php" || lower.endsWith("/player_api.php")
            val hasGetPhp = lower == "/get.php" || lower.endsWith("/get.php")
            // Strip the endpoint file so the base points at the panel root/path
            if (hasPlayerApi) path = path.substring(0, path.length - "/player_api.php".length)
            if (hasGetPhp) path = path.substring(0, path.length - "/get.php".length)
            // Normalize to exactly one leading slash, no trailing slash
            val pathPart = path.trim('/').let { if (it.isEmpty()) "" else "/$it" }

            val base = "${uri.scheme}://$host$port$pathPart"

            var username: String? = null
            var password: String? = null
            val query = uri.rawQuery
            if (!query.isNullOrBlank()) {
                for (param in query.split("&")) {
                    val kv = param.split("=", limit = 2)
                    if (kv.size == 2 && kv[1].isNotEmpty()) {
                        when (kv[0].lowercase()) {
                            "username", "user" -> username = decode(kv[1])
                            "password", "pass" -> password = decode(kv[1])
                        }
                    }
                }
            }
            Parsed(base, username, password, hasGetPhp, hasPlayerApi, query)
        } catch (e: Exception) {
            null
        }
    }

    private fun decode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }
}
