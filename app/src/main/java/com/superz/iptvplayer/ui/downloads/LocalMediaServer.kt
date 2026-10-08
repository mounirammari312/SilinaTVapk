package com.superz.iptvplayer.ui.downloads

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.18.1 — the Saved Videos library's OFFLINE playback bridge.
 *
 * WHY THIS EXISTS: the player's locked engine opens every stream through
 * media3's OkHttpDataSource, which only speaks http/https — a "file://"
 * URI dies instantly. Rather than touching one line of SmartPlayer (iron
 * rule: the hybrid engine is never modified), the library serves its
 * local files over a tiny LOOPBACK HTTP server: the engine then plays
 * "http://127.0.0.1:<port>/<token>/<index>" exactly like any network
 * stream — same chain, same fallback, same seek support, ZERO engine
 * changes (the reference app's own subtitle servers use the same trick).
 *
 * Contract:
 *   • [acquire]/[release] — refcounted lifecycle. The Saved-player session
 *     acquires on bootstrap and releases in PlayerViewModel.onCleared;
 *     the socket + threads only live while somebody is watching.
 *   • [register] — swaps the served file list; returns the URL for each
 *     entry (index-stable for the session that registered them).
 *   • GET only, "/<token>/<index>", HTTP/1.1, Content-Length always,
 *     full Range support (206 + Content-Range) so seeking works.
 *   • BOUND TO 127.0.0.1 — unreachable from any network, plus a random
 *     per-instance token in the path. Only files passed to [register]
 *     are ever served (no directory traversal — the index selects from
 *     the in-memory list, the URL never carries a path).
 */
object LocalMediaServer {

    private const val TAG = "LocalMediaServer"

    @Volatile private var server: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    private val pool by lazy { Executors.newCachedThreadPool { r -> Thread(r, "oria-local-http") } }

    /** Served entries — index in the list == index in the URL. */
    private val files = CopyOnWriteArrayList<File>()

    /** Refcount: the socket lives while ≥1 player session holds it. */
    private val refs = AtomicInteger(0)

    /** Random per-instance path token — blocks cross-app probing. */
    private val token = java.util.UUID.randomUUID().toString().replace("-", "").take(20)

    /** The base URL every registered file hangs off of; "" while stopped. */
    val baseUrl: String
        get() = server?.let { "http://127.0.0.1:${it.localPort}/$token" } ?: ""

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────

    /** Starts the server (first [acquire] only) and returns its base URL. */
    @Synchronized
    fun acquire(): String {
        refs.incrementAndGet()
        if (server == null) {
            try {
                // port 0 → the OS picks a free ephemeral port
                val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
                server = s
                val t = Thread({ acceptLoop(s) }, "oria-local-accept")
                t.isDaemon = true
                t.start()
                acceptThread = t
                Log.i(TAG, "loopback media server up on 127.0.0.1:${s.localPort}")
            } catch (e: Exception) {
                Log.e(TAG, "failed to start: ${e.message}")
                server = null
            }
        }
        return baseUrl
    }

    /** Drops one reference; the LAST release closes the socket. */
    @Synchronized
    fun release() {
        if (refs.decrementAndGet() > 0) return
        refs.set(0) // negative leaks clamp to zero — never kill a live watcher
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        acceptThread = null
        files.clear()
        Log.i(TAG, "loopback media server down")
    }

    /**
     * Swaps the served list and returns one URL per entry (same order).
     * Empty URLs mean the server could not start — the caller shows the
     * playback error instead.
     */
    fun register(list: List<File>): List<String> {
        files.clear()
        files.addAll(list)
        val base = baseUrl
        return List(list.size) { i -> "$base/$i" }
    }

    // ─────────────────────────────────────────────────────────────
    // Serving
    // ─────────────────────────────────────────────────────────────

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val socket = try {
                s.accept()
            } catch (e: Exception) {
                break // closed by release()
            }
            pool.execute { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 20_000
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), 8 * 1024)

            // Request line: "GET /<token>/<index> HTTP/1.1"
            val requestLine = readLine(input) ?: return socket.closeSafe()
            val parts = requestLine.split(" ")
            if (parts.size < 3) return socket.closeSafe()
            val method = parts[0].uppercase()
            val target = parts[1]

            // Headers → Range (the only one the server cares about).
            var range: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0 && line.substring(0, idx).trim().equals("Range", true)) {
                    range = line.substring(idx + 1).trim()
                }
            }

            // Route: /<token>/<index>
            val segs = target.trim('/').split('/')
            if (segs.size != 2 || segs[0] != token) {
                respondError(socket, 404, "Not Found")
                return
            }
            val index = segs[1].toIntOrNull() ?: return respondError(socket, 404, "Not Found")
            val file = files.getOrNull(index) ?: return respondError(socket, 404, "Not Found")
            if (!file.exists() || !file.isFile) {
                return respondError(socket, 404, "Not Found")
            }

            serveFile(socket, file, range, headOnly = method == "HEAD")
        } catch (e: Exception) {
            // player closed mid-transfer — the quiet everyday case
            Log.d(TAG, "connection ended: ${e.message ?: "eof"}")
        } finally {
            socket.closeSafe()
        }
    }

    /** Streams (or HEAD-describes) one file, honoring a single Range. */
    private fun serveFile(socket: Socket, file: File, rangeHeader: String?, headOnly: Boolean) {
        val total = file.length()
        val mime = SavedVideosContract.mimeFor(file.name) ?: "application/octet-stream"

        // "bytes=123-" | "bytes=0-499" | "bytes=-500" (last 500 bytes)
        var start = 0L
        var endInclusive = total - 1
        var partial = false
        val m = Regex("bytes=(\\d*)-(\\d*)").find(rangeHeader ?: "")
        if (m != null && total > 0) {
            val (a, b) = m.destructured
            partial = true
            when {
                a.isEmpty() && b.isNotEmpty() -> {          // suffix range
                    val len = b.toLong().coerceAtMost(total)
                    start = total - len
                    endInclusive = total - 1
                }
                a.isNotEmpty() -> {
                    start = a.toLong().coerceIn(0, total - 1)
                    endInclusive = if (b.isEmpty()) total - 1
                    else b.toLong().coerceAtMost(total - 1)
                }
                else -> partial = false                      // "bytes=-" — ignore
            }
            if (start > endInclusive) {                       // unsatisfiable
                respondError(socket, 416, "Range Not Satisfiable", total)
                return
            }
        }

        val length = endInclusive - start + 1
        val status = if (partial) "206 Partial Content" else "200 OK"
        val out = socket.getOutputStream()
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append("\r\n")
        sb.append("Content-Type: ").append(mime).append("\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        if (partial) {
            sb.append("Content-Range: bytes ").append(start).append('-')
                .append(endInclusive).append('/').append(total).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
        if (headOnly) return

        file.inputStream().use { input ->
            var skip = start
            while (skip > 0) {
                val skipped = input.skip(skip)
                if (skipped <= 0) break
                skip -= skipped
            }
            copy(input, out, length)
        }
        out.flush()
    }

    /** Streams exactly [count] bytes, tolerating a short file tail. */
    private fun copy(input: java.io.InputStream, out: OutputStream, count: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (read < 0) break
            out.write(buf, 0, read)
            remaining -= read
        }
    }

    private fun respondError(socket: Socket, code: Int, text: String, total: Long? = null) {
        try {
            val out = socket.getOutputStream()
            val body = "$code $text\r\n"
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(code).append(' ').append(text).append("\r\n")
            sb.append("Content-Length: ").append(body.length).append("\r\n")
            if (total != null) sb.append("Content-Range: bytes */").append(total).append("\r\n")
            sb.append("Connection: close\r\n\r\n").append(body)
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (_: Exception) {
        } finally {
            socket.closeSafe()
        }
    }

    /** One header/request line (ISO-8859-1, CR/LF tolerated). */
    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun Socket.closeSafe() {
        try {
            close()
        } catch (_: Exception) {
        }
    }
}
