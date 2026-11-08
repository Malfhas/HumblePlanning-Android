package com.humbleplanning.app.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.humbleplanning.app.data.ThemeMode
import java.util.Locale

/** The two ends of the logo gradient. */
val BrandStart = Color(0xFF5A17FF)
val BrandEnd = Color(0xFFCF9BFF)

// Neutral zinc surfaces with the violet of the logo as the only accent; other colours come from the projects.
private val Light = lightColorScheme(
    primary = BrandStart,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE5FF),
    onPrimaryContainer = Color(0xFF1E0A52),
    secondary = Color(0xFF52525B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1F1F3),
    onSecondaryContainer = Color(0xFF27272A),
    tertiary = Color(0xFFDC2626),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEE2E2),
    onTertiaryContainer = Color(0xFF7F1D1D),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF18181B),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFF1F1F3),
    onSurfaceVariant = Color(0xFF5B5B63),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F6F7),
    surfaceContainer = Color(0xFFF1F1F3),
    surfaceContainerHigh = Color(0xFFEBEBED),
    surfaceContainerHighest = Color(0xFFE4E4E7),
    surfaceTint = Color.Transparent,
    outline = Color(0xFFA1A1AA),
    outlineVariant = Color(0xFFE4E4E7),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB794FF),
    onPrimary = Color(0xFF1E0A52),
    primaryContainer = Color(0xFF3A1E8C),
    onPrimaryContainer = Color(0xFFEDE5FF),
    secondary = Color(0xFFA1A1AA),
    onSecondary = Color(0xFF18181B),
    secondaryContainer = Color(0xFF27272A),
    onSecondaryContainer = Color(0xFFE4E4E7),
    tertiary = Color(0xFFF87171),
    onTertiary = Color(0xFF450A0A),
    tertiaryContainer = Color(0xFF5B1A1A),
    onTertiaryContainer = Color(0xFFFEE2E2),
    background = Color(0xFF0E0B16),
    onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF0E0B16),
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF27272A),
    onSurfaceVariant = Color(0xFFB4B4BC),
    surfaceContainerLowest = Color(0xFF0A0810),
    surfaceContainerLow = Color(0xFF15121E),
    surfaceContainer = Color(0xFF1B1824),
    surfaceContainerHigh = Color(0xFF221F2B),
    surfaceContainerHighest = Color(0xFF2A2733),
    surfaceTint = Color.Transparent,
    outline = Color(0xFF71717A),
    outlineVariant = Color(0xFF2E2B37),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF5B1A1A),
    onErrorContainer = Color(0xFFFEE2E2),
)

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontWeight = FontWeight.Medium),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** App colours on top of the Material scheme. */
@Immutable
data class HumbleColors(val today: Color, val success: Color, val warning: Color, val dark: Boolean)

val LocalHumbleColors = staticCompositionLocalOf { HumbleColors(BrandStart, Color(0xFF15803D), Color(0xFFB45309), false) }

@Composable
fun HumbleTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    // System bar icons follow the theme of the app, not the phone's
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    // "Today" is the primary colour; success and warning are dark enough for text on a light surface
    val extra = if (dark) HumbleColors(scheme.primary, Color(0xFF4ADE80), Color(0xFFFBBF24), true)
    else HumbleColors(scheme.primary, Color(0xFF15803D), Color(0xFFB45309), false)
    androidx.compose.runtime.CompositionLocalProvider(LocalHumbleColors provides extra) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

/** Context in the language chosen in the app; an empty language follows the phone. */
fun localizedContext(context: Context, language: String): Context {
    if (language.isBlank()) return context
    val config = Configuration(context.resources.configuration)
    config.setLocale(Locale.forLanguageTag(language))
    return context.createConfigurationContext(config)
}

/** "#RRGGBB" or "RRGGBB" of the server to a colour; null when missing or invalid. */
fun parseColor(value: String?): Color? {
    val hex = value?.trim()?.removePrefix("#") ?: return null
    if (hex.length != 6) return null
    return hex.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/** Readable text on a background of this colour. */
fun Color.contentColor(): Color = if (luminance() > 0.55f) Color(0xFF18181B) else Color.White
