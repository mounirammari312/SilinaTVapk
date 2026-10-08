package com.superz.iptvplayer.player.multiscreen

import com.superz.iptvplayer.data.db.Channel
import com.superz.iptvplayer.data.db.Playlist

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v2.3.0 — MULTI-SCREEN (الشاشات المتعددة), the pure geometry/logic
 *  core. ZERO Android imports — every rule here is unit-testable on
 *  the JVM (the VodSidebarBuilder contract).
 *
 *  The feature: up to four live channels playing simultaneously in a
 *  split-screen grid (the professional IPTV apps' multi-view — TiviMate,
 *  IPTV Smarters), the focused cell owning the audio, D-pad/touch
 *  navigating between cells, one tap expanding a cell fullscreen.
 *
 *  Design rules this file owns:
 *   • [MultiLayout] — the two grid shapes (1×2 and 2×2) with their
 *     rows/cols/cells geometry;
 *   • [focusTarget] — directional focus math with edge CLAMPING (a TV
 *     remote must never wrap focus from the last column into the first
 *     — wrapping is how users lose track of which cell is highlighted);
 *   • [isMultiScreenCapable] — which channels may join a grid: live
 *     channels whose URL is derivable for ExoPlayer (every stream in a
 *     multi-screen cell IS an ExoPlayer — libVLC cannot be multiplied ×4
 *     on TV boxes). VOD keys, local saves and VLC-only protocols are
 *     excluded by construction;
 *   • [candidateUrls] — the per-cell fallback chain (directSource first,
 *     then .ts → .m3u8, de-duplicated) — the single-channel player's
 *     StreamUrls chain, adapted to the grid's plain-HTTP cells.
 * ═══════════════════════════════════════════════════════════════════
 */

/** The grid shapes the multi-screen supports. */
enum class MultiLayout(val cells: Int, val rows: Int, val cols: Int) {
    TWO(cells = 2, rows = 1, cols = 2),
    FOUR(cells = 4, rows = 2, cols = 2);

    companion object {
        /** Persisted-name round trip (SharedPreferences); null on garbage. */
        fun fromName(name: String?): MultiLayout? =
            entries.firstOrNull { it.name == name }
    }
}

/** A directional focus step (D-pad arrows). */
enum class FocusDir { LEFT, RIGHT, UP, DOWN }

object MultiScreenGrid {

    /**
     * The next cell a D-pad arrow lands on from [from] — or null at the
     * grid's edge (clamped navigation: focus never wraps around, the
     * highlighted cell moves only where the arrow physically points).
     */
    fun focusTarget(layout: MultiLayout, from: Int, dir: FocusDir): Int? {
        if (from !in 0 until layout.cells) return null
        val row = from / layout.cols
        val col = from % layout.cols
        val next = when (dir) {
            FocusDir.LEFT -> if (col > 0) from - 1 else null
            FocusDir.RIGHT -> if (col < layout.cols - 1) from + 1 else null
            FocusDir.UP -> if (row > 0) from - layout.cols else null
            FocusDir.DOWN -> if (row < layout.rows - 1) from + layout.cols else null
        }
        return next?.takeIf { it in 0 until layout.cells }
    }

    /** VOD synthetic keys never join a multi-screen grid. */
    fun isVodKey(key: String?): Boolean =
        key != null && (key.startsWith("vodm:") || key.startsWith("vode:") ||
            key.startsWith("vodc:") || key.startsWith("vodx:") || key.startsWith("vodt:") ||
            key.startsWith("saved:"))

    /**
     * May this channel play in a grid cell? A cell is a plain ExoPlayer
     * over an HTTP URL, so the answer is YES exactly when a URL is
     * derivable for it:
     *   • M3U / resolved-direct channels — an http(s) directUrl;
     *   • Xtream channels — a streamId (+ credentials in the playlist);
     *   • PORTAL live channels — a stalker cmd the ViewModel resolves
     *     (create_link) before assigning the cell.
     * VOD keys and local saves are refused by construction.
     */
    fun isMultiScreenCapable(playlist: Playlist?, channel: Channel): Boolean {
        if (isVodKey(channel.key)) return false
        val type = playlist?.type
        if (channel.directUrl?.startsWith("http", true) == true) return true
        if (channel.streamId != null && type == "XTREAM") return true
        if (type == "PORTAL" && channel.stalkerCmd != null) return true
        return false
    }

    /**
     * The per-cell URL fallback chain (first → last): directSource /
     * directUrl first, then the Xtream .ts and .m3u8 variants,
     * de-duplicated in order. The manager advances through it on cell
     * error, exactly like the single player's engine chain.
     */
    fun candidateUrls(playlist: Playlist, channel: Channel): List<String> {
        val out = mutableListOf<String>()
        channel.directUrl?.let { direct ->
            if (direct.startsWith("http", true)) out += direct
        }
        if (channel.streamId != null && playlist.type == "XTREAM") {
            val base = playlist.server?.trimEnd('/') ?: ""
            val u = playlist.username ?: ""
            val p = playlist.password ?: ""
            if (base.isNotBlank() && u.isNotBlank() && p.isNotBlank()) {
                out += "$base/live/$u/$p/${channel.streamId}.ts"
                out += "$base/live/$u/$p/${channel.streamId}.m3u8"
            }
        }
        return out.distinct()
    }

    /**
     * The first empty slot after [from] (circular) — the setup screen's
     * "assign and advance" cursor: pick a channel → it lands in the
     * selected slot → selection hops to the next empty slot so the next
     * pick fills the grid left-to-right without extra taps.
     */
    fun nextEmptySlot(slots: List<Channel?>, from: Int): Int {
        val n = slots.size
        for (step in 1..n) {
            val idx = (from + step) % n
            if (slots[idx] == null) return idx
        }
        return from
    }

    /**
     * v2.3.1 — THE STAGGERED SPIN-UP BACKOFF (pure, unit-tested).
     *
     * A grid start asks a TV box to stand up FOUR players — four video
     * decoder sessions, four audio tracks, four network cold-starts —
     * within a few milliseconds, on hardware whose codec budget is often
     * barely two streams. Asking for all four AT ONCE is exactly how the
     * whole process gets torn down by a fatal codec/EGL failure (the
     * v2.3.0 field crash: tapping ابدأ المشاهدة closed the app).
     *
     * The professional multiview contract (TiviMate / IPTV Smarters):
     * cells come up SEQUENTIALLY — cell 1 immediately, each subsequent
     * cell one step later — so the box's decoder farm is claimed in an
     * orderly queue instead of a thundering herd. This function computes
     * one newcomer's delay from the recent-creation timestamps:
     *   delay = (creations inside the window) × step
     * A lone mid-watch picker swap finds an empty window → instant.
     */
    fun creationBackoffMs(
        nowMs: Long,
        recentCreationMs: List<Long>,
        stepMs: Long = STAGGER_STEP_MS,
        windowMs: Long = STAGGER_WINDOW_MS
    ): Long {
        if (stepMs <= 0 || windowMs <= 0) return 0L
        val recent = recentCreationMs.count { it in (nowMs - windowMs)..nowMs }
        return recent.coerceAtMost(8) * stepMs
    }

    /**
     * v2.3.2 — THE CHAIN-RETRY DECISION (pure, unit-tested).
     *
     * The v2.3.1 field report: every grid cell landed on "القناة غير
     * متوفرة" the moment its URL chain exhausted — a single transient
     * failure (a cold CDN edge, a momentary 403 while the panel refreshes
     * a token, a 9 s timeout that fired while the first segment was
     * ALREADY in flight) permanently errored the cell until the user
     * manually pressed retry. The professional multiview contract is more
     * forgiving: the cell gets ONE automatic second pass through its whole
     * chain before the error card — transient failures self-heal, real
     * failures (a dead channel, an unsupported codec) still land on the
     * card after the second honest attempt.
     */
    fun nextChainAction(
        urlsExhausted: Boolean,
        attempt: Int,
        maxAttempts: Int = MAX_CHAIN_ATTEMPTS
    ): ChainAction {
        if (!urlsExhausted) return ChainAction.ADVANCE_URL
        return if (attempt < maxAttempts) ChainAction.RESTART_CHAIN else ChainAction.ERROR
    }

    /** One stagger step (≈ the safe spacing between two codec inits). */
    const val STAGGER_STEP_MS = 300L

    /** How long a creation keeps "thundering" (shrinks the herd window). */
    const val STAGGER_WINDOW_MS = 1_500L

    /**
     * v2.3.2 — how many full passes a cell's URL chain gets before the
     * error card (2 = the first honest pass + one automatic retry pass).
     */
    const val MAX_CHAIN_ATTEMPTS = 2

    // ── v2.4.0 — THE CELL ENGINE LADDER (pure, unit-tested) ────────

    /**
     * v2.4.0 — the engine a grid cell plays through.
     *
     * THE COMPLETION OF THE ROOT FIX. The single-channel player is
     * universal because its chain ends in libVLC — software decoding
     * that no OEM can strip (the natives ship INSIDE the APK). The
     * v2.3.x grid asked only ExoPlayer, so a box whose codec farm
     * refuses its 3rd/4th decoder instance (or whose OEM stripped the
     * AOSP software codecs) errored the cell — “القناة غير متوفرة” —
     * while the SAME channel played fine one screen over, through
     * VLC. v2.4.0 gives the cell the SAME ladder the single player
     * has, miniaturized:
     *   • [CellEngine.EXO] — the platform ladder: hardware codecs
     *     first (enableDecoderFallback walks the whole MediaCodec
     *     list, AOSP software codecs included when the OEM shipped
     *     them). A flagship's four cells stay here, in hardware.
     *   • [CellEngine.VLC] — the guaranteed software rung: the cell
     *     re-opens the SAME URL through a libVLC MediaPlayer with
     *     hardware decoding DISABLED — pure FFmpeg-grade software
     *     decode, the exact universality the single player's last
     *     resort has, multiplied per cell (one shared LibVLC context,
     *     one MediaPlayer per cell — the libVLC video-wall pattern).
     * A DECODER-kind failure promotes EXO → VLC (same URL — the URL
     * was never the problem); every other failure walks the URL
     * chain as before. Works on ALL devices, zero per-device tuning:
     * every box finds its own level at runtime.
     */
    enum class CellEngine { EXO, VLC }

    /**
     * v2.4.0 — the promotion decision (pure): only a DECODER-kind
     * failure on the Exo rung promotes to the VLC rung (the codec
     * farm refused — the URL was never the problem); SERVER and
     * TIMEOUT failures keep the engine (the URL chain handles them).
     * One promotion per slot — a VLC that still fails walks the URL
     * chain like any honest failure.
     */
    fun nextCellEngine(current: CellEngine, failureWasDecoder: Boolean): CellEngine =
        if (failureWasDecoder && current == CellEngine.EXO) CellEngine.VLC else current

    /**
     * v2.4.0 — the per-engine first-frame budget (pure). A software
     * decode (the VLC rung, or Exo's software fallback) starts
     * honestly slower than a hardware one — codec init + the CPU's
     * own first-frame cost, in parallel with sibling cells — so the
     * timeout guard gives it a longer leash. Same values on every
     * device, tuned to the ENGINE, never the device.
     */
    fun cellTimeoutMs(engine: CellEngine): Long = when (engine) {
        CellEngine.EXO -> CELL_TIMEOUT_EXO_MS
        CellEngine.VLC -> CELL_TIMEOUT_VLC_MS
    }

    /**
     * v2.4.0 — THE AUDIO DECODER ECONOMY (pure): at most ONE Exo cell
     * — the focused one — ever claims an AUDIO decoder instance.
     *
     * THE HIDDEN BUDGET KILLER v2.3.2 left on the table: muted cells
     * played at volume 0 but STILL DECODED their audio — on a box
     * with a shallow audio codec farm (AC3 / E-AC3 licensing again),
     * the 3rd and 4th cells failed on the AUDIO decoder while their
     * video would have played fine. The economy: a non-focused EXO
     * cell disables its audio TRACK TYPE entirely — no audio renderer,
     * no audio decoder claimed, nothing for the codec farm to refuse.
     * (VLC cells decode audio in software — no farm to exhaust — so
     * they are muted by volume only.) A focus swap flips the flags on
     * the Exo cells (a live track re-selection: instant, no player
     * rebuild, no stream restart) — the ears follow the eyes exactly
     * as before, on a box that can now afford the grid.
     */
    fun claimsAudio(slot: Int, focusedSlot: Int): Boolean = slot == focusedSlot

    /** v2.4.0 — the Exo rung's first-frame budget (was 12 s). */
    const val CELL_TIMEOUT_EXO_MS = 12_000L

    /**
     * v2.4.0 — the VLC rung's first-frame budget (the CPU's honest
     * parallel cold-start: software codec init + first frames while
     * sibling cells share the same link and silicon).
     */
    const val CELL_TIMEOUT_VLC_MS = 20_000L
}

/** The verdicts [MultiScreenGrid.nextChainAction] hands the manager. */
enum class ChainAction { ADVANCE_URL, RESTART_CHAIN, ERROR }
