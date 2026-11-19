package ly.majde.gtatranslator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ly.majde.gtatranslator.R

// "Vice City sunset": neon pink, ocean teal and sunset orange on a deep night sky.
val NeonPink = Color(0xFFFF4FA3)
val OceanTeal = Color(0xFF2EE6D6)
val SunsetOrange = Color(0xFFFF9F43)
val NightSky = Color(0xFF0B0812)
val Ink = Color(0xFFF4F0FA)

val SunsetBrush = Brush.linearGradient(listOf(NeonPink, SunsetOrange))
val OceanBrush = Brush.linearGradient(listOf(OceanTeal, Color(0xFF6C7BFF)))
val NightBrush = Brush.verticalGradient(listOf(Color(0xFF1A0F2E), NightSky, NightSky))

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
    surface = Color(0xFF15101E),
    onSurface = Ink,
    surfaceVariant = Color(0xFF261E33),
    onSurfaceVariant = Color(0xFFC9C0D6),
    surfaceContainerLowest = Color(0xFF09070E),
    surfaceContainerLow = Color(0xFF130E1B),
    surfaceContainer = Color(0xFF1A1424),
    surfaceContainerHigh = Color(0xFF221A2E),
    surfaceContainerHighest = Color(0xFF2B2239),
    outline = Color(0xFF5E5272),
    outlineVariant = Color(0xFF3A3047),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
    errorContainer = Color(0xFF4A1416),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Tajawal (SIL Open Font License) — clean, modern Arabic + Latin. */
val Tajawal = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_bold, FontWeight.Bold),
    Font(R.font.tajawal_extrabold, FontWeight.ExtraBold),
)

private fun TextStyle.tajawal() = copy(fontFamily = Tajawal)

private val base = Typography()

private val AppTypography = Typography(
    displayLarge = base.displayLarge.tajawal(),
    displayMedium = base.displayMedium.tajawal(),
    displaySmall = base.displaySmall.tajawal(),
    headlineLarge = base.headlineLarge.tajawal(),
    headlineMedium = base.headlineMedium.tajawal(),
    headlineSmall = base.headlineSmall.tajawal(),
    titleLarge = base.titleLarge.tajawal(),
    titleMedium = base.titleMedium.tajawal(),
    titleSmall = base.titleSmall.tajawal(),
    bodyLarge = base.bodyLarge.tajawal(),
    bodyMedium = base.bodyMedium.tajawal(),
    bodySmall = base.bodySmall.tajawal(),
    labelLarge = base.labelLarge.tajawal(),
    labelMedium = base.labelMedium.tajawal(),
    labelSmall = base.labelSmall.tajawal(),
)

@Composable
fun GtaTranslatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ViceColors, typography = AppTypography, content = content)
}
