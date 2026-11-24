package com.vagabundo.telemoto.ui

import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** Tasarım token'ları (kanvastaki koyu/açık temalarla aynı). */
data class TmColors(
    val light: Boolean,
    val bg: Color, val surf: Color, val well: Color, val line: Color, val tick: Color,
    val ink: Color, val mute: Color, val accInk: Color,
    val left: Color, val right: Color, val leftTrack: Color, val rightTrack: Color,
    val red: Color, val redBg: Color, val redFill: Color,
    val green: Color, val ecoBg: Color, val perfBg: Color, val neutral: Color,
)

val Amber = Color(0xFFFFB020)
val OnAmber = Color(0xFF0B0E11)

val DarkTm = TmColors(
    light = false,
    bg = Color(0xFF0B0E11), surf = Color(0xFF12171C), well = Color(0xFF0E1317), line = Color(0xFF2A323B), tick = Color(0xFF55616D),
    ink = Color(0xFFE8EDF2), mute = Color(0xFF9AA6B2), accInk = Amber,
    left = Color(0xFF6FB1FF), right = Color(0xFFFF9A57), leftTrack = Color(0xFF1F3A5C), rightTrack = Color(0xFF5A2E14),
    red = Color(0xFFFF6B6B), redBg = Color(0xFF2A1215), redFill = Color(0xFFD62D2D),
    green = Color(0xFF5BD18B), ecoBg = Color(0xFF12291C), perfBg = Color(0xFF2E2410), neutral = Color(0xFF55616D),
)

val LightTm = TmColors(
    light = true,
    bg = Color(0xFFEEF1F4), surf = Color(0xFFFFFFFF), well = Color(0xFFE8ECF0), line = Color(0xFFCBD3DB), tick = Color(0xFF8994A0),
    ink = Color(0xFF0E1418), mute = Color(0xFF4A5561), accInk = Color(0xFF8A5200),
    left = Color(0xFF1F5FBF), right = Color(0xFFB84A0E), leftTrack = Color(0xFFD3E2F7), rightTrack = Color(0xFFF6DCCB),
    red = Color(0xFFC21F1F), redBg = Color(0xFFFBE3E3), redFill = Color(0xFFC21F1F),
    green = Color(0xFF157A3E), ecoBg = Color(0xFFDCF1E4), perfBg = Color(0xFFFFF1D6), neutral = Color(0xFF9AA6B2),
)

val LocalTm = staticCompositionLocalOf { DarkTm }

/** Gösterge sayıları için dar (condensed) yazı. */
val Condensed: FontFamily = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))

@Composable
fun TelemotoTheme(light: Boolean, content: @Composable () -> Unit) {
    val tm = if (light) LightTm else DarkTm
    val scheme = if (light) lightColorScheme(
        primary = Amber, onPrimary = OnAmber, background = tm.bg, surface = tm.surf,
        onBackground = tm.ink, onSurface = tm.ink, surfaceVariant = tm.well, onSurfaceVariant = tm.mute, outline = tm.line,
    ) else darkColorScheme(
        primary = Amber, onPrimary = OnAmber, background = tm.bg, surface = tm.surf,
        onBackground = tm.ink, onSurface = tm.ink, surfaceVariant = tm.well, onSurfaceVariant = tm.mute, outline = tm.line,
    )
    CompositionLocalProvider(LocalTm provides tm) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
