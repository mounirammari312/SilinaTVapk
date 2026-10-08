package com.superz.iptvplayer.data.net

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * ResilientDoh contract tests — pure JVM (no Robolectric):
 * fallback order, guaranteed last resort, literal-IP fast path,
 * TTL cache, circuit breaker, and the "never worse than today" guarantee.
 */
class ResilientDohTest {

    /** Configurable fake resolver: counts calls, succeeds or throws. */
    private class FakeDns(
        val name: String,
        var addresses: List<InetAddress>? = listOf(
            InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
        ),
        var error: Exception? = null
    ) : Dns {
        var calls = 0
        override fun lookup(hostname: String): List<InetAddress> {
            calls++
            error?.let { throw it }
            return addresses ?: throw UnknownHostException("$name: $hostname")
        }
    }

    /** Deterministic controllable clock. */
    private class FakeClock(var now: Long = 0L) {
        fun fn(): () -> Long = { now }
        fun advance(ms: Long) { now += ms }
    }

    private fun uhe(msg: String = "no address") = UnknownHostException(msg)

    // ── Fallback order ──────────────────────────────────────────

    @Test
    fun `primary succeeds - others never called`() {
        val primary = FakeDns("cf")
        val secondary = FakeDns("google")
        val system = FakeDns("system")
        val dns = ResilientDoh(listOf(primary, secondary), system, clock = { 0 })

        val result = dns.lookup("panel.example")

        assertEquals(1, primary.calls)
        assertEquals(0, secondary.calls)
        assertEquals(0, system.calls)
        assertEquals(primary.addresses, result)
    }

    @Test
    fun `primary fails - secondary takes over`() {
        val primary = FakeDns("cf", error = uhe("endpoint blocked"))
        val secondary = FakeDns("google")
        val system = FakeDns("system")
        val dns = ResilientDoh(listOf(primary, secondary), system, clock = { 0 })

        val result = dns.lookup("panel.example")

        assertEquals(1, primary.calls)
        assertEquals(1, secondary.calls)
        assertEquals(0, system.calls)
        assertEquals(secondary.addresses, result)
    }

    @Test
    fun `both DoH endpoints fail - system DNS is the guaranteed last resort`() {
        val primary = FakeDns("cf", error = uhe("blocked"))
        val secondary = FakeDns("google", error = uhe("blocked"))
        val system = FakeDns("system")
        val dns = ResilientDoh(listOf(primary, secondary), system, clock = { 0 })

        val result = dns.lookup("panel.example")

        assertEquals(1, primary.calls)
        assertEquals(1, secondary.calls)
        assertEquals(1, system.calls)
        assertEquals(system.addresses, result)
    }

    @Test
    fun `everything fails - UnknownHostException propagates like today`() {
        val primary = FakeDns("cf", error = uhe("blocked"))
        val secondary = FakeDns("google", error = uhe("blocked"))
        val system = FakeDns("system", error = uhe("domain does not exist"))
        val dns = ResilientDoh(listOf(primary, secondary), system, clock = { 0 })

        try {
            dns.lookup("nonexistent.example")
            fail("expected UnknownHostException")
        } catch (expected: UnknownHostException) {
            // identical to the pre-DoH behavior
        }
    }

    // ── Private-host rejection flows through ────────────────────

    @Test
    fun `single-label hostnames rejected by DoH resolve via system`() {
        // DoH rejects private/single-label hosts LOCALLY with this exact message.
        val primary = FakeDns("cf", error = UnknownHostException("private hosts not resolved"))
        val secondary = FakeDns("google", error = UnknownHostException("private hosts not resolved"))
        val system = FakeDns("system")
        val dns = ResilientDoh(listOf(primary, secondary), system, clock = { 0 })

        val result = dns.lookup("portal")

        assertEquals(system.addresses, result)
        // Private-host rejections must NOT count as endpoint failures
        // (no network I/O was involved).
        assertEquals(1, primary.calls)
        assertEquals(1, secondary.calls)
    }

    // ── Literal-IP fast path ────────────────────────────────────

    @Test
    fun `literal IPv4 host skips the DoH chain entirely`() {
        val primary = FakeDns("cf")
        val system = FakeDns("system")
        val dns = ResilientDoh(listOf(primary), system, clock = { 0 })

        dns.lookup("5.254.120.3")

        assertEquals(0, primary.calls)
        assertEquals(1, system.calls)
    }

    @Test
    fun `literal IPv6 host skips the DoH chain entirely`() {
        val primary = FakeDns("cf")
        val system = FakeDns("system")
        val dns = ResilientDoh(listOf(primary), system, clock = { 0 })

        dns.lookup("2606:4700:4700::1111")

        assertEquals(0, primary.calls)
        assertEquals(1, system.calls)
    }

    @Test
    fun `normal hostnames are NOT treated as literals`() {
        assertTrue(!ResilientDoh.isLiteralAddress("panel.example.com"))
        assertTrue(!ResilientDoh.isLiteralAddress("example"))
        assertTrue(!ResilientDoh.isLiteralAddress("1.2.3.4.5"))
        assertTrue(!ResilientDoh.isLiteralAddress("1.2.3.999"))
        assertTrue(ResilientDoh.isLiteralAddress("1.2.3.4"))
        assertTrue(ResilientDoh.isLiteralAddress("::1"))
    }

    // ── TTL cache ───────────────────────────────────────────────

    @Test
    fun `cached answers are free within TTL and refreshed after it`() {
        val clock = FakeClock()
        val primary = FakeDns("cf")
        val dns = ResilientDoh(
            listOf(primary), FakeDns("system"),
            clock = clock.fn(), cacheTtlMs = 30_000
        )

        dns.lookup("panel.example")            // t=0 → resolver
        clock.advance(10_000)
        dns.lookup("panel.example")            // t=10s → cache hit
        clock.advance(10_000)
        dns.lookup("panel.example")            // t=20s → cache hit
        assertEquals(1, primary.calls)

        clock.advance(15_000)                  // t=35s → TTL expired
        dns.lookup("panel.example")
        assertEquals(2, primary.calls)         // resolver consulted again
    }

    // ── Circuit breaker ─────────────────────────────────────────

    @Test
    fun `dead endpoint is skipped during cooldown and probed once after it`() {
        val clock = FakeClock()
        val primary = FakeDns("cf", error = uhe("endpoint down"))
        val system = FakeDns("system")
        val dns = ResilientDoh(
            listOf(primary), system,
            clock = clock.fn(), failureThreshold = 3, cooldownMs = 60_000
        )

        dns.lookup("a.example")   // fail 1
        dns.lookup("b.example")   // fail 2
        dns.lookup("c.example")   // fail 3 → breaker OPEN until t+60s
        assertEquals(3, primary.calls)

        clock.advance(10_000)
        dns.lookup("d.example")   // within cooldown → primary skipped
        assertEquals(3, primary.calls)
        assertEquals(4, system.calls)

        clock.advance(50_000)     // t=60s → cooldown expired → ONE probe
        dns.lookup("e.example")
        assertEquals(4, primary.calls)         // the single re-probe
        // probe failed again → breaker re-opens immediately
        clock.advance(10_000)
        dns.lookup("f.example")
        assertEquals(4, primary.calls)         // still cooling → skipped
        assertEquals(6, system.calls)
    }

    @Test
    fun `breaker resets after a successful probe`() {
        val clock = FakeClock()
        val primary = FakeDns("cf", error = uhe("flaky"))
        val system = FakeDns("system")
        val dns = ResilientDoh(
            listOf(primary), system,
            clock = clock.fn(), failureThreshold = 3, cooldownMs = 60_000
        )

        repeat(3) { dns.lookup("x$it.example") }  // breaker opens
        clock.advance(60_000)

        primary.error = null                     // endpoint recovered
        primary.addresses = listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 7)))
        dns.lookup("recovered.example")          // probe succeeds
        assertEquals(4, primary.calls)

        primary.error = uhe("single blip")       // ONE new failure…
        dns.lookup("blip.example")
        assertEquals(5, primary.calls)
        dns.lookup("blip2.example")              // …must NOT re-open the breaker
        assertEquals(6, primary.calls)           // (below threshold again)
    }
}
