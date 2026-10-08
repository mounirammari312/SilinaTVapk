package com.superz.iptvplayer.ui.catchup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superz.iptvplayer.R
import com.superz.iptvplayer.data.epg.EpgProgram
import com.superz.iptvplayer.data.epg.toEpgTimeLabel
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.VuPremiumLogo
import com.superz.iptvplayer.ui.theme.VuTopBarAnchor
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** text_tint_color (#9f94a4) — the reference's secondary text. */
private val TextTint = Color(0xFF9F94A4)

/** colorOrange (#f57d67) — the reference's SELECTED tab background. */
private val TabSelected = Color(0xFFF57D67)

/** black_65 (#a6000000) — the tab strip + unselected tab background. */
private val TabUnselected = Color(0xA6000000)

/**
 * v1.12.3 — the day-tabbed Catch-Up detail screen, REPLICATED 1:1 from the
 * reference's activity_catch_up_detail.xml + fragment_catch_up_detail.xml +
 * item_catch_detail.xml (user request: "the design is completely different
 * from the reference — copy it exactly"):
 *
 *  header: ly_back + PremiumLL + CENTERED txt_header ("CATCH UP/Live" —
 *  the reference's static catch_up_live title) + tx_system_time.
 *
 *  rlDataView (40sdp side margins): tab_layout — a TabLayout with
 *  tabGravity=fill + tabMaxWidth=0dp (THREE EQUAL-WIDTH tabs), selected tab
 *  background #F57D67 (colorOrange), unselected #A6000000 (black_65), white
 *  15ssp labels, white indicator strip under the selected tab — then the
 *  day's program list.
 *
 *  item_catch_detail rows: program name 15sdp (single line) on top, then
 *  the clock icon (15×15sdp, VISIBLE only when mark_archive = 1) + the time
 *  range 13sdp ("HH:mm ~ HH:mm" from the row's t_time/time_to). All text
 *  #9F94A4 (text_tint_color) → white when the row is focused/selected —
 *  the reference's exact tint language.
 *
 *  text_view_default ("No Catch Up Found", centered) for empty days;
 *  progress_bar (25sdp centered) while a table loads.
 *
 *  The play hand-off (vodc:/vodx: synthetic channels through the
 *  VodPlayRegistry — a FRESH tv_archive create_link per open, exactly the
 *  reference's CatchUpPlayActivity) and the XC day-bucket logic are
 *  UNCHANGED from v1.12.1 (see CatchUpDetailViewModel).
 */
@Composable
fun CatchUpDetailScreen(
    playlistId: Long,
    channelKey: String,
    onBack: () -> Unit,
    onPlay: (playlistId: Long, channelKey: String) -> Unit,
    viewModel: CatchUpDetailViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsState()
    val s = rememberVuSdp()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VuPalette.BackgroundBrush)
    ) {
        // ══ Top bar: ly_back + PremiumLL + txt_header + tx_system_time ══
        var premiumH by remember { mutableStateOf(androidx.compose.ui.unit.Dp.Unspecified) }
        val premiumHeight = premiumH.takeUnless { it == androidx.compose.ui.unit.Dp.Unspecified }
            ?: s.d(VuTopBarAnchor.LOGO_HEIGHT_FALLBACK_SDP)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = s.d(VuTopBarAnchor.PREMIUM_MARGIN_TOP_SDP))
        ) {
            com.superz.iptvplayer.ui.theme.VuBackButton(onClick = onBack)
            VuPremiumLogo(
                modifier = Modifier.padding(start = s.d(10)),
                onHeight = { premiumH = it }
            )
        }
        // txt_header — the reference's STATIC catch_up_live title, centered.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = s.d(VuTopBarAnchor.PREMIUM_MARGIN_TOP_SDP))
                .height(premiumHeight)
        ) {
            Text(
                stringResource(R.string.vu_catch_up_live),
                color = VuPalette.White,
                fontSize = s.t(13),
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
        DetailClock(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = s.d(VuTopBarAnchor.PREMIUM_MARGIN_TOP_SDP))
                .height(premiumHeight)
                .padding(end = s.d(10))
        )

        // ══ rlDataView — margins: start/end 40sdp, top 10sdp, bottom 20sdp ══
        val bodyTop = s.d(VuTopBarAnchor.premiumBandBottomSdp(null)) + s.d(10)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = bodyTop,
                    start = s.d(40),
                    end = s.d(40),
                    bottom = s.d(20)
                )
        ) {
            // ── tab_layout: equal-width day tabs (tabGravity fill) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TabUnselected)
            ) {
                ui.days.forEach { (date, label) ->
                    val selected = date == ui.selectedDay
                    var focused by remember(label) { mutableStateOf(false) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (selected) Modifier.background(TabSelected)
                                else if (focused) Modifier.background(TabSelected.copy(alpha = 0.7f))
                                else Modifier.background(TabUnselected)
                            )
                            .onFocusChanged { focused = it.isFocused }
                            .focusable()
                            .clickable { viewModel.selectDay(date) }
                            .padding(vertical = s.d(8))
                    ) {
                        Text(
                            label,
                            color = VuPalette.White,
                            fontSize = s.ts(15),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // tabIndicatorColor = white — the selected tab's strip.
                        Box(
                            modifier = Modifier
                                .padding(top = s.d(3))
                                .width(s.d(40))
                                .height(s.d(2))
                                .background(
                                    if (selected) VuPalette.White
                                    else Color.Transparent
                                )
                        )
                    }
                }
            }

            // ── view_pager → recycler_catch_detail: the day's programs ──
            Box(modifier = Modifier.weight(1f)) {
                when {
                    ui.loading -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        CircularProgressIndicator(
                            color = VuPalette.Cyan,
                            strokeWidth = s.d(2),
                            modifier = Modifier.size(s.d(25))
                        )
                    }
                    ui.programs.isEmpty() -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // text_view_default — 20ssp, centered.
                        Text(
                            stringResource(R.string.vu_no_catchup_found),
                            color = VuPalette.White,
                            fontSize = s.ts(12),
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                    else -> {
                        val listState = rememberLazyListState()
                        val currentIdx = ui.programs.indexOfFirst { it.isNow(nowMs) }
                        LaunchedEffect(ui.selectedDay) {
                            // Open the day at the CURRENT program (TV comfort).
                            if (currentIdx > 1) listState.scrollToItem(currentIdx - 1)
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(
                                ui.programs,
                                key = { i, p -> "p-${p.startMs}-$i-${p.fileId}" }
                            ) { _, program ->
                                VuProgramRow(
                                    program = program,
                                    catchUpCapable = ui.catchUpCapable,
                                    onClick = {
                                        viewModel.playProgram(program) { key ->
                                            onPlay(playlistId, key)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * item_catch_detail.xml: txt_name (15sdp, singleLine) on top; below it the
 * image_clock (15×15sdp — VISIBLE only when mark_archive = 1) and txt_time
 * ("HH:mm ~ HH:mm", 13sdp, 15sdp start gap). Tints: #9F94A4 (text_tint_
 * color) → white when focused/selected. Paddings: 15sdp top / 10sdp bottom /
 * 20sdp sides (the item's own padding).
 */
@Composable
private fun VuProgramRow(
    program: EpgProgram,
    catchUpCapable: Boolean,
    onClick: () -> Unit
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val highlight = focused
    val tintColor = if (highlight) VuPalette.White else TextTint

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // Reference behavior: any row with a file id may be clicked —
            // the portal's create_link decides (CatchUpPlayActivity passes
            // every program through; unarchived ones fail there).
            // v1.18.0: TimeMachine rows (catchupUrl != null) are ALWAYS
            // clickable — they exist precisely because the panel flags no
            // archive (catchUpCapable would be false), and their URL is
            // already built (the server decides if it plays).
            .clickable(
                enabled = (program.fileId != null && catchUpCapable) ||
                    program.catchupUrl != null,
                onClick = onClick
            )
            .padding(
                start = s.d(20),
                end = s.d(20),
                top = s.d(15),
                bottom = s.d(10)
            )
    ) {
        // txt_name — 15sdp, singleLine, ellipsized.
        Text(
            program.title,
            color = tintColor,
            fontSize = s.t(15),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // image_clock (mark_archive = 1) + txt_time — "t_time ~ time_to".
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = s.d(5))
        ) {
            if (program.markArchive) {
                Image(
                    painter = painterResource(R.drawable.vu_ic_clock),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(tintColor),
                    modifier = Modifier.size(s.d(15))
                )
                Spacer(Modifier.width(s.d(15)))
            }
            Text(
                "${program.startMs.toEpgTimeLabel()} ~ ${program.endMs.toEpgTimeLabel()}",
                color = tintColor,
                fontSize = s.t(13),
                maxLines = 1
            )
        }
    }
}

/** tx_system_time — "HH:mm | MMM d yyyy" 10sdp, ticks on the minute. */
@Composable
private fun DetailClock(modifier: Modifier = Modifier) {
    val s = rememberVuSdp()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(60_000L - nowMs % 60_000L)
        }
    }
    val locale = Locale.getDefault()
    val fmt = remember(locale) {
        SimpleDateFormat("HH:mm | MMM d yyyy", locale)
    }
    Text(
        fmt.format(Date(nowMs)),
        color = VuPalette.White,
        fontSize = s.t(10),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = modifier
    )
}
