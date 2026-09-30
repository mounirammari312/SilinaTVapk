package com.agon.app.ui.util

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.agon.app.R

/**
 * LogoPlaceholder — V8.5.2 unified brand placeholder.
 *
 * Renders the DOH app logo ([R.drawable.ic_logo]) centered on a dark
 * background, fitted (not cropped) so the full logo is always visible.
 *
 * Used as the [coil.compose.AsyncImage] / [coil.compose.SubcomposeAsyncImage]
 * `error` slot AND as the "no logo URL" fallback for channel / movie /
 * series cards across the dashboard, hero slider, feed and player. This
 * guarantees that any content without a cover image shows the app's brand
 * logo instead of a broken/empty card or a generic gradient.
 *
 * @param modifier usually `Modifier.fillMaxSize()` so the logo fills the card.
 */
@Composable
fun LogoPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color(0xFF0B0B12)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = "DOH",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}
