package com.superz.iptvplayer.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * TCP/TLS pre-connector: fires a tiny ranged GET so the connection lands in
 * the shared OkHttp pool. When playback starts moments later, ExoPlayer's
 * OkHttpDataSource reuses the already-warm socket — saving DNS + TCP + TLS
 * round-trips on every zap.
 */
object Preconnector {

    fun warm(okHttp: OkHttpClient, scope: CoroutineScope, urls: Collection<String?>) {
        for (url in urls) {
            if (url == null || !url.startsWith("http", true)) continue
            scope.launch(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("Range", "bytes=0-2047")
                        .build()
                    okHttp.newCall(request).execute().use { response ->
                        // Touch a couple of KB so the socket is fully established,
                        // then return the connection to the keep-alive pool.
                        response.body?.byteStream()?.let { stream ->
                            val buf = ByteArray(2048)
                            stream.read(buf)
                        }
                    }
                } catch (_: Exception) {
                    // Best effort only — never surface preconnect failures.
                }
            }
        }
    }
}
