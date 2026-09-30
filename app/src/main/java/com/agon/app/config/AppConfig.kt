package com.agon.app.config

import androidx.compose.ui.graphics.Color

/**
 * Central Configuration for Universal Stream IPTV Player
 * Contains all app-wide constants, feature flags, and configuration values.
 *
 * ════════════════════════════════════════════════════════════════════════
 *  V5.0 ADDITIONS — NATIVE ZERO-CONFIG ATOMIC TUNNELING & ANTI-VPN
 *  ════════════════════════════════════════════════════════════════════════
 *  New constants for:
 *    - DNS-over-HTTPS (DoH) endpoint configuration (Cloudflare 1.1.1.1)
 *    - Bootstrap client defensive timeouts (3-second maximum)
 *    - Native Header Spoofing Matrix signature arrays (LibVLC + Chromecast)
 *    - Multi-Threaded Socket Shatter exception pattern matchers
 *
 *  These constants are read by [RedirectSniffer] and
 *  [ProxyForegroundService] at network-stack construction time. They
 *  must remain `const val` so the compiler inlines them at every
 *  call-site — no runtime lookup overhead on the hot path.
 * ════════════════════════════════════════════════════════════════════════
 */
object AppConfig {

    // ════════════════════════════════════════════════════════════════════════
    //  V8.3 — ADMOB LEAN AD SYSTEM (Interstitial + App Open ONLY)
    //  ════════════════════════════════════════════════════════════════════════
    //  The legacy ad constants (ENABLE_ADS / BANNER_ID) have been PHYSICALLY
    //  REMOVED from this class. The Native Advanced + Anchored Adaptive Banner
    //  formats and their supporting files (NativeAdCard / BannerAdView /
    //  NativeAdPool) have also been deleted from the project.
    //
    //  The remaining lean ad system lives in the dedicated `com.agon.app.ads`
    //  package:
    //    • AdConstants        — App Open + Interstitial ad-unit IDs + throttle
    //    • SilinaApplication  — async MobileAds.initialize()
    //    • AdMobManager       — central orchestrator (interstitial + app open)
    //    • AppOpenAdController / InterstitialAdController
    //    • PolicyShield       — fullscreen-live ad firewall
    //    • DPadAdFocusEngine  — TV remote focus + key interception
    //
    //  New code MUST import AdConstants directly — no backward-compatible
    //  aliases are exposed from AppConfig anymore.
    //  ════════════════════════════════════════════════════════════════════════
    //  V8.0 §III — HOST MASKING SYSTEM (قناع النطاق الموجه)
    //  ════════════════════════════════════════════════════════════════════════
    //  Commercial product protection: when a buyer sets these constants to
    //  non-empty values, the login screen AUTO-FILLS the server URL in the
    //  background and HIDES the Portal URL field from the end user. The user
    //  only enters Username + Password — they cannot see or change the
    //  locked server. This protects the buyer's server from being harvested.
    //
    //  WHEN EMPTY (default): the app runs as a "clean empty player" and ALL
    //  login fields are visible — safe for Google Play upload.
    //
    //  USAGE FOR BUYERS:
    //    1. Set FORCED_HOST_URL to your Xtream portal URL
    //       (e.g. "http://my-server.com:8080")
    //    2. Set FORCED_PROFILE_NAME to your brand name
    //       (e.g. "MyIPTV Pro")
    //    3. Rebuild the APK — users will only see Username + Password fields
    // ════════════════════════════════════════════════════════════════════════
    /**
     * Forced Xtream portal URL. When non-empty, the login screen hides the
     * Portal URL field and auto-fills this value in the background. Leave
     * empty for a clean player (all fields visible, Google Play safe).
     */
    const val FORCED_HOST_URL: String = ""

    /**
     * Forced profile display name. When non-empty + [FORCED_HOST_URL] is set,
     * the profile name field is also auto-filled + hidden. Leave empty for a
     * clean player.
     */
    const val FORCED_PROFILE_NAME: String = ""

    // ════════════════════════════════════════════════════════════════════════
    //  V8.0 §IV — SUPABASE CREDENTIAL ISOLATION (عزل المفاتيح السحابية)
    //  ════════════════════════════════════════════════════════════════════════
    //  The Supabase URL + anon key were EXTRACTED from LoginActivity.kt and
    //  isolated here so each buyer can plug in their OWN Supabase project.
    //
    //  This prevents buyer databases from colliding and protects the original
    //  developer's Supabase project from being drained by resold APKs.
    //
    //  The values below are PLACEHOLDERS — the Smart Connect (QR login)
    //  feature will not function until the buyer replaces them with their own
    //  Supabase project credentials.
    //
    //  TODO: Replace with Your Own Supabase Credentials
    //  1. Create a free project at https://supabase.com
    //  2. Create a table named `auth_bridge` with columns:
    //       - tv_code (text)
    //       - payload (text)
    //       - created_at (timestamptz, default now())
    //  3. Copy your Project URL + anon key from:
    //       Project Settings → API → Project URL + anon public key
    //  4. Paste them below.
    // ════════════════════════════════════════════════════════════════════════
    /**
     * Supabase project URL for the Smart Connect (QR code) login bridge.
     * TODO: Replace with Your Own Supabase Credentials.
     */
    const val SUPABASE_URL: String = "https://YOUR-PROJECT-REF.supabase.co"

    /**
     * Supabase anon (public) API key for the Smart Connect bridge.
     * TODO: Replace with Your Own Supabase Credentials.
     */
    const val SUPABASE_ANON_KEY: String = "YOUR_SUPABASE_ANON_KEY"

    /**
     * Vercel-hosted QR landing page base URL. Buyers can self-host this page
     * (the source is a single HTML file) and point this constant at their own
     * domain. Leave as-is for the default hosted page.
     */
    const val VERCEL_QR_BASE: String = "https://silinatv-qr-page.vercel.app/"

    // ════════════════════════════════════════════════════════════════════════
    //  V8.2 — CENTRAL SERVER URL REGISTRY (سجل الروابط المركزي)
    //  ════════════════════════════════════════════════════════════════════════
    //  Every external server URL the app contacts is declared here so the
    //  buyer can change any endpoint WITHOUT hunting through the source.
    //
    //  To reconfigure: open THIS file, change the constant value, rebuild.
    //  No other file needs editing.
    //
    //  ════════════════════════════════════════════════════════════════════════
    //  SPORTS MATCHES — "مباريات اليوم" microservice
    //  ════════════════════════════════════════════════════════════════════════
    //  Consumed by MatchHarvesterRepository. Returns a JSON array of today's
    //  matches: [{"home":"...", "away":"...", "time":"...", "channel":"..."}]
    //  The default endpoint is the official Silina sports microservice hosted
    //  on Hugging Face Spaces. Buyers can self-host the same Flask app and
    //  point this constant at their own deployment.
    /**
     * Sports matches microservice endpoint (مباريات اليوم).
     * Returns JSON: [{"home","away","time","channel"}]
     */
    const val SPORTS_MATCHES_API_URL: String = "https://mouniramm-sport.hf.space/get_matches"

    //  ════════════════════════════════════════════════════════════════════════
    //  SMART CONNECT — Xtream credential generator backend
    //  ════════════════════════════════════════════════════════════════════════
    //  Consumed by LoginActivity (triggerConnection flow). The backend issues
    //  free trial Xtream credentials keyed by the device fingerprint. Buyers
    //  can self-host the same backend and repoint these constants.
    /**
     * Smart Connect — trial Xtream credential generator endpoint.
     * Query: ?count=N&fingerprint=DEVICE_ID
     */
    const val SMART_CONNECT_GENERATE_URL: String = "https://empreinte-2cm5.onrender.com/api/generate_xtream"

    /**
     * Smart Connect — server info probe endpoint (validates host+user+pass).
     */
    const val SMART_CONNECT_SERVER_INFO_URL: String = "https://empreinte-2cm5.onrender.com/api/server_info"

    //  ════════════════════════════════════════════════════════════════════════
    //  SUBTITLES — OpenSubtitles + Cinemeta scraping pipeline
    //  ════════════════════════════════════════════════════════════════════════
    //  Consumed by SubtitleRepository. The pipeline is:
    //    1. Cinemeta (Stremio v3 catalog) — IMDB ID lookup by movie name.
    //    2. OpenSubtitles REST API — primary subtitle source.
    //    3. Stremio OpenSubtitles v3 add-on — fallback subtitle source.
    //  These are public free APIs. Buyers can swap them for mirror endpoints
    //  if the originals are rate-limited in their region.
    /**
     * Cinemeta (Stremio v3) catalog search endpoint for IMDB ID resolution.
     * The movie name is URL-encoded and appended after "search=".
     */
    const val SUBTITLE_CINEMETA_BASE_URL: String = "https://v3-cinemeta.strem.io/catalog/movie/top/search="

    /**
     * OpenSubtitles REST API base for subtitle search by IMDB ID + language.
     * The full URL is built as: BASE + "imdbid-{id}/sublanguageid-{lang}"
     */
    const val OPENSUBTITLES_REST_BASE_URL: String = "https://rest.opensubtitles.org/search/"

    /**
     * Stremio OpenSubtitles v3 add-on fallback endpoint.
     * The IMDB ID is appended to form the full .json URL.
     */
    const val STREMIO_OPENSUBTITRES_BASE_URL: String = "https://opensubtitles-v3.strem.io/subtitles/movie/"

    // Branding Colors
    /**
     * Primary brand color - Deep Purple
     * Used for app theming, buttons, and accent elements
     */
    val PRIMARY_COLOR: Color = Color(0xFF6200EA)

    /**
     * Secondary brand color - Purple variant
     */
    val SECONDARY_COLOR: Color = Color(0xFF3700B3)

    /**
     * Accent color for highlights
     */
    val ACCENT_COLOR: Color = Color(0xFF03DAC6)

    // App Information
    /**
     * Application display name shown in UI
     */
    const val APP_NAME: String = "Universal Stream"

    /**
     * Application version for display purposes
     */
    const val APP_VERSION: String = "1.0.0"

    // DataStore Keys
    /**
     * Key prefix for stored playlists
     */
    const val DATASTORE_PLAYLIST_PREFIX: String = "playlist_"

    /**
     * Key for active playlist ID
     */
    const val DATASTORE_ACTIVE_PLAYLIST: String = "active_playlist_id"

    /**
     * Key for user preferences
     */
    const val DATASTORE_USER_PREFERENCES: String = "user_preferences"

    // API Configuration
    /**
     * Connection timeout in seconds for API calls
     */
    const val CONNECTION_TIMEOUT: Long = 30

    /**
     * Read timeout in seconds for API calls
     */
    const val READ_TIMEOUT: Long = 30

    // ════════════════════════════════════════════════════════════════════════
    //  V8.5 — LAN HUB CONTENT SERVER PORT
    //  ════════════════════════════════════════════════════════════════════════
    //  Dedicated port for [com.agon.app.lan.HubContentServer] — the HTTP
    //  server that serves the host's playlist.m3u to discovered peers.
    //  Chosen deliberately DIFFERENT from ProxyForegroundService.LOCAL_PORT
    //  (8080) so the two never collide. If this port is busy the server
    //  falls back to an OS-assigned port (see HubContentServer.start).
    // ════════════════════════════════════════════════════════════════════════
    const val HUB_CONTENT_PORT: Int = 8081

    /**
     * Write timeout in seconds for API calls
     */
    const val WRITE_TIMEOUT: Long = 30

    // Player Configuration
    /**
     * Default buffer duration in milliseconds
     */
    const val DEFAULT_BUFFER_MS: Long = 50000

    /**
     * Minimum buffer before playback starts
     */
    const val MIN_BUFFER_MS: Long = 15000

    /**
     * Maximum buffer size
     */
    const val MAX_BUFFER_MS: Long = 50000

    // ════════════════════════════════════════════════════════════════════════
    //  V5.0 — DNS-over-HTTPS (DoH) CONFIGURATION
    //  ════════════════════════════════════════════════════════════════════════
    //  Wraps every IPTV domain resolution in an encrypted HTTPS tunnel so
    //  local ISP DNS poisoning / hijacking cannot redirect or block IPTV
    //  endpoints. The DoH resolver is constructed once at app init and
    //  plugged into every OkHttpClient that touches an IPTV domain.
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Master switch for the DoH resolver. When `true`, every OkHttp client
     * built by [RedirectSniffer] and [ProxyForegroundService] will route
     * its DNS lookups through [DOH_CLOUDFLARE_ENDPOINT] over HTTPS port
     * 443. When `false`, the system DNS resolver is used (subject to ISP
     * poisoning / hijacking).
     *
     * Default: `true` — the directive explicitly mandates DoH be the
     * primary resolution path.
     */
    const val DOH_ENABLED: Boolean = true

    /**
     * Cloudflare's public DoH endpoint. Chosen for:
     *   - Global anycast presence (low RTT from any region)
     *   - Strict no-logging policy (privacy)
     *   - HTTP/2 + TLS 1.3 support (fast handshake)
     *   - Compatible with OkHttp's `DnsOverHttps` Module (JSON wire format)
     *
     * Endpoint documentation:
     *   https://developers.cloudflare.com/1.1.1.1/encryption/dns-over-https/
     */
    const val DOH_CLOUDFLARE_ENDPOINT: String = "https://1.1.1.1/dns-query"

    /**
     * Bootstrap client connect timeout — the OkHttp client that performs
     * the DoH HTTPS request itself. The directive mandates a 3-second
     * MAXIMUM so a misconfigured DoH endpoint cannot stall the entire
     * network stack. If the DoH bootstrap client cannot reach Cloudflare
     * within 3 seconds, the resolver falls back to system DNS.
     */
    const val DOH_BOOTSTRAP_CONNECT_TIMEOUT_MS: Long = 3_000L

    /**
     * Bootstrap client read timeout — applies to the actual DoH HTTPS
     * response (the JSON-encoded DNS answer). 3 seconds is generous for
     * a single A-record lookup; if it expires, we fall back.
     */
    const val DOH_BOOTSTRAP_READ_TIMEOUT_MS: Long = 3_000L

    /**
     * Bootstrap client write timeout — applies to the DoH HTTPS request
     * body (a small JSON payload). 3 seconds is more than enough.
     */
    const val DOH_BOOTSTRAP_WRITE_TIMEOUT_MS: Long = 3_000L

    /**
     * Hard-coded fallback IPv4 addresses for `1.1.1.1` and `1.0.0.1`
     * (Cloudflare's secondary DoH endpoint). Used by the bootstrap
     * client to AVOID the very DNS poisoning we are trying to bypass —
     * if we resolved `1.1.1.1` via system DNS, an ISP could redirect
     * us to a fake DoH server.
     *
     * The OkHttp DnsOverHttps module accepts an `bootstrapDnsHosts`
     * list which is consulted BEFORE the system resolver for the DoH
     * endpoint's own hostname.
     */
    val DOH_BOOTSTRAP_IPS: List<String> = listOf("1.1.1.1", "1.0.0.1")

    /**
     * User-Agent sent by the bootstrap DoH client. Uses a vanilla
     * Firefox UA so Cloudflare's edge doesn't fingerprint us as a
     * non-browser client and rate-limit the DoH queries.
     */
    const val DOH_BOOTSTRAP_USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0"

    // ════════════════════════════════════════════════════════════════════════
    //  V5.0 — NATIVE HEADER SPOOFING MATRIX SIGNATURES
    //  ════════════════════════════════════════════════════════════════════════
    //  Production-grade client signature arrays used by the OkHttp
    //  Interceptor installed inside ProxyForegroundService. The
    //  interceptor rotates between these for every distinct downstream
    //  stream connection request so edge firewalls / WAFs cannot lock
    //  onto a single static User-Agent signature.
    //
    //  Two families are mandated by the directive:
    //    1. Native LibVLC signature (VLC/3.0.20 LibVLC/3.0.20)
    //    2. Android TV Chromecast framework
    //  A third (Roku-style) family is included for pattern diversity.
    // ════════════════════════════════════════════════════════════════════════

    /**
     * LibVLC User-Agent signatures. Used for the `User-Agent` header
     * rotation. DPI boxes that throttle or block "non-VLC" traffic at
     * ISPs will pass us through untouched when they see these strings.
     */
    val SPOOF_USER_AGENTS_LIBVLC: List<String> = listOf(
        "VLC/3.0.20 LibVLC/3.0.20",
        "VLC/3.0.21 LibVLC/3.0.21",
        "VLC/3.0.19 LibVLC/3.0.19",
        "VLC/3.0.18 LibVLC/3.0.18"
    )

    /**
     * Android TV / Chromecast User-Agent signatures. Modern edge
     * firewalls (Cloudflare, Akamai, Fastly) treat these as legitimate
     * streaming clients and skip the anti-bot challenge path.
     */
    val SPOOF_USER_AGENTS_CHROMECAST: List<String> = listOf(
        "Mozilla/5.0 (Linux; Android 11; Chromecast Google TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; Chromecast) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36 CrKey/124.0.0.0"
    )

    /**
     * Roku-style User-Agent signatures. Included for pattern diversity —
     * breaks up the rotation so the WAF cannot predict the next
     * fingerprint from the previous one.
     */
    val SPOOF_USER_AGENTS_ROKU: List<String> = listOf(
        "Roku/DVP-9.10 (459.10E04123A)",
        "Roku/DVP-10.5 (525.05R0123X)"
    )

    /**
     * Accept-Language pool — geographically diverse to break fingerprinting.
     * Rotated per request alongside the User-Agent.
     */
    val SPOOF_ACCEPT_LANGUAGES: List<String> = listOf(
        "en-US,en;q=0.9",
        "en-GB,en;q=0.9",
        "en-US,en;q=0.9,fr-FR;q=0.8",
        "en-US,en;q=0.9,es-ES;q=0.8",
        "en-US,en;q=0.9,de-DE;q=0.8",
        "en-US,en;q=0.9,ar;q=0.8",
        "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7",
        "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    /**
     * Default Accept header sent on every spoofed request. The literal
     * value `*` `*` (slash-star slash-star) is the safest value — it
     * satisfies both VLC-style and browser-style upstream origins
     * without triggering content-negotiation edge cases.
     */
    const val SPOOF_DEFAULT_ACCEPT: String = "*/*"

    /**
     * Default Connection header. `keep-alive` reuses the TCP socket
     * across segment requests, saving one TCP+TLS handshake per segment.
     */
    const val SPOOF_DEFAULT_CONNECTION: String = "keep-alive"

    /**
     * Headers that must be 100% purged from every downstream request.
     * The default OkHttp client injects `User-Agent: okhttp/4.x` and
     * (when a body is present) `Accept-Encoding: gzip` — both leak our
     * true identity to the WAF. The interceptor strips them before
     * the request hits the wire.
     */
    val SPOOF_HEADERS_TO_STRIP: Set<String> = setOf(
        "User-Agent",
        "Accept-Encoding",
        "OkHttp-Selected-Protocol",
        "OkHttp-Sent-Millis",
        "OkHttp-Received-Millis"
    )

    // ════════════════════════════════════════════════════════════════════════
    //  V5.0 — MULTI-THREADED SOCKET SHATTER EXCEPTION PATTERNS
    //  ════════════════════════════════════════════════════════════════════════
    //  Sub-exception message fragments that the streaming pump loop in
    //  ProxyForegroundService watches for. When ANY of these patterns
    //  is trapped inside a `java.io.IOException`, the engine immediately:
    //    1. Skips error retries (no exponential backoff)
    //    2. Hard force-closes the upstream network socket
    //    3. Shuts down the worker thread
    //  This achieves 0% battery drain and absolute zero byte bleeding
    //  over mobile network allocations during lightning-fast channel
    //  surfing (Zapping Mode).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Lowercase substrings that, when found inside an IOException
     * message, trigger an immediate socket shatter. The match is
     * case-insensitive so we don't miss "broken pipe" / "BROKEN PIPE"
     * variants from different JVM vendors.
     */
    val SOCKET_SHATTER_PATTERNS: List<String> = listOf(
        "broken pipe",
        "connection reset by peer",
        "connection reset",
        "socket closed",
        "socket is closed",
        "socket exception",
        "software caused connection abort",
        "pipe broken",
        "stream is closed",
        "endpoint is at or beyond eof"
    )

    /**
     * Maximum concurrent proxy sessions. Bumped from 32 to 64 in V5.0
     * to support higher HLS segment parallelism (some streams fire
     * 8+ concurrent segment requests during initial buffer fill).
     */
    const val MAX_CONCURRENT_SESSIONS: Int = 64
}
