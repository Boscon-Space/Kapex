package space.boscon.hab.station

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * One fixed palette, chosen once at start-up, so the UI does not shift with
 * the system theme mid-session. Neutral slate surfaces, blue primary (the
 * validated categorical slot 1), status colours reserved for status only.
 */
object Palette {
    val good = Color(0xFF1BAF7A); val warn = Color(0xFFEDA100); val bad = Color(0xFFE34948)
    val gnd = Color(0xFF2B2F36); val v5 = Color(0xFFC62828); val v33 = Color(0xFFE6A700); val pcb = Color(0xFF1F5F3A)
}

val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF3987E5), onPrimary = Color.White, primaryContainer = Color(0xFF1E3A5F), onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF8FA3BF), onSecondary = Color(0xFF0F1419),
    background = Color(0xFF0F1419), onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF151B22), onSurface = Color(0xFFE6EAF0), surfaceVariant = Color(0xFF1C242E), onSurfaceVariant = Color(0xFF9AA7B8),
    surfaceContainer = Color(0xFF1A2129), surfaceContainerHigh = Color(0xFF202933), surfaceContainerLow = Color(0xFF131920),
    outline = Color(0xFF3A4553), outlineVariant = Color(0xFF2A3340), error = Color(0xFFE66767), onError = Color.White,
)

val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF2A78D6), onPrimary = Color.White, primaryContainer = Color(0xFFDCE8FA), onPrimaryContainer = Color(0xFF0E2A4F),
    secondary = Color(0xFF4F6075), onSecondary = Color.White,
    background = Color(0xFFF6F7F9), onBackground = Color(0xFF14181E),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF14181E), surfaceVariant = Color(0xFFEEF1F5), onSurfaceVariant = Color(0xFF5B6675),
    surfaceContainer = Color(0xFFF0F2F5), surfaceContainerHigh = Color(0xFFE8EBEF), surfaceContainerLow = Color(0xFFFAFBFC),
    outline = Color(0xFFC3CAD3), outlineVariant = Color(0xFFDDE2E8), error = Color(0xFFC62828), onError = Color.White,
)
