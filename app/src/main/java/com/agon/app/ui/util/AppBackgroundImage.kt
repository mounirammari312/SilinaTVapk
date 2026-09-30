package com.agon.app.ui.util

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.agon.app.R

/**
 * AppBackgroundImage — V8.5.4 full-screen brand wallpaper.
 *
 * Renders the DOH app background ([R.drawable.app_bg]) cropped to fill the
 * screen, with a semi-transparent black scrim on top so foreground content
 * (forms, cards, text) stays legible. Drop this as the FIRST child of a
 * screen's root `Box` (or inside a wrapper `Box`) to brand the screen.
 *
 * @param scrimAlpha darkness of the overlay (0 = no scrim, 1 = solid black).
 *                   Default 0.5f keeps the wallpaper visible while ensuring
 *                   text contrast.
 */
@Composable
fun AppBackgroundImage(scrimAlpha: Float = 0.5f) {
    Image(
        painter = painterResource(R.drawable.app_bg),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
    if (scrimAlpha > 0f) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha)))
    }
}
