package com.agon.app.ui.focus

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged

/**
 * TvFocusUtils — Reusable D-Pad focus navigation helpers for Android TV.
 *
 * ════════════════════════════════════════════════════════════════════════
 *  PURPOSE
 * ════════════════════════════════════════════════════════════════════════
 *  Android TV remotes only have UP / DOWN / LEFT / RIGHT / OK / BACK. The
 *  default Jetpack Compose focus traversal is "nearest-neighbour by bounding
 *  box", which on a 4K TV with a 22k-channel grid causes the focus to jump
 *  to unpredictable rows, freeze at row boundaries, or escape popups.
 *
 *  This file exposes four composable helpers that solve the seven focus
 *  defects reported by QA — without touching any business logic:
 *
 *    1. [rememberFocusIndex] — preserves the focused child index across
 *       LazyList scroll-out / scroll-in (solves the "focus jumps back to the
 *       first item when scrolling fast" defect).
 *
 *    2. [Modifier.autoRequestFocus] — auto-requests focus the first time a
 *       composable enters composition (solves the "focus disappears when the
 *       player controls fade in" defect).
 *
 *    3. [rememberAutoFocusRequester] — pairs a FocusRequester with an
 *       auto-request LaunchedEffect, ideal for popup/dialog first-child.
 *
 *    4. [Modifier.focusSaver] — attaches to each item inside a LazyList so
 *       the parent's saved focus index is updated whenever the item gains
 *       focus. Combined with [Modifier.restoreFocusIfSaved], this implements
 *       the "remember which row was focused when scrolling fast" feature.
 *
 *  All helpers are stateless from the caller's perspective — they manage
 *  their own state via `rememberSaveable` so the saved focus index survives
 *  configuration changes and process death.
 * ════════════════════════════════════════════════════════════════════════
 */

/**
 * Holder for the last-focused child index in a scrollable list. The value
 * is persisted across scroll-out via `rememberSaveable` so when the user
 * scrolls back, the previously-focused row is restored instead of falling
 * back to row 0.
 */
@Composable
fun rememberFocusIndex(key: String = "default"): MutableState<Int> {
    return rememberSaveable(key = key, saver = Saver(
        save = { mutableIntStateOf(it.value) },
        restore = { mutableIntStateOf(it.value) }
    )) {
        mutableIntStateOf(0)
    }
}

/**
 * Auto-requests focus the first time the composable enters composition
 * (and only the first time — re-composition does NOT re-request focus).
 *
 * Use on the FIRST focusable element inside a player controls overlay,
 * popup, or dialog so the D-Pad lands inside the new surface immediately.
 *
 * Example:
 * ```
 * val requester = remember { FocusRequester() }
 * Box(
 *     Modifier
 *         .focusRequester(requester)
 *         .autoRequestFocus(requester)
 *         .focusable()
 * )
 * ```
 */
fun Modifier.autoRequestFocus(
    requester: FocusRequester,
    delayMs: Long = 0L
): Modifier = composed {
    val alreadyRequested = remember { mutableStateOf(false) }
    if (!alreadyRequested.value) {
        LaunchedEffect(Unit) {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            runCatching { requester.requestFocus() }
            alreadyRequested.value = true
        }
    }
    this
}

/**
 * Convenience: creates a [FocusRequester] AND auto-requests focus on it
 * in the same `LaunchedEffect`. Returns the requester so the caller can
 * also use it for programmatic focus shifts (e.g. moveFocus(Next) on
 * PIN entry).
 *
 * Use inside Dialog / Popup composables so the first focusable child
 * receives focus the instant the popup is shown.
 */
@Composable
fun rememberAutoFocusRequester(delayMs: Long = 0L): FocusRequester {
    val requester = remember { FocusRequester() }
    val alreadyRequested = remember { mutableStateOf(false) }
    if (!alreadyRequested.value) {
        LaunchedEffect(Unit) {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            runCatching { requester.requestFocus() }
            alreadyRequested.value = true
        }
    }
    return requester
}

/**
 * Per-item focus saver — attach to each item inside a LazyColumn / LazyRow
 * so the parent's `indexState` is updated whenever that item gains focus.
 *
 * Combined with [Modifier.restoreFocusIfSaved], this implements the
 * "remember which row was focused when scrolling fast" feature for Android TV.
 */
fun Modifier.focusSaver(
    index: Int,
    indexState: MutableState<Int>
): Modifier = composed {
    this.onFocusChanged { state ->
        if (state.isFocused) {
            indexState.value = index
        }
    }
}

/**
 * Per-item focus restorer — attach to each item inside a LazyColumn / LazyRow.
 * If this item's index matches the saved focus index, the FocusRequester
 * automatically requests focus when the item enters composition.
 *
 * This is the restoration half of [Modifier.focusSaver]. Place it BEFORE
 * [Modifier.focusSaver] in the chain so the restoration LaunchedEffect is
 * attached on the same modifier node as the saver.
 */
fun Modifier.restoreFocusIfSaved(
    index: Int,
    savedIndex: Int,
    requester: FocusRequester
): Modifier = composed {
    LaunchedEffect(index, savedIndex) {
        if (index == savedIndex) {
            runCatching { requester.requestFocus() }
        }
    }
    this
}
