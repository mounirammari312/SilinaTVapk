package com.superz.iptvplayer.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superz.iptvplayer.ui.theme.AccentIndigoLight
import com.superz.iptvplayer.ui.theme.GlassSurface
import com.superz.iptvplayer.ui.theme.TextMuted

// ─────────────────────────────────────────────────────────────────
// v1.4.11 — the old Deep-Space LoginScreen was REPLACED by the
// reference's login flow (AddPortalScreen → EditPortalScreen; v1.19.12
// removed the onboarding pages — the intro slider and the first-run
// language grid); the engine-error mapping moved into EditPortalScreen.kt.
// The login ENGINE (LoginViewModel) is byte-untouched and is now driven
// by EditPortalScreen.
//
// This file keeps ONLY LoginField — the project-styled glassmorphic
// text field that PlayerScreen (engine, byte-protected) imports and
// uses. Its import path and behavior stay exactly as they were.
// ─────────────────────────────────────────────────────────────────

/**
 * Login field — reference design: 40dp height, CircleShape, glass bg,
 * 1dp white-60% border (focused: 1.5dp indigo light), 12sp text
 */
@Composable
fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CircleShape)
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = if (isFocused) AccentIndigoLight else Color.White.copy(alpha = 0.6f),
                shape = CircleShape
            )
            .background(
                GlassSurface.copy(alpha = if (isFocused) 0.75f else 0.50f),
                CircleShape
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
                imeAction = imeAction
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
        )
        if (value.isEmpty()) {
            Text(hint, color = TextMuted.copy(alpha = 0.8f), fontSize = 11.sp)
        }
    }
}
