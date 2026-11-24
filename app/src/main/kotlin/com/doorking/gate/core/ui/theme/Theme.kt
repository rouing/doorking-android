package com.doorking.gate.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Immutable
data class DoorkingSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val LightSemanticColors = DoorkingSemanticColors(
    success = SuccessLight,
    onSuccess = OnSuccessLight,
    successContainer = SuccessContainerLight,
    onSuccessContainer = OnSuccessContainerLight,
    warning = WarningLight,
    onWarning = OnWarningLight,
    warningContainer = WarningContainerLight,
    onWarningContainer = OnWarningContainerLight,
)

private val DarkSemanticColors = DoorkingSemanticColors(
    success = SuccessDark,
    onSuccess = OnSuccessDark,
    successContainer = SuccessContainerDark,
    onSuccessContainer = OnSuccessContainerDark,
    warning = WarningDark,
    onWarning = OnWarningDark,
    warningContainer = WarningContainerDark,
    onWarningContainer = OnWarningContainerDark,
)

val LocalDoorkingColors = staticCompositionLocalOf { LightSemanticColors }

// Full cool-neutral Material 3 fallback schemes (eliminates default M3 lavender tint when dynamicColor is off).
private val BrandLightScheme = lightColorScheme(
    primary = BrandBlueLight,
    onPrimary = OnBrandBlueLight,
    primaryContainer = BrandBlueContainerLight,
    onPrimaryContainer = OnBrandBlueContainerLight,
    secondary = BrandSecondaryLight,
    onSecondary = OnBrandSecondaryLight,
    secondaryContainer = BrandSecondaryContainerLight,
    onSecondaryContainer = OnBrandSecondaryContainerLight,
    tertiary = BrandTertiaryLight,
    onTertiary = OnBrandTertiaryLight,
    tertiaryContainer = BrandTertiaryContainerLight,
    onTertiaryContainer = OnBrandTertiaryContainerLight,
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF181C20),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF181C20),
    surfaceVariant = Color(0xFFDEE3EB),
    onSurfaceVariant = Color(0xFF42474E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F9),
    surfaceContainer = Color(0xFFEBEEF3),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE0E2E8),
    outline = Color(0xFF72777F),
    outlineVariant = Color(0xFFC2C7CF),
)

private val BrandDarkScheme = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = OnBrandBlueDark,
    primaryContainer = BrandBlueContainerDark,
    onPrimaryContainer = OnBrandBlueContainerDark,
    secondary = BrandSecondaryDark,
    onSecondary = OnBrandSecondaryDark,
    secondaryContainer = BrandSecondaryContainerDark,
    onSecondaryContainer = OnBrandSecondaryContainerDark,
    tertiary = BrandTertiaryDark,
    onTertiary = OnBrandTertiaryDark,
    tertiaryContainer = BrandTertiaryContainerDark,
    onTertiaryContainer = OnBrandTertiaryContainerDark,
    background = Color(0xFF101418),
    onBackground = Color(0xFFE0E2E8),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE0E2E8),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC2C7CF),
    surfaceContainerLowest = Color(0xFF0B0E12),
    surfaceContainerLow = Color(0xFF181C20),
    surfaceContainer = Color(0xFF1C2024),
    surfaceContainerHigh = Color(0xFF262A2F),
    surfaceContainerHighest = Color(0xFF31353A),
    outline = Color(0xFF8C9199),
    outlineVariant = Color(0xFF42474E),
)

// Exact M3 shape scale (4 / 8 / 12 / 16 / 28 dp).
val DoorkingShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun DoorkingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> BrandDarkScheme
        else -> BrandLightScheme
    }
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalDoorkingColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DoorkingTypography,
            shapes = DoorkingShapes,
            content = content,
        )
    }
}
