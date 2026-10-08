package com.superz.iptvplayer.player.multiscreen

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.superz.iptvplayer.data.db.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v2.3.0 — MULTI-SCREEN ENGINE: up to four players, one per grid
 *  cell, managed as ONE unit. v2.4.0 — the cells gained the SAME
 *  engine ladder the single player has (Exo → VLC).
 *
 *  THE IRON BOUNDARY: this manager is completely SELF-CONTAINED — it
 *  never touches SmartPlayer / EngineRouter / PlayerSessionManager /
 *  VodPlayRegistry. The single-channel player's engine files stay
 *  byte-identical; the multi-screen is a parallel world that reuses
 *  the app's ALREADY-BUNDLED libVLC natives for its software rung
 *  (reading SmartPlayer's proven creation pattern, changing nothing
 *  in it).
 *
 *  Professional playback rules implemented here:
 *   • THE v2.4.0 ENGINE LADDER per cell: an EXO cell (hardware first,
 *     MediaCodec fallback walking the platform's whole decoder list)
 *     that meets a DECODER-kind failure re-opens the SAME URL through
 *     a libVLC MediaPlayer with hardware decoding DISABLED — the
 *     guaranteed software rung, the exact universality that keeps
 *     the single screen playing on every box on Earth (the natives
 *     ship inside the APK — no OEM can strip them). One shared
 *     LibVLC context, one MediaPlayer per cell (the libVLC
 *     video-wall pattern).
 *   • LEAN BUFFERS per cell (min 1.5s / max 15s / target ≈ 24 MB):
 *     four cells ≈ ≤ 96 MB of buffering — the single player keeps its
 *     own 256 MB deep-live buffers untouched (that depth is what keeps
 *     one stream immune to CDN cuts; a grid splits the same attention
 *     four ways and favors instant parallel starts instead);
 *   • NO DISK CACHE in a cell: the shared 512 MB LRU is the single
 *     player's seamless-restart window — four rotating streams would
 *     evict it in minutes (thrash both features);
 *   • AUDIO ROUTING + THE v2.4.0 AUDIO ECONOMY: exactly ONE cell owns
 *     the sound at any moment — the focused cell. A non-focused EXO
 *     cell disables its audio TRACK TYPE entirely (no audio renderer,
 *     no audio decoder instance claimed — the shallow audio codec
 *     farms can never refuse the grid); a non-focused VLC cell mutes
 *     by volume (VLC decodes audio in software — no farm to exhaust);
 *   • PER-CELL FALLBACK CHAIN: directSource → .ts → .m3u8; a cell error
 *     advances its own chain (the single player's engine-fallback
 *     philosophy, miniaturized per cell) and lands on a retryable ERROR
 *     state only when every URL failed on every rung;
 *   • FULL RELEASE on every slot swap and on teardown — a replaced
 *     stream's player (Exo OR VLC) is released the instant its
 *     successor prepares, so the grid never holds a fifth hidden
 *     instance.
 * ═══════════════════════════════════════════════════════════════════
 */
@OptIn(UnstableApi::class)
class MultiScreenManager(
    private val context: Context,
    private val okHttp: OkHttpClient,
    private val scope: CoroutineScope
) {

    /** The per-cell lifecycle the UI renders. */
    enum class CellStatus { LOADING, PLAYING, BUFFERING, ERROR }

    /**
     * v2.3.2 — WHY a cell failed (the field diagnostics). The v2.3.1
     * report showed every failure as the same generic "channel
     * unavailable" card, which hid the real story (a box's codec farm
     * refusing its 3rd/4th decoder). The card now says WHICH wall the
     * cell hit, in the user's language.
     */
    enum class FailureKind { NONE, DECODER, SERVER, TIMEOUT }

    /** One cell's publishable state (slot-ordered list in [cells]). */
    data class CellUi(
        val slot: Int,
        val channel: Channel,
        val status: CellStatus,
        val ttffMs: Long? = null,
        val failureKind: FailureKind = FailureKind.NONE,
        /** v2.4.0 — which engine the cell is currently playing through
         * (drives the video surface the UI binds: PlayerView ↔
         * VLCVideoLayout). */
        val engine: MultiScreenGrid.CellEngine = MultiScreenGrid.CellEngine.EXO
    )

    /** One slot's internals: the channel, its URL chain and the player. */
    private class SlotHolder(
        var channel: Channel,
        var urls: List<String>,
        var urlIndex: Int,
        var status: CellStatus = CellStatus.LOADING,
        var ttffMs: Long? = null,
        var startMs: Long = 0L,
        var generation: Int = 0,
        /** v2.3.2 — which full pass of the URL chain this slot is on. */
        var attempt: Int = 1,
        /** v2.3.2 — the LAST failure's kind (feeds the error card's line). */
        var failureKind: FailureKind = FailureKind.NONE,
        /** v2.4.0 — the engine this slot's NEXT player builds on (see
         * [MultiScreenGrid.nextCellEngine]; promoted to the VLC rung on a
         * DECODER-kind failure — a fresh channel resets it). */
        var engine: MultiScreenGrid.CellEngine = MultiScreenGrid.CellEngine.EXO
    ) {
        /** v2.4.1 — the EXO cell's PlayerView, kept BY THE ENGINE (the
         * SmartPlayer pattern: the surface is bound at PLAYER-BIRTH time,
         * never by hoping a Compose recomposition happens to run). */
        var exoView: PlayerView? = null

        /** v2.4.1 — the VLC cell's VLCVideoLayout, same engine-side rule. */
        var vlcView: VLCVideoLayout? = null

        /** The ExoPlayer of an EXO cell (null on the VLC rung). */
        var exoPlayer: ExoPlayer? = null

        /** The libVLC MediaPlayer of a VLC cell (null on the EXO rung). */
        var vlcPlayer: MediaPlayer? = null

        /** Whether the VLC cell's views are attached to its surface. */
        var vlcAttached: Boolean = false

        /** The cell's timeout guard job (cancelled on first frame). */
        var timeoutJob: Job? = null

        /** v2.3.1 — the staggered spin-up job (the delayed creation). */
        var startJob: Job? = null

        fun cancelTimeout() {
            timeoutJob?.cancel()
            timeoutJob = null
        }

        /** v2.3.1 — kills a pending staggered creation AND any timeout. */
        fun cancelPendingWork() {
            startJob?.cancel()
            startJob = null
            cancelTimeout()
        }
    }

    private val holders = arrayOfNulls<SlotHolder>(4)

    /** v2.3.1 — the recent player-creation timestamps (the stagger gate). */
    private val recentCreations = java.util.ArrayDeque<Long>()

    /** v2.4.0 — the grid's ONE shared LibVLC context (the video-wall
     * pattern: N MediaPlayers, one context, one set of natives). Lazy —
     * a grid whose every cell stays on hardware never pays for it. */
    private var gridLibVlc: LibVLC? = null

    /** v2.4.0 — set if LibVLC cannot initialize on this device
     * (UnsatisfiedLinkError on 16KB-page devices / the lite build):
     * the ladder's VLC rung degrades to the honest URL-chain walk. */
    private var vlcUnavailable = false

    /** The slot whose cell owns the audio (volume 1 + audio focus). */
    @Volatile
    var focusedSlot: Int = 0
        private set

    private val _cells = MutableStateFlow<List<CellUi>>(emptyList())
    val cells: StateFlow<List<CellUi>> = _cells.asStateFlow()

    // ── PUBLIC API ──────────────────────────────────────────────────

    /** The ExoPlayer an EXO cell's PlayerView should bind to (null otherwise). */
    fun playerFor(slot: Int): ExoPlayer? = holders.getOrNull(slot)?.exoPlayer

    /**
     * v2.4.1 — bind a cell's PlayerView INTO THE ENGINE (the UI factory's
     * first act). The manager keeps the reference so that EVERY player
     * born in this slot — the staggered firstborn, a chain-advance
     * successor, a retry rebuild — is bound to the surface at BIRTH, the
     * exact SmartPlayer pattern the single screen has always used.
     *
     * WHY THIS IS THE v2.4.1 ROOT FIX: v2.4.0 relied on the Compose
     * `AndroidView.update` lambda re-running after each player creation —
     * but a publish whose CellUi is EQUAL to the previous one (status still
     * LOADING, same channel, same engine) is DEDUPLICATED by StateFlow →
     * no emission → no recomposition → `update` never re-runs → the
     * PlayerView keeps `player = null` → the cell plays AUDIO into a
     * missing surface: the field's exact “sound yes, picture no” report.
     * Engine-side binding makes the surface deterministic — it no longer
     * depends on WHEN Compose happens to recompose. The `update` lambda
     * stays as a harmless safety net (it can only ever write the CURRENT
     * player or null — never a stale one).
     */
    fun attachExoView(slot: Int, view: PlayerView) {
        val holder = holders.getOrNull(slot)
        if (holder == null) {
            // Empty slot — no player can exist; keep the view clean.
            if (view.player != null) view.player = null
            return
        }
        holder.exoView = view
        // A late-arriving surface (fullscreen swap, engine demotion back to
        // Exo) binds the CURRENT player immediately.
        holder.exoPlayer?.let { view.player = it }
    }

    /** v2.4.1 — the PlayerView left the slot's hierarchy (dispose / key
     * swap / fullscreen swap): drop the engine-side reference so a FUTURE
     * view binds fresh, and clear the dead binding off the leaving view. */
    fun detachExoView(slot: Int, view: PlayerView) {
        val holder = holders.getOrNull(slot) ?: return
        if (holder.exoView === view) {
            holder.exoView = null
            if (view.player === holder.exoPlayer) view.player = null
        }
    }

    /** The MediaPlayer a VLC cell should attach to (null otherwise). */
    fun vlcPlayerFor(slot: Int): MediaPlayer? = holders.getOrNull(slot)?.vlcPlayer

    /** v2.4.0 — the engine a cell is currently playing through. */
    fun engineFor(slot: Int): MultiScreenGrid.CellEngine =
        holders.getOrNull(slot)?.engine ?: MultiScreenGrid.CellEngine.EXO

    /** The channel assigned to a slot (null = empty). */
    fun channelFor(slot: Int): Channel? = holders.getOrNull(slot)?.channel

    /**
     * v2.4.0 — bind a VLC cell's [VLCVideoLayout] to its MediaPlayer.
     * v2.4.1 — the layout is remembered ENGINE-SIDE too (see
     * [attachExoView]): a player born AFTER the view attaches at birth
     * ([prepareVlcCell]’s birth-bind) — both arrival orders are covered,
     * the SmartPlayer late-attach contract. Idempotent; called from the
     * cell UI's update phase.
     */
    fun bindVlcView(slot: Int, layout: VLCVideoLayout) {
        val holder = holders.getOrNull(slot) ?: return
        holder.vlcView = layout          // v2.4.1 — remembered for the birth-bind
        val player = holder.vlcPlayer ?: return
        if (holder.vlcAttached) return
        try {
            // textureView = true: no z-order conflicts with the cell's
            // Compose overlays (the app's one-active-surface rule).
            player.attachViews(layout, null, false, true)
            holder.vlcAttached = true
        } catch (t: Throwable) {
            Log.w(TAG, "cell $slot vlc attachViews", t)
        }
    }

    /** v2.4.0 — unbind a VLC cell's surface (the UI's onDispose). */
    fun unbindVlcView(slot: Int, layout: VLCVideoLayout) {
        val holder = holders.getOrNull(slot) ?: return
        // v2.4.1 — drop the engine-side reference too: a view that left the
        // hierarchy must never receive a later birth-bind.
        if (holder.vlcView === layout) holder.vlcView = null
        if (!holder.vlcAttached) return
        try {
            holder.vlcPlayer?.detachViews()
        } catch (t: Throwable) {
            Log.w(TAG, "cell $slot vlc detachViews", t)
        }
        holder.vlcAttached = false
    }

    /**
     * (Re)assign a slot: the previous player is released FIRST (a
     * replaced stream must not keep decoding behind its successor),
     * then a fresh player prepares the chain's first URL — through the
     * v2.3.1 STAGGERED SPIN-UP (see [MultiScreenGrid.creationBackoffMs]):
     * the grid's cells stand up one step apart, never as a herd.
     */
    fun assign(slot: Int, channel: Channel, urls: List<String>) {
        if (slot !in holders.indices) return
        releaseSlot(slot)
        if (urls.isEmpty()) {
            val holder = SlotHolder(channel, emptyList(), 0)
            holder.status = CellStatus.ERROR
            holders[slot] = holder
            publish()
            return
        }
        val holder = SlotHolder(channel, urls, 0)
        holders[slot] = holder
        holder.generation++
        prepareCurrent(holder, slot)
        publish()
    }

    /** Move the audio to [slot] — instant volume swap, no restarts. */
    fun setFocus(slot: Int) {
        if (slot !in holders.indices || holders[slot] == null) return
        if (focusedSlot == slot) return
        focusedSlot = slot
        applyAudioRouting()
    }

    /** Re-run the cell's whole chain from its first URL — a FRESH pass
     *  (attempt reset): the retry key is the user's explicit "try again",
     *  so it earns a complete two-pass budget all over again. The ENGINE
     *  is deliberately KEPT (v2.4.0): a slot that already proved its
     *  hardware refuses its codec must not burn another doomed hardware
     *  pass before landing where it plays — the retry starts at the
     *  level that worked, or at VLC if nothing did. */
    fun retry(slot: Int) {
        val holder = holders.getOrNull(slot) ?: return
        holder.cancelPendingWork()
        holder.urlIndex = 0
        holder.status = CellStatus.LOADING
        holder.ttffMs = null
        holder.attempt = 1
        holder.failureKind = FailureKind.NONE
        holder.generation++
        prepareCurrent(holder, slot)
        publish()
    }

    /** Release one slot (the channel picker's "clear" action). */
    fun releaseSlot(slot: Int) {
        val holder = holders.getOrNull(slot) ?: return
        // v2.3.1 — the generation bump ALSO kills any pending staggered
        // creation: a slot cleared mid-spell must never spin up a player
        // from beyond the grave.
        holder.generation++
        holder.cancelPendingWork()
        releaseSlotPlayer(holder, slot)
        if (holders[slot] === holder) holders[slot] = null
        if (focusedSlot == slot) {
            // audio falls to the first still-live slot
            focusedSlot = holders.indices.firstOrNull { holders[it] != null } ?: 0
        }
        publish()
    }

    /** Teardown — every player released, state emptied. */
    fun releaseAll() {
        for (slot in holders.indices) {
            val holder = holders.getOrNull(slot) ?: continue
            holder.generation++   // v2.3.1 — kills pending staggered spin-ups
            holder.cancelPendingWork()
            releaseSlotPlayer(holder, slot)
            holders[slot] = null
        }
        synchronized(recentCreations) { recentCreations.clear() }
        releaseGridLibVlc()
        _cells.value = emptyList()
    }

    // ── INTERNALS ───────────────────────────────────────────────────

    /** v2.4.0 — release whichever player a slot currently holds.
     * v2.4.1 — the SURFACE is unbound FIRST (a PlayerView left pointing
     * at a released player would render black/crash on its callbacks;
     * the freed binding also lets the successor's birth-bind take over
     * cleanly). */
    private fun releaseSlotPlayer(holder: SlotHolder, slot: Int) {
        try {
            holder.exoView?.let { v ->
                if (v.player === holder.exoPlayer) v.player = null
            }
            holder.exoPlayer?.run {
                stop()
                clearMediaItems()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "slot $slot exo release", e)
        }
        holder.exoPlayer = null
        try {
            holder.vlcPlayer?.run {
                stop()
                detachViews()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "slot $slot vlc release", e)
        }
        holder.vlcPlayer = null
        holder.vlcAttached = false
    }

    /** v2.4.0 — the shared LibVLC context, born on the first VLC cell. */
    private fun ensureGridLibVlc(): LibVLC? {
        if (vlcUnavailable) return null
        gridLibVlc?.let { return it }
        return try {
            val options = arrayListOf(
                "--network-caching=$VLC_CELL_CACHING_MS",
                "--live-caching=$VLC_CELL_CACHING_MS",
                "--file-caching=$VLC_CELL_CACHING_MS",
                "--drop-late-frames",
                "--skip-frames",
                "--no-osd"
            )
            val lib = LibVLC(context, options)
            gridLibVlc = lib
            lib
        } catch (t: Throwable) {
            // UnsatisfiedLinkError (16KB-page devices / missing natives)
            // or NoClassDefFoundError (lite build) — the VLC rung bows
            // out and the chain walks on honestly.
            vlcUnavailable = true
            gridLibVlc = null
            Log.e(TAG, "grid VLC unavailable on this device — Exo-only grid", t)
            null
        }
    }

    /** v2.4.0 — the shared context dies with the grid (or when the last
     * VLC cell is gone — see [releaseSlotPlayer]'s callers via
     * [releaseAll]; slot swaps keep it: a box that needed VLC once will
     * need it again on the next swap). */
    private fun releaseGridLibVlc() {
        try {
            gridLibVlc?.release()
        } catch (e: Exception) {
            Log.w(TAG, "grid libvlc release", e)
        }
        gridLibVlc = null
    }

    private fun prepareCurrent(holder: SlotHolder, slot: Int) {
        val gen = holder.generation
        val url = holder.urls.getOrNull(holder.urlIndex)
        if (url == null) {
            holder.status = CellStatus.ERROR
            publish()
            return
        }
        // ── v2.3.1 — THE STAGGERED SPIN-UP GATE ──
        // A grid start schedules its cells through
        // [MultiScreenGrid.creationBackoffMs]: the first cell stands up
        // immediately, each following one a step later — the box's codec
        // farm is claimed in an orderly queue (the v2.3.0 field crash
        // was the whole app dying under four simultaneous inits while
        // the parked single player still held a decoder). A lone picker
        // swap finds an empty window and starts instantly, as before.
        val now = android.os.SystemClock.elapsedRealtime()
        val backoffMs = synchronized(recentCreations) {
            recentCreations.removeAll { it < now - MultiScreenGrid.STAGGER_WINDOW_MS }
            val computed = MultiScreenGrid.creationBackoffMs(now, recentCreations.toList())
            recentCreations.addLast(now)
            computed
        }
        holder.status = CellStatus.LOADING
        // TTFF measures from the SCHEDULED start so a staggered cell's
        // timing stays honest (its clock begins when it actually runs).
        holder.startMs = now + backoffMs
        holder.cancelPendingWork()
        if (backoffMs <= 0L) {
            prepareCurrentNow(holder, slot, gen)
        } else {
            Log.d(TAG, "cell $slot staggered spin-up in ${backoffMs}ms")
            holder.startJob = scope.launch {
                delay(backoffMs)
                if (gen == holder.generation) prepareCurrentNow(holder, slot, gen)
            }
        }
        publish()
    }

    /**
     * v2.3.1 — the actual creation + prepare, HARDENED: a player build
     * that throws on this box (codec/EGL exhaustion is a REAL failure
     * mode on TV hardware) advances the cell's fallback chain instead
     * of tearing the whole process down. The audio routing re-applies
     * AFTER creation so a late-born player still receives its verdict.
     * v2.4.0 — the branch by engine: EXO builds an ExoPlayer, VLC
     * builds a libVLC MediaPlayer (software decode by construction).
     */
    private fun prepareCurrentNow(holder: SlotHolder, slot: Int, gen: Int) {
        if (gen != holder.generation) return   // superseded while staggered
        val url = holder.urls.getOrNull(holder.urlIndex)
        if (url == null) {
            holder.status = CellStatus.ERROR
            publish()
            return
        }
        holder.status = CellStatus.LOADING
        holder.startMs = android.os.SystemClock.elapsedRealtime()
        when (holder.engine) {
            MultiScreenGrid.CellEngine.VLC -> prepareVlcCell(holder, slot, gen, url)
            MultiScreenGrid.CellEngine.EXO -> prepareExoCell(holder, slot, gen, url)
        }
    }

    /**
     * The EXO cell's creation + prepare (v2.3.2's hardened path,
     * unchanged in spirit): lean buffers, no disk cache, muted by
     * default, the platform decoder ladder with fallback enabled.
     */
    private fun prepareExoCell(holder: SlotHolder, slot: Int, gen: Int, url: String) {
        val player: ExoPlayer
        try {
            player = createCellPlayer(holder, slot, gen)
            holder.exoPlayer = player
            // ── v2.4.1 — THE BIRTH-BIND (the root fix for “audio yes,
            // picture no”): the surface — composed long before this
            // staggered player existed — is bound THE INSTANT the player
            // is born, from the engine side, exactly like SmartPlayer
            // binds a newborn ExoPlayer to its stored PlayerView. The
            // video renderer initializes WITH its output surface; no
            // StateFlow-dedup race can ever leave it surface-less. ──
            holder.exoView?.let { v ->
                try {
                    v.player = player
                } catch (t: Throwable) {
                    Log.w(TAG, "cell $slot birth-bind", t)
                }
            }
            player.setMediaSource(buildMediaSource(url), /* resetPosition = */ true)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            // The box refused the instance (decoder budget, EGL surface,
            // thread exhaustion) — release whatever half-born state
            // exists and let the chain try the next URL.
            Log.w(TAG, "cell $slot player build failed", e)
            try {
                holder.exoPlayer?.release()
            } catch (_: Exception) {
            }
            holder.exoPlayer = null
            advanceChain(holder, slot, gen, FailureKind.DECODER)
            return
        }
        armTimeoutGuard(holder, slot, gen)
        applyAudioRouting()
        publish()
    }

    /**
     * v2.4.0 — THE VLC CELL: the guaranteed software rung. The same
     * URL, re-opened through a libVLC MediaPlayer with hardware
     * decoding DISABLED — the single player's codec-fallback of last
     * resort, miniaturized per cell (one shared LibVLC context, the
     * libVLC video-wall pattern). Its events mirror the Exo cell's
     * states: Buffering → BUFFERING, Playing/Vout → PLAYING (Vout =
     * the first video frames — the honest TTFF mark), EncounteredError
     * → the chain walker (classified SERVER: at this rung the codecs
     * are software, so a failure here is the stream's fault).
     */
    private fun prepareVlcCell(holder: SlotHolder, slot: Int, gen: Int, url: String) {
        val lib = ensureGridLibVlc()
        if (lib == null) {
            // VLC cannot initialize on this device — the VLC rung bows
            // out; walk the chain (still honestly classified).
            Log.w(TAG, "cell $slot vlc rung unavailable — chain walk")
            advanceChain(holder, slot, gen, FailureKind.DECODER)
            return
        }
        val player: MediaPlayer
        try {
            player = MediaPlayer(lib)
            holder.vlcPlayer = player
            player.setEventListener { event ->
                if (gen != holder.generation || holder.vlcPlayer !== player) return@setEventListener
                when (event.type) {
                    MediaPlayer.Event.Buffering -> {
                        if (holder.status != CellStatus.PLAYING) {
                            holder.status = CellStatus.BUFFERING
                            publish()
                        }
                    }
                    MediaPlayer.Event.Playing,
                    MediaPlayer.Event.Vout -> {
                        if (holder.status != CellStatus.PLAYING) {
                            markCellSuccess(holder)
                            publish()
                        }
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        Log.w(TAG, "cell $slot vlc error")
                        advanceChain(holder, slot, gen, FailureKind.SERVER)
                    }
                }
            }
            val media = Media(lib, Uri.parse(url))
            // THE POINT OF THE RUNG: hardware decoding DISABLED — the
            // codec farm that refused this cell's ExoPlayer instance is
            // not asked again; FFmpeg-grade software decode answers.
            media.setHWDecoderEnabled(false, false)
            player.setMedia(media)
            media.release()
            // libVLC's volume is an int scale (0-100), not Exo's float.
            player.volume = if (MultiScreenGrid.claimsAudio(slot, focusedSlot)) VLC_VOLUME_FULL else VLC_VOLUME_MUTED
            // ── v2.4.1 — THE VLC BIRTH-BIND (SmartPlayer's late-attach
            // contract, mirrored): the VLCVideoLayout may have been
            // composed BEFORE this player existed (the promotion publish
            // precedes the staggered creation) — attach it NOW so the
            // vout is born WITH the player and Event.Vout can fire its
            // honest first-frame mark. The reverse order (view arrives
            // after the player) is covered by [bindVlcView]. ──
            holder.vlcView?.let { v ->
                if (!holder.vlcAttached) {
                    try {
                        player.attachViews(v, null, false, true)
                        holder.vlcAttached = true
                    } catch (t: Throwable) {
                        Log.w(TAG, "cell $slot vlc birth-bind", t)
                    }
                }
            }
            player.play()
        } catch (e: Exception) {
            Log.w(TAG, "cell $slot vlc build failed", e)
            try {
                holder.vlcPlayer?.release()
            } catch (_: Exception) {
            }
            holder.vlcPlayer = null
            holder.vlcAttached = false
            advanceChain(holder, slot, gen, FailureKind.DECODER)
            return
        }
        armTimeoutGuard(holder, slot, gen)
        applyVlcRouting()
        publish()
    }

    /** The shared timeout guard, engine-aware (see [armTimeoutGuard]). */
    private fun armTimeoutGuard(holder: SlotHolder, slot: Int, gen: Int) {
        // ── THE CELL TIMEOUT GUARD (the single player's Timeout Guard,
        //    miniaturized): no first frame in time → the cell advances
        //    to its own next URL exactly like an engine error would.
        //    v2.3.2: 12 s (was 9) — four parallel cold starts share ONE
        //    real-world link, and honest CDN edges regularly need ~10 s
        //    for the first segment; killing them at 9 s was landing
        //    PLAYABLE cells on the error card.
        //    v2.4.0: the budget is ENGINE-AWARE — a software-decoding
        //    cell (the VLC rung) gets 20 s (the CPU's honest parallel
        //    cold-start; see [MultiScreenGrid.cellTimeoutMs]).
        holder.cancelTimeout()
        holder.timeoutJob = scope.launch {
            delay(MultiScreenGrid.cellTimeoutMs(holder.engine))
            if (gen == holder.generation && holder.status != CellStatus.PLAYING) {
                Log.w(TAG, "cell $slot timeout on url #${holder.urlIndex} (attempt ${holder.attempt}, engine ${holder.engine})")
                advanceChain(holder, slot, gen, FailureKind.TIMEOUT)
            }
        }
    }

    /**
     * The chain walker. v2.3.2 — TWO rules:
     *   • the failure KIND is classified (decoder / server / timeout)
     *     and carried to the error card, so the field report says WHICH
     *     wall the cell hit instead of a generic "unavailable";
     *   • when the chain exhausts, [MultiScreenGrid.nextChainAction]
     *     decides: ONE automatic second pass (transient failures
     *     self-heal), THEN the error card (real failures stay honest).
     * v2.4.0 — THE ENGINE PROMOTION comes FIRST: a DECODER-kind failure
     * on the Exo rung does NOT waste the URL chain (the URL was never
     * the problem) — the cell re-opens the SAME URL on the VLC rung
     * (guaranteed software decode). Only when the VLC rung ALSO refuses
     * does the URL chain resume walking (at the VLC rung — the slot
     * remembers what its hardware told it).
     */
    private fun advanceChain(
        holder: SlotHolder,
        slot: Int,
        gen: Int,
        kind: FailureKind,
        error: PlaybackException? = null
    ) {
        if (gen != holder.generation) return   // a newer assign/retry owns the slot
        Log.w(TAG, "cell $slot advancing chain: $kind" +
            (error?.let { " (${it.errorCodeName})" } ?: ""))
        holder.cancelPendingWork()
        releaseSlotPlayer(holder, slot)
        holder.failureKind = kind
        // ── v2.4.0 — THE ENGINE PROMOTION (the root fix's completion):
        // the platform codec farm refused → re-open the SAME URL with
        // software decoding GUARANTEED (the bundled libVLC rung). One
        // promotion per slot — a VLC that still fails walks the chain
        // like any honest failure. ──
        val newEngine = MultiScreenGrid.nextCellEngine(holder.engine, kind == FailureKind.DECODER)
        if (newEngine != holder.engine) {
            Log.w(TAG, "cell $slot promoting to $newEngine — same URL, fresh player")
            holder.engine = newEngine
            holder.status = CellStatus.LOADING
            prepareCurrent(holder, slot)
            return
        }
        holder.urlIndex++
        val urlsExhausted = holder.urlIndex >= holder.urls.size
        when (MultiScreenGrid.nextChainAction(urlsExhausted, holder.attempt)) {
            ChainAction.ADVANCE_URL -> prepareCurrent(holder, slot)
            ChainAction.RESTART_CHAIN -> {
                Log.w(TAG, "cell $slot pass ${holder.attempt} exhausted — auto-retry pass ${holder.attempt + 1}")
                holder.attempt++
                holder.urlIndex = 0
                holder.status = CellStatus.LOADING
                prepareCurrent(holder, slot)
            }
            ChainAction.ERROR -> {
                holder.status = CellStatus.ERROR
                publish()
            }
        }
    }

    /**
     * An EXO cell player: lean buffers, no disk cache, muted by default.
     * Audio focus is granted ONLY to the focused cell (see
     * [applyAudioRouting]) — four focus holders would pause each other
     * the moment the second one starts.
     *
     * ── v2.3.2 — THE UNIVERSAL DECODER LADDER ──
     * THE FIELD REPORT (v2.3.1): every cell showed "القناة غير متوفرة"
     * — the crash was gone, but the box's hardware codec farm only
     * allows 1-2 CONCURRENT decoder instances, and a default player
     * answers "decoder init failed" beyond that budget. THE
     * PROFESSIONAL, WORKS-ON-ALL-DEVICES ANSWER (this is a worldwide
     * product, not one-box tuning): enableDecoderFallback(true) —
     * each renderer tries its PREFERRED (hardware) codec first, and
     * the moment a codec init is refused, Media3 walks down the
     * decoder list to the next candidate, all the way down to the
     * AOSP software codecs the OEM shipped. Result, per device, at
     * runtime, with ZERO configuration:
     *   • flagships with 4 hardware instances → four hardware cells;
     *   • the typical TV box (2 instances) → cells 1-2 hardware,
     *     cells 3-4 software — PLAYING instead of erroring;
     *   • old boxes (1 instance) → cell 1 hardware, the rest software.
     * And v2.4.0 completes the ladder: when even the platform's
     * software codecs are stripped or exhausted, the cell PROMOTES to
     * the VLC rung ([MultiScreenGrid.nextCellEngine]) — the guarantee
     * nothing on the device can take away.
     */
    private fun createCellPlayer(holder: SlotHolder, slot: Int, gen: Int): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs                     */ 1_500,
                /* maxBufferMs                     */ 15_000,
                /* bufferForPlaybackMs             */ 1_500,
                /* bufferForPlaybackAfterRebufferMs */ 2_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(CELL_TARGET_BUFFER_BYTES)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val httpFactory = OkHttpDataSource.Factory(okHttp)
            .setDefaultRequestProperties(mapOf("User-Agent" to USER_AGENT))
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
            )
            .build()

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ false
        )
        player.volume = 0f
        // ── v2.4.0 — THE AUDIO DECODER ECONOMY, at birth: a cell born
        // unfocused NEVER claims an audio decoder (its audio track
        // type is disabled before the first frame — no renderer init,
        // nothing for a codec farm to refuse; see
        // [MultiScreenGrid.claimsAudio]). The focused cell (and every
        // late-born player AFTER a focus move) receives its verdict in
        // [applyAudioRouting] below. ──
        if (!MultiScreenGrid.claimsAudio(slot, focusedSlot)) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (gen != holder.generation || holder.exoPlayer !== player) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        holder.status = CellStatus.BUFFERING
                        publish()
                    }
                    Player.STATE_READY -> {
                        if (holder.status != CellStatus.PLAYING) markCellSuccess(holder)
                        publish()
                    }
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (gen != holder.generation || holder.exoPlayer !== player) return
                if (isPlaying) {
                    markCellSuccess(holder)
                    holder.status = CellStatus.PLAYING
                } else if (holder.exoPlayer?.playbackState != Player.STATE_READY) {
                    holder.status = CellStatus.BUFFERING
                }
                publish()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (gen != holder.generation || holder.exoPlayer !== player) return
                Log.w(TAG, "cell $slot error: ${error.errorCodeName}")
                advanceChain(holder, slot, gen, classify(error), error)
            }
        })
        return player
    }

    /**
     * v2.3.2 — map a [PlaybackException] onto the user-facing failure
     * kind. The DECODER bucket is the one that matters in the field:
     * a TV box's hardware codec farm refusing its Nth concurrent
     * instance surfaces as INIT/DECODING/FORMAT-EXCEEDS — and with the
     * v2.4.0 engine ladder in place, reaching the card with a DECODER
     * verdict now means even the VLC software rung could not decode
     * the stream (a genuinely unsupported codec), not a budget hiccup.
     */
    private fun classify(error: PlaybackException): FailureKind = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED -> FailureKind.DECODER
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_CONCURRENT_STREAM_LIMIT,
        PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
        PlaybackException.ERROR_CODE_DISCONNECTED -> FailureKind.SERVER
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> FailureKind.TIMEOUT
        else -> FailureKind.SERVER
    }

    private fun markCellSuccess(holder: SlotHolder) {
        if (holder.ttffMs == null) {
            holder.ttffMs = android.os.SystemClock.elapsedRealtime() - holder.startMs
            holder.cancelTimeout()   // the first frame landed — guard disarmed
        }
        holder.status = CellStatus.PLAYING
    }

    /**
     * The m3u8/plain split of the single player's source construction,
     * WITHOUT the disk cache (see the class KDoc — the shared LRU
     * belongs to the single player's seamless-restart window).
     */
    private fun buildMediaSource(url: String): MediaSource {
        val httpFactory = OkHttpDataSource.Factory(okHttp)
            .setDefaultRequestProperties(mapOf("User-Agent" to USER_AGENT))
        val item = MediaItem.Builder().setUri(url).build()
        return if (url.contains("m3u8")) {
            HlsMediaSource.Factory(httpFactory).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(httpFactory).createMediaSource(item)
        }
    }

    /**
     * THE AUDIO ROUTING — exactly one audible cell, and (v2.4.0) at
     * most one cell that even CLAIMS an audio decoder:
     *   focused EXO cell → volume 1 + setAudioAttributes(attrs, true)
     *               + the audio track ENABLED (a live track
     *               re-selection — the sound joins without a player
     *               rebuild or a stream restart);
     *   other EXO cells → volume 0 + handleAudioFocus=false + the
     *               audio track type DISABLED — the audio decoder is
     *               RELEASED back to the box's codec farm the instant
     *               focus moves away ([MultiScreenGrid.claimsAudio]);
     *   VLC cells → volume only (software audio — no farm to exhaust).
     */
    private fun applyAudioRouting() {
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        for (slot in holders.indices) {
            val holder = holders.getOrNull(slot) ?: continue
            val exo = holder.exoPlayer ?: continue
            try {
                val audioTrackDisabled = !MultiScreenGrid.claimsAudio(slot, focusedSlot)
                if (exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO) != audioTrackDisabled) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, audioTrackDisabled)
                        .build()
                }
                if (slot == focusedSlot) {
                    exo.volume = 1f
                    exo.setAudioAttributes(attrs, /* handleAudioFocus = */ true)
                } else {
                    exo.volume = 0f
                    exo.setAudioAttributes(attrs, /* handleAudioFocus = */ false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "audio routing slot $slot", e)
            }
        }
        applyVlcRouting()
    }

    /** v2.4.0 — the VLC cells' leg of the audio routing (volume only). */
    private fun applyVlcRouting() {
        for (slot in holders.indices) {
            val holder = holders.getOrNull(slot) ?: continue
            val vlc = holder.vlcPlayer ?: continue
            try {
                vlc.volume = if (MultiScreenGrid.claimsAudio(slot, focusedSlot)) VLC_VOLUME_FULL else VLC_VOLUME_MUTED
            } catch (e: Exception) {
                Log.w(TAG, "vlc volume slot $slot", e)
            }
        }
    }

    private fun publish() {
        _cells.value = holders.mapIndexed { slot, holder ->
            holder?.let {
                CellUi(
                    slot = slot,
                    channel = it.channel,
                    status = it.status,
                    ttffMs = it.ttffMs,
                    failureKind = if (it.status == CellStatus.ERROR) it.failureKind else FailureKind.NONE,
                    engine = it.engine
                )
            }
        }.filterNotNull()
    }

    companion object {
        private const val TAG = "MultiScreen"
        private const val USER_AGENT = "IPTVPlayer/1.0 (Android; Media3 MultiScreen)"
        /** ≈ 24 MB per cell — four cells stay under ~96 MB of buffering. */
        private const val CELL_TARGET_BUFFER_BYTES = 24 * 1024 * 1024
        /**
         * v2.4.0 — the grid VLC context's caching budget. Leaner than
         * the single player's deep-live buffers (a grid favors instant
         * parallel starts over single-stream CDN immunity — the same
         * trade the Exo cells make with their 1.5s/15s buffers).
         */
        private const val VLC_CELL_CACHING_MS = 1_000
        /** v2.4.0 — libVLC's int volume scale: the audible cell's level. */
        private const val VLC_VOLUME_FULL = 100
        /** v2.4.0 — libVLC's int volume scale: a muted cell's level. */
        private const val VLC_VOLUME_MUTED = 0
        // v2.4.0 — the per-cell timeout budgets moved to
        // [MultiScreenGrid.cellTimeoutMs] (engine-aware, pure, tested).
    }
}
