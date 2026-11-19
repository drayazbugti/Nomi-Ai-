package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val NoomiDarkColorScheme = darkColorScheme(
    primary = PureWhite,
    onPrimary = ObsidianBackground,
    primaryContainer = ElevatedPillDark,
    onPrimaryContainer = PureWhite,
    secondary = SoftSilver,
    onSecondary = ObsidianBackground,
    secondaryContainer = CharcoalSurface,
    onSecondaryContainer = PureWhite,
    tertiary = StudioEmeraldSuccess,
    onTertiary = ObsidianBackground,
    background = ObsidianBackground,
    onBackground = PureWhite,
    surface = CharcoalSurface,
    onSurface = PureWhite,
    surfaceVariant = GraphiteSheet,
    onSurfaceVariant = SoftSilver,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = CharcoalSurface,
    surfaceContainer = GraphiteSheet,
    surfaceContainerHigh = ElevatedPillDark,
    outline = HairlineBorderDark,
    outlineVariant = HairlineBorderDark,
    error = StudioCrimsonError,
    onError = ObsidianBackground
)

private val NoomiLightColorScheme = lightColorScheme(
    primary = InkBlack,
    onPrimary = PureCardLight,
    primaryContainer = SoftStoneLight,
    onPrimaryContainer = InkBlack,
    secondary = SlateSecondary,
    onSecondary = PureCardLight,
    secondaryContainer = PureCardLight,
    onSecondaryContainer = InkBlack,
    tertiary = StudioEmeraldSuccess,
    onTertiary = InkBlack,
    background = StudioLightBackground,
    onBackground = InkBlack,
    surface = PureCardLight,
    onSurface = InkBlack,
    surfaceVariant = SoftStoneLight,
    onSurfaceVariant = SlateSecondary,
    surfaceContainerLowest = PureCardLight,
    surfaceContainerLow = StudioLightBackground,
    surfaceContainer = PureCardLight,
    surfaceContainerHigh = SoftStoneLight,
    outline = HairlineBorderLight,
    outlineVariant = HairlineBorderLight,
    error = StudioCrimsonErrorLight,
    onError = PureCardLight
)

val NoomiShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun NoomiTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) NoomiDarkColorScheme else NoomiLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NoomiTypography,
        shapes = NoomiShapes,
        content = content
    )
}
