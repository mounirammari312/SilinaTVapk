package com.superz.iptvplayer.player.multiscreen

import android.content.Context
import com.superz.iptvplayer.data.db.Channel

/**
 * ═══════════════════════════════════════════════════════════════════
 *  v2.3.0 — MULTI-SCREEN hand-off + persistence.
 *
 *  [MultiScreenSession] — the setup screen → playback screen hand-off
 *  (the VodPlayRegistry pattern): the setup composes the final config
 *  (layout + assigned channels), parks it here, and navigates; the
 *  playback route consumes it exactly once. Process-death fallback:
 *  the playback screen re-builds from [MultiScreenPrefs] when the
 *  registry is empty (the config is persisted at the same moment).
 *
 *  [MultiScreenPrefs] — the LAST SESSION memory: layout name + the
 *  ordered slot keys. Re-entering the setup screen restores what the
 *  user watched last time (professional apps never make you re-pick
 *  your four screens every evening).
 * ═══════════════════════════════════════════════════════════════════
 */
object MultiScreenSession {

    /** The complete multi-screen configuration, ready to play. */
    data class Config(
        val layout: MultiLayout,
        /** Slot-ordered channels; trailing nulls trimmed by the builder. */
        val channels: List<Channel>
    )

    @Volatile
    private var pending: Config? = null

    /** Park the config the setup screen just composed (before navigating). */
    fun start(config: Config) {
        pending = config
    }

    /** Take the parked config (once — a second consumer gets null). */
    fun consume(): Config? = pending.also { pending = null }

    /** Peek without consuming (diagnostics / pre-conditions). */
    fun peek(): Config? = pending

    // ── v2.3.1 — THE SINGLE-ENGINE HAND-OFF ──────────────────────────
    //
    // THE FIELD CRASH (v2.3.0): tapping ابدأ المشاهدة closed the app.
    // The grid asked a TV box for 2-4 fresh decoders while the PARKED
    // single player — paused but never released — still held one, and
    // the codec farm answered with a fatal refusal. The professional
    // multiview contract (TiviMate / IPTV Smarters): when the grid
    // truly starts, the single screen's engine is RELEASED — decoders,
    // audio focus and GPU surfaces returned to the box BEFORE the grid's
    // own players claim their share. The single screen re-inits on its
    // next play press (its resume bake restores VOD positions), exactly
    // like returning from any multiview in the professional apps.
    //
    // The releasers register while their ViewModels live in the back
    // stack (the player screen and the channel view's mini player);
    // [releaseSingleEngines] fires them all the instant the grid starts.

    private val engineReleasers = mutableListOf<() -> Unit>()

    /**
     * Register a single-screen engine releaser. Returns the UNREGISTER
     * action — call it from the owner's onCleared so a dead screen never
     * releases a recycled engine twice.
     */
    fun registerEngineReleaser(releaser: () -> Unit): () -> Unit {
        synchronized(engineReleasers) { engineReleasers.add(releaser) }
        return {
            synchronized(engineReleasers) { engineReleasers.remove(releaser) }
        }
    }

    /**
     * Release every registered single-screen engine (the grid is about
     * to start). Safe any time, from anywhere, idempotent per owner.
     */
    fun releaseSingleEngines() {
        val snapshot: List<() -> Unit>
        synchronized(engineReleasers) {
            snapshot = engineReleasers.toList()
            engineReleasers.clear()
        }
        for (release in snapshot) {
            try {
                release()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * The persisted last-session memory. Keys are stored as ONE ordered
 * string — slot order is the whole point, so a Set would corrupt it.
 * The separator is '\u241F' (␟ SYMBOL FOR UNIT SEPARATOR): channel keys
 * are stream ids ("531"), portal keys ("k:531") and URL hashes — none
 * of them can ever contain it.
 */
object MultiScreenPrefs {

    private const val FILE = "multiscreen_prefs"
    private const val KEY_LAYOUT = "layout"
    private const val KEY_SLOTS = "slot_keys"
    private const val SEPARATOR = "␟"

    // ── pure encode/decode (unit-tested on the JVM) ─────────────────

    /** Ordered keys → one persisted string; empty slots are skipped. */
    fun encodeKeys(keys: List<String?>): String =
        keys.filterNotNull().filter { it.isNotBlank() }.joinToString(SEPARATOR)

    /** The persisted string → the ordered key list (empty on garbage). */
    fun decodeKeys(raw: String?): List<String> =
        raw?.split(SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()

    // ── device side ──────────────────────────────────────────────────

    fun save(context: Context, layout: MultiLayout, keys: List<String?>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAYOUT, layout.name)
            .putString(KEY_SLOTS, encodeKeys(keys))
            .apply()
    }

    fun loadLayout(context: Context): MultiLayout? =
        MultiLayout.fromName(
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LAYOUT, null)
        )

    fun loadKeys(context: Context): List<String> =
        decodeKeys(
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_SLOTS, null)
        )

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
