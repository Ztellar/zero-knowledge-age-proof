package cl.zkage.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cl.zkage.app.R

/**
 * The palette of the 2026-10-29 redesign: white and blue, with green and red kept for
 * "accepted" and "rejected". Every text pair passes WCAG AA; the ratios are in the redesign
 * canvas notes (text 16.4:1 light, 14.9:1 dark).
 */
@Immutable
data class AppColors(
    val background: Color,
    val text: Color,
    val muted: Color,
    val primary: Color,
    val onPrimary: Color,
    /** Light tint behind step numbers and info panels. */
    val tint: Color,
    val line: Color,
    val success: Color,
    val successTint: Color,
    val error: Color,
    val errorTint: Color,
    /** The proof card on the ready screen. */
    val card: Color,
    val onCard: Color,
    val onCardMuted: Color,
    /** The background rings: outline, highlighted arc and center disc. */
    val ring: Color,
    val ringAccent: Color,
    val ringFill: Color,
)

val LightColors = AppColors(
    background = Color(0xFFFFFFFF),
    text = Color(0xFF0F1F3D),
    muted = Color(0xFF45557A),
    primary = Color(0xFF1846C4),
    onPrimary = Color(0xFFFFFFFF),
    tint = Color(0xFFEAF1FE),
    line = Color(0xFFDCE4F3),
    success = Color(0xFF146C43),
    successTint = Color(0xFFE3F2E9),
    error = Color(0xFFB3261E),
    errorTint = Color(0xFFFDECEA),
    card = Color(0xFF1846C4),
    onCard = Color(0xFFFFFFFF),
    onCardMuted = Color(0xFFD6E2FF),
    ring = Color(0xFFD3DEF3),
    ringAccent = Color(0xFF9DB5EC),
    ringFill = Color(0xFFEAF1FE),
)

val DarkColors = AppColors(
    background = Color(0xFF0F1A2E),
    text = Color(0xFFE8EEF9),
    muted = Color(0xFFA7B4D0),
    primary = Color(0xFF8FB0FF),
    onPrimary = Color(0xFF0B1426),
    tint = Color(0xFF16243F),
    line = Color(0xFF2A3B5E),
    success = Color(0xFF6FD39B),
    successTint = Color(0xFF15301F),
    error = Color(0xFFFF9C94),
    errorTint = Color(0xFF3A1A1A),
    card = Color(0xFF1F3F9E),
    onCard = Color(0xFFFFFFFF),
    onCardMuted = Color(0xFFD6E2FF),
    ring = Color(0xFF24365A),
    ringAccent = Color(0xFF3D5C99),
    ringFill = Color(0xFF16243F),
)

val LocalAppColors = staticCompositionLocalOf { LightColors }

/** Atkinson Hyperlegible Next (SIL OFL 1.1), drawn for readers with low vision. */
@OptIn(ExperimentalTextApi::class)
private val Atkinson = FontFamily(
    listOf(400, 600, 700).map { weight ->
        Font(
            R.font.atkinson_hyperlegible_next,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    },
)

/** Sizes in sp, so they follow the phone's font size up to 200%. */
private val AppTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontFamily = Atkinson, fontSize = 18.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontFamily = Atkinson, fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontFamily = Atkinson, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    val material = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = colors.primary, onPrimary = colors.onPrimary, background = colors.background, surface = colors.background, onBackground = colors.text, onSurface = colors.text, error = colors.error)
    } else {
        lightColorScheme(primary = colors.primary, onPrimary = colors.onPrimary, background = colors.background, surface = colors.background, onBackground = colors.text, onSurface = colors.text, error = colors.error)
    }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = material, typography = AppTypography, content = content)
    }
}

object App {
    val colors: AppColors @Composable get() = LocalAppColors.current
}
