package com.superz.iptvplayer.data.xtream

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * v1.4.4 — network-level retry contract for [withNetRetries].
 *
 * Field evidence: the admagnun.net panel transiently RESETS single
 * connections (aggressive firewall); one dead request must not fail a
 * whole info fetch or VOD sync when a retry one second later succeeds.
 */
class XtreamClientRetryTest {

    @Test
    fun `network fault retries then succeeds`() = runBlocking {
        var calls = 0
        val result = withNetRetries(attempts = 3, backoffsMs = listOf(0L, 0L)) {
            calls++
            if (calls < 3) throw IOException("connection reset")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun `xtream exception is definitive - no retry`() = runBlocking {
        var calls = 0
        try {
            withNetRetries(attempts = 3, backoffsMs = listOf(0L, 0L)) {
                calls++
                throw XtreamException("HTTP_404")
            }
            fail("should have thrown XtreamException")
        } catch (e: XtreamException) {
            assertEquals("HTTP_404", e.message)
        }
        assertEquals(1, calls)
    }

    @Test
    fun `persistent network fault exhausts attempts and rethrows last`() = runBlocking {
        var calls = 0
        try {
            withNetRetries(attempts = 3, backoffsMs = listOf(0L, 0L)) {
                calls++
                throw IOException("reset")
            }
            fail("should have thrown IOException")
        } catch (e: IOException) {
            assertEquals("reset", e.message)
        }
        assertEquals(3, calls)
    }

    @Test
    fun `first attempt success makes exactly one call`() = runBlocking {
        var calls = 0
        val result = withNetRetries(attempts = 3, backoffsMs = listOf(0L, 0L)) {
            calls++
            "immediate"
        }
        assertEquals("immediate", result)
        assertEquals(1, calls)
    }
}
