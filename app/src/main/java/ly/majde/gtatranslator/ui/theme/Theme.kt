package ly.majde.gtatranslator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// "Vice City sunset" palette: neon pink, ocean teal and sunset orange on a night sky.
val NeonPink = Color(0xFFFF4FA3)
val OceanTeal = Color(0xFF2EE6D6)
val SunsetOrange = Color(0xFFFF9F43)
private val NightSky = Color(0xFF0E0B16)
private val Ink = Color(0xFFF2EEF8)

private val ViceColors = darkColorScheme(
    primary = NeonPink,
    onPrimary = Color(0xFF2A0016),
    primaryContainer = Color(0xFF5A1740),
    onPrimaryContainer = Color(0xFFFFD8E8),
    secondary = OceanTeal,
    onSecondary = Color(0xFF00201D),
    secondaryContainer = Color(0xFF0F4A45),
    onSecondaryContainer = Color(0xFFB8FFF6),
    tertiary = SunsetOrange,
    onTertiary = Color(0xFF2B1500),
    background = NightSky,
    onBackground = Ink,
    surface = Color(0xFF17121F),
    onSurface = Ink,
    surfaceVariant = Color(0xFF261E33),
    onSurfaceVariant = Color(0xFFCFC6DA),
    surfaceContainerLowest = Color(0xFF0B0911),
    surfaceContainerLow = Color(0xFF15111C),
    surfaceContainer = Color(0xFF1B1524),
    surfaceContainerHigh = Color(0xFF231B2E),
    surfaceContainerHighest = Color(0xFF2C2338),
    outline = Color(0xFF6D6080),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
    errorContainer = Color(0xFF4A1416),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun GtaTranslatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ViceColors, content = content)
}
