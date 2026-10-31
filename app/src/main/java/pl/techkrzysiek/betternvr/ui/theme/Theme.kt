package pl.techkrzysiek.betternvr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object RecordColors {
    val continuous = Color(0xFF3D8BFF)
    val motion = Color(0xFFFFA726)
    val alarm = Color(0xFFEF5350)
    val smart = Color(0xFFAB47BC)
    val manual = Color(0xFF66BB6A)
    val human = Color(0xFFE040FB)
    val vehicle = Color(0xFF26C6DA)
}

private val Dark = darkColorScheme(
    primary = Color(0xFF7DB2FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF1B4A85),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFFBBC7DB),
    background = Color(0xFF0F1318),
    surface = Color(0xFF0F1318),
    surfaceVariant = Color(0xFF2A3038),
    surfaceContainer = Color(0xFF1A1F26),
    error = Color(0xFFFF8A80),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1B5FBF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF001B3D),
    surfaceVariant = Color(0xFFDFE3EB),
)

@Composable
fun BetterNvrTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
