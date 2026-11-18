package com.jrweb.avangvpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkBg = Color(0xFF0D1117)
val CardBg = Color(0xFF161B22)
val AccentCyan = Color(0xFF00E5FF)
val ConnectedGreen = Color(0xFF00E676)
val DangerRed = Color(0xFFFF5252)
val TextPrimary = Color(0xFFF0F6FC)
val TextSecondary = Color(0xFF8B949E)

private val AvangColorScheme = darkColorScheme(
    primary = AccentCyan,
    secondary = ConnectedGreen,
    background = DarkBg,
    surface = CardBg,
    onPrimary = DarkBg,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun AvangVpnTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AvangColorScheme, content = content)
}