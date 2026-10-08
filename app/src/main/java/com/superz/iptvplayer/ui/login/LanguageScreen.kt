package com.superz.iptvplayer.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.superz.iptvplayer.R
import com.superz.iptvplayer.ui.theme.VuBackButton
import com.superz.iptvplayer.ui.theme.VuPalette
import com.superz.iptvplayer.ui.theme.VuPremiumLogo
import com.superz.iptvplayer.ui.theme.rememberVuSdp
import com.superz.iptvplayer.ui.theme.vuBackground

/**
 * activity_language.xml + item_language.xml + LanguageActivity — the
 * language-selection grid, verbatim:
 *
 *  • the app-wide gradient background;
 *  • top row (marginTop 10sdp): ly_back + PremiumLL (the shared widgets);
 *  • rv_Language: 3-column grid, outer margin 20sdp; each item 180sdp wide
 *    (margin 5sdp, item_setting_bg: #33707070 radius 5sdp → gradient on
 *    focus/selection), padding L40 T10 R30 B10sdp, image_check/uncheck
 *    15sdp + name 10sdp white (marginStart 7sdp).
 *
 * Selecting a language stores the tag — the caller then relaunches through
 * the entry gate (LanguageAdapter.setLocale semantics: apply the locale and
 * start fresh, wiping the back stack), so the gate's next screen appears in
 * the new locale.
 *
 * v1.19.12 — this is the SETTINGS language page ONLY (user directive: the
 * onboarding copy is gone with the intro slider — the app follows the
 * device locale on first run), and the grid lists exactly the two locales
 * the app actually ships: English and Arabic.
 */
@Composable
fun LanguageScreen(
    initialTag: String?,
    onBack: () -> Unit,
    onSelect: (tag: String) -> Unit
) {
    val s = rememberVuSdp()
    var selectedTag by remember { mutableStateOf(initialTag ?: VuLoginFlow.DEFAULT_TAG) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .vuBackground()
    ) {
        // Top row — ly_back + PremiumLL, marginTop 10sdp (activity_language).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = s.d(10))
        ) {
            VuBackButton(onClick = onBack)
            VuPremiumLogo(modifier = Modifier.padding(start = s.d(10)))
        }

        // rv_Language — 3-column grid, margin 20sdp.
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(s.d(20)),
            horizontalArrangement = Arrangement.spacedBy(s.d(10)),
            verticalArrangement = Arrangement.spacedBy(s.d(10)),
            modifier = Modifier.fillMaxSize()
        ) {
            gridItems(VuLoginFlow.LANGUAGES, key = { it.tag }) { lang ->
                VuLanguageItem(
                    language = lang,
                    selected = lang.tag == selectedTag,
                    onClick = {
                        selectedTag = lang.tag
                        onSelect(lang.tag)
                    }
                )
            }
        }
    }
}

/** item_language.xml — 180sdp wide row, check icon + 10sdp name. */
@Composable
private fun VuLanguageItem(
    language: VuLoginFlow.VuLanguage,
    selected: Boolean,
    onClick: () -> Unit
) {
    val s = rememberVuSdp()
    var focused by remember { mutableStateOf(false) }
    val highlighted = selected || focused

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(s.d(5))
            .width(s.d(180))
            .clip(RoundedCornerShape(s.d(5)))
            .then(
                if (highlighted) Modifier.background(VuPalette.PurpleBrush)
                else Modifier.background(VuPalette.PanelOverlay)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(
                start = s.d(40), top = s.d(10),
                end = s.d(30), bottom = s.d(10)
            )
    ) {
        Image(
            painter = painterResource(
                if (selected) R.drawable.vu_image_check else R.drawable.vu_image_uncheck
            ),
            contentDescription = null,
            modifier = Modifier.size(s.d(15))
        )
        Spacer(Modifier.width(s.d(7)))
        Text(
            language.display,
            color = VuPalette.White,
            fontSize = s.t(10),
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
