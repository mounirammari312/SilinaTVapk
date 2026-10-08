package com.superz.iptvplayer.data.m3u

import com.superz.iptvplayer.data.db.Category
import com.superz.iptvplayer.data.db.Channel
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.security.MessageDigest

/**
 * Streaming M3U / m3u_plus parser.
 *
 * Handles: #EXTINF attributes (tvg-logo, group-title, tvg-id), display names
 * (including commas inside quoted attributes), #EXTGRP blocks, and BOM.
 * Falls back to windows-1256 when the file is not valid UTF-8 — common for
 * Arabic IPTV portals.
 */
object M3UParser {

    data class Result(val categories: List<Category>, val channels: List<Channel>)

    private val ATTR_REGEX = Regex("""([a-zA-Z0-9\-_]+)\s*=\s*"([^"]*)"""")

    fun parse(
        bytes: ByteArray,
        playlistId: Long,
        onProgress: (Int) -> Unit = {}
    ): Result {
        val charset = detectCharset(bytes)
        val stream = ByteArrayInputStream(bytes)
        return parse(stream, playlistId, charset, onProgress)
    }

    fun parse(
        input: InputStream,
        playlistId: Long,
        charset: Charset = Charsets.UTF_8,
        onProgress: (Int) -> Unit = {}
    ): Result {
        val reader = input.bufferedReader(charset)
        val categories = LinkedHashMap<String, String>()   // id -> name
        val channels = ArrayList<Channel>()
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroupTitle: String? = null
        var extGroup: String? = null
        var num = 0

        reader.forEachLine { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line == "#EXTM3U" || line.startsWith("#EXTVLCOPT") -> { /* skip */ }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    // Split attributes and display name (name = after the comma that
                    // follows the last quoted attribute, or after the duration).
                    val attrEnd = line.lastIndexOf('"')
                    val commaIdx = line.indexOf(',', if (attrEnd > 0) attrEnd else 8)
                    val attrBlock = if (commaIdx > 0) line.substring(0, commaIdx) else line
                    pendingName = if (commaIdx > 0) line.substring(commaIdx + 1).trim() else null
                    pendingLogo = null
                    pendingGroupTitle = null
                    for (m in ATTR_REGEX.findAll(attrBlock)) {
                        when (m.groupValues[1].lowercase()) {
                            "tvg-logo" -> pendingLogo = m.groupValues[2].trim().ifEmpty { null }
                            "group-title" -> pendingGroupTitle = m.groupValues[2].trim().ifEmpty { null }
                        }
                    }
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    extGroup = line.substring(8).trim().ifEmpty { null }
                }

                line.startsWith("#") -> { /* unknown directive — ignore */ }

                else -> {
                    // A URL line: emit the pending channel
                    val name = pendingName?.takeIf { it.isNotBlank() } ?: extractHost(line)
                    val groupTitle = (pendingGroupTitle ?: extGroup)?.takeIf { it.isNotBlank() }
                    val categoryId = groupTitle?.let { categoryIdFor(it) }
                    if (groupTitle != null && categoryId != null) {
                        if (!categories.containsKey(categoryId)) categories[categoryId] = groupTitle
                    }
                    channels.add(
                        Channel(
                            playlistId = playlistId,
                            key = "u:" + md5(line),
                            num = ++num,
                            name = name,
                            logo = pendingLogo,
                            categoryId = categoryId,
                            streamId = null,
                            directUrl = line
                        )
                    )
                    pendingName = null
                    pendingLogo = null
                    pendingGroupTitle = null
                    if (channels.size % 200 == 0) onProgress(channels.size)
                }
            }
        }

        val categoryRows = categories.map { (id, name) -> Category(playlistId = playlistId, categoryId = id, name = name) }
        return Result(categoryRows, channels)
    }

    private fun extractHost(url: String): String = try {
        val noScheme = url.substringAfter("://", url)
        noScheme.substringBefore('/').take(40)
    } catch (e: Exception) {
        "Channel"
    }

    private fun categoryIdFor(group: String): String = "g:" + md5(group.lowercase())

    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** UTF-8 strict check with windows-1256 fallback (Arabic portals). */
    private fun detectCharset(bytes: ByteArray): Charset {
        // Skip BOM
        val start = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> 3
            else -> 0
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes, start, bytes.size - start))
            Charsets.UTF_8
        } catch (e: Exception) {
            try {
                Charset.forName("windows-1256")
            } catch (e2: Exception) {
                Charsets.ISO_8859_1
            }
        }
    }
}
