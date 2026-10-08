package com.superz.iptvplayer.ui.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import com.superz.iptvplayer.ui.settings.VuSettingsPrefs
import java.util.Locale

/**
 * v1.7.0 — "Active Subtitle" made REAL.
 *
 * The reference stores the toggle (GetSharedAppInfo's GeneralSettingMenu
 * "Active Subtitle") but never wires it to its ExoPlayer — a dead setting.
 * Ours actually drives the player: when ON, the active ExoPlayer's track
 * selection is nudged so an embedded text track (DVB / teletext / WebVTT /
 * TTML…) gets SELECTED and rendered by the PlayerView's built-in subtitle
 * view; when OFF, text tracks are explicitly disabled (Media3 selects none
 * by default, but the explicit disable guarantees the off state even after
 * a mid-session toggle).
 *
 * v1.16.0 — THE SIDE-LOAD FIX. The v1.15.0 subtitle feature (CC panel →
 * SubtitleRepository → injected .srt) never rendered: every onPlaying()
 * calls apply() and, with the general toggle defaulting to OFF, it issued
 * setTrackTypeDisabled(TRACK_TYPE_TEXT, true) — a disabled track TYPE can
 * never be selected, so the side-loaded .srt sat there unselected while
 * the UI honestly reported "Subtitles: on". Additionally apply() overwrote
 * preferredTextLanguages with the app-language ladder, dropping any
 * side-loaded language outside it. Fix: the side-loaded language lives in
 * [sideLoadedLanguage] (set by PlayerViewModel on inject, cleared on
 * clear/zap) and (a) forces the text TYPE enabled, (b) is prepended to the
 * preference ladder so it always wins. Held OUTSIDE the UI state because
 * ChannelViewViewModel's zaps/activations also call apply() and must see
 * the same truth.
 *
 * Iron-rule compliance: SmartPlayer.kt / PlayerScreen.kt are untouched —
 * the parameters are applied from the ViewModels (ChannelViewViewModel,
 * PlayerViewModel) onto the PUBLIC `activeExoForView` instance, and they
 * persist on that instance across zaps and across the split→fullscreen
 * hand-over (the session keeps the same ExoPlayer). VLC-fallback playback
 * has no public accessor and simply stays subtitle-less, like today.
 */
object SubtitleActivation {

    /**
     * v1.16.0 — the language code of the player's side-loaded external
     * subtitle (the CC panel), or null when none is active. Owned by
     * PlayerViewModel (set on inject, cleared on clear / channel change).
     */
    @Volatile
    var sideLoadedLanguage: String? = null

    /**
     * The preferred-text-language ladder: the app's chosen language first
     * (the intro/settings language), the device locale second, English as
     * the universal fallback. "und" tracks are accepted via
     * selectUndeterminedLanguage so unlabeled tracks still render.
     */
    fun preferredSubtitleLanguages(appLangTag: String?, deviceLang: String? = Locale.getDefault().language): List<String> {
        val out = LinkedHashSet<String>()
        appLangTag?.takeIf { it.isNotBlank() }?.let { out += it }
        deviceLang?.takeIf { it.isNotBlank() && it != "_" }?.let { out += it }
        out += "en"
        return out.toList()
    }

    /**
     * Apply the setting to the given ExoPlayer instance (no-op when null —
     * e.g. the engine is currently VLC or nothing has started yet).
     * v1.16.0: a side-loaded subtitle overrides the general toggle — text
     * stays ENABLED and the side-loaded language tops the ladder.
     */
    fun apply(player: Player?, context: Context) {
        val p = player ?: return
        val on = sideLoadedLanguage != null || VuSettingsPrefs.activeSubtitle(context)
        val langTag = context.getSharedPreferences("vu_prefs", Context.MODE_PRIVATE)
            .getString("lang", null)
        applyTo(p, on, effectiveLanguages(sideLoadedLanguage, langTag, Locale.getDefault().language))
    }

    /**
     * v1.16.0 — the merged preference ladder: the side-loaded language
     * FIRST (the user picked it explicitly in the player — it outranks the
     * general preference), then the classic ladder without duplicates.
     * Pure function so tests can pin the merge semantics without a device
     * Player.
     */
    fun effectiveLanguages(
        sideLoaded: String?,
        appLangTag: String?,
        deviceLang: String? = Locale.getDefault().language
    ): List<String> {
        val ladder = preferredSubtitleLanguages(appLangTag, deviceLang)
        val sl = sideLoaded?.takeIf { it.isNotBlank() } ?: return ladder
        return listOf(sl) + ladder.filter { !it.equals(sl, ignoreCase = true) }
    }

    /**
     * The pure parameter mutation — separated so tests can pin the
     * on/off semantics without a device Player.
     */
    fun applyTo(player: Player, on: Boolean, preferredLanguages: List<String>) {
        val builder: TrackSelectionParameters.Builder = player.trackSelectionParameters.buildUpon()
        if (on) {
            builder
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguages(*preferredLanguages.toTypedArray())
                .setSelectUndeterminedTextLanguage(true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        player.trackSelectionParameters = builder.build()
    }
}
