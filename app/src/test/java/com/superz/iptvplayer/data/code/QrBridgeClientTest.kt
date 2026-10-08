package com.superz.iptvplayer.data.code

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64

/**
 * v1.14.1 — the QR login bridge engine's contract (SMART CONNECT, copied
 * from the reference app; the dead Supabase backend was swapped for our
 * MongoDB-backed /api/qr in the user's own Vercel project). Robolectric
 * gives the engine its real Android pieces (Bitmap for the zxing output,
 * android.util.Base64 for the payload decode) so the ENGINE runs
 * unmodified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrBridgeClientTest {

    // ── Constants — the bridge points at the user's OWN Vercel page ───────

    @Test
    fun `vercel qr base is the user s hosted page`() {
        assertEquals("https://silinatv-qr-page.vercel.app/", QrBridgeClient.VERCEL_QR_BASE)
    }

    @Test
    fun `bridge api base is the function on the same domain`() {
        assertEquals(
            "https://silinatv-qr-page.vercel.app/api/qr",
            QrBridgeClient.BRIDGE_API_BASE
        )
    }

    @Test
    fun `qr url embeds the tv code as the page s code param`() {
        assertEquals(
            "https://silinatv-qr-page.vercel.app/?code=123456",
            QrBridgeClient.qrUrlFor("123456")
        )
    }

    @Test
    fun `poll url keeps the engine s eq filter shape`() {
        assertEquals(
            "https://silinatv-qr-page.vercel.app/api/qr?tv_code=eq.123456",
            QrBridgeClient.pollUrlFor("123456")
        )
    }

    @Test
    fun `delete url matches the poll url s code filter`() {
        assertEquals(
            QrBridgeClient.pollUrlFor("654321"),
            QrBridgeClient.deleteUrlFor("654321")
        )
        assertEquals(
            "https://silinatv-qr-page.vercel.app/api/qr?tv_code=eq.654321",
            QrBridgeClient.deleteUrlFor("654321")
        )
    }

    // ── Payload decoding — the Vercel page's Base64(JSON) format ──────────

    /** Encodes exactly like the page: btoa(unescape(encodeURIComponent(json))). */
    private fun pageEncode(json: String): String =
        Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    @Test
    fun `xtream payload decodes with all fields`() {
        val json = """
            {"mode":"xtream","profile":"Living Room",
             "host":"http://panel.tv:8080","user":"u1","pass":"p1","m3u":""}
        """.trimIndent()
        val payload = QrBridgeClient.QrPayload.fromBase64(pageEncode(json))!!
        assertTrue(payload.isXtream)
        assertEquals(false, payload.isM3u)
        assertEquals("Living Room", payload.profile)
        assertEquals("http://panel.tv:8080", payload.host)
        assertEquals("u1", payload.user)
        assertEquals("p1", payload.pass)
        assertEquals("", payload.m3u)
    }

    @Test
    fun `m3u payload decodes with empty xtream fields`() {
        val json = """
            {"mode":"m3u","profile":"Salon","host":"","user":"","pass":"",
             "m3u":"http://x.tv/get.php?username=a&password=b&type=m3u_plus"}
        """.trimIndent()
        val payload = QrBridgeClient.QrPayload.fromBase64(pageEncode(json))!!
        assertTrue(payload.isM3u)
        assertEquals(false, payload.isXtream)
        assertEquals("Salon", payload.profile)
        assertEquals("http://x.tv/get.php?username=a&password=b&type=m3u_plus", payload.m3u)
    }

    @Test
    fun `arabic profile names survive the base64 round trip`() {
        val json = """{"mode":"xtream","profile":"غرفة الجلوس","host":"http://p.tv","user":"مستخدم","pass":"سر","m3u":""}"""
        val payload = QrBridgeClient.QrPayload.fromBase64(pageEncode(json))!!
        assertEquals("غرفة الجلوس", payload.profile)
        assertEquals("مستخدم", payload.user)
        assertEquals("سر", payload.pass)
    }

    @Test
    fun `unknown mode is neither xtream nor m3u`() {
        val json = """{"mode":"weird","profile":"","host":"","user":"","pass":"","m3u":""}"""
        val payload = QrBridgeClient.QrPayload.fromBase64(pageEncode(json))!!
        assertEquals(false, payload.isXtream)
        assertEquals(false, payload.isM3u)
    }

    @Test
    fun `garbage base64 decodes to null not a crash`() {
        assertNull(QrBridgeClient.QrPayload.fromBase64("!!!not-base64!!!"))
        assertNull(QrBridgeClient.decodeBase64Json(""))
        assertNull(QrBridgeClient.decodeBase64Json("aGVsbG8"))  // "hello", not JSON
    }

    // ── QR generation — zxing engine on a real (Robolectric) Bitmap ────────

    @Test
    fun `qr bitmap encodes the url as a scannable square`() {
        val bmp = QrBridgeClient.generateQrBitmap(QrBridgeClient.qrUrlFor("654321"), 256)
        assertEquals(256, bmp!!.width)
        assertEquals(256, bmp.height)
        assertEquals(Bitmap.Config.RGB_565, bmp.config)
        // A real QR matrix is mostly white with black modules — both present.
        var black = 0
        var white = 0
        for (x in 0 until 256) {
            for (y in 0 until 256) {
                if (bmp.getPixel(x, y) == android.graphics.Color.BLACK) black++ else white++
            }
        }
        assertTrue("QR must contain black modules", black > 0)
        assertTrue("QR must contain white modules", white > 0)
    }

    @Test
    fun `different tv codes produce different qr bitmaps`() {
        val a = QrBridgeClient.generateQrBitmap(QrBridgeClient.qrUrlFor("111111"), 128)
        val b = QrBridgeClient.generateQrBitmap(QrBridgeClient.qrUrlFor("222222"), 128)
        var diff = 0
        for (x in 0 until 128) {
            for (y in 0 until 128) {
                if (a!!.getPixel(x, y) != b!!.getPixel(x, y)) diff++
            }
        }
        assertTrue("different codes must produce different QRs", diff > 0)
    }

    @Test
    fun `bad input to qr generation returns null not a crash`() {
        // Negative size forces Bitmap.createBitmap to throw — the engine's
        // catch returns null instead of crashing the login screen.
        assertNull(QrBridgeClient.generateQrBitmap("data", -5))
        assertNotEquals(null, QrBridgeClient.generateQrBitmap("data", 64))
    }
}
