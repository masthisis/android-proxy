package com.neurorift.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Proton VPN design tokens (issue #34).
//
// Numeric palette adopted from the official ProtonVPN/android-app colors.xml
// (GPL-3.0 project — we take the hex values as a visual reference, all code
// here is ours). These semantic colors are exported for screens:
//
// - [ProtonViolet]   brand accent — idle button, selection, links
// - [ConnectedGreen] tunnel-up state (theme-aware variants)
// - [AlertRed]       failure / disconnect actions (maps to colorScheme.error)
// - [DisconnectedPink] tunnel-down state accents
// - [LoadLow/Medium/High] server-load colors for the proxies screen (#12)
// ---------------------------------------------------------------------------
val ProtonViolet = Color(0xFF6D4AFF)
val ProtonVioletLight = Color(0xFF8A6EFF)
val ConnectedGreenDark = Color(0xFF27DDB1)
val ConnectedGreenLight = Color(0xFF1C9C7C)
val AlertRed = Color(0xFFDC3251)
val DisconnectedPink = Color(0xFFF08FA4)
val LoadLow = Color(0xFF1EA885)
val LoadMedium = Color(0xFFFF9900)
val LoadHigh = Color(0xFFDC3251)
val MaintenanceGray = Color(0xFFA7A4B5)

// Dark layering (Proton dark): window -> surface -> elevated card.
private val BgDark = Color(0xFF0C0C14)
private val SurfaceDark = Color(0xFF1C1B24)
private val SurfaceElevatedDark = Color(0xFF292733)

// Light layering (Proton light): white surfaces over soft lavender.
private val BgLight = Color(0xFFF4F2FB)
private val SurfaceLight = Color(0xFFFFFFFF)
private val SurfaceElevatedLight = Color(0xFFF8F6FD)

private val DarkColors = darkColorScheme(
    primary = ProtonViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3E2D86),
    onPrimaryContainer = Color(0xFFE4DDFF),
    secondary = ProtonVioletLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2A1F5C),
    onSecondaryContainer = Color(0xFFE4DDFF),
    tertiary = ConnectedGreenDark,
    onTertiary = Color(0xFF04150F),
    background = BgDark,
    onBackground = Color(0xFFEAEAF0),
    surface = SurfaceDark,
    onSurface = Color(0xFFEAEAF0),
    surfaceVariant = SurfaceElevatedDark,
    onSurfaceVariant = MaintenanceGray,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceElevatedDark,
    surfaceContainerHighest = Color(0xFF33313E),
    surfaceContainerLow = SurfaceDark,
    surfaceContainerLowest = BgDark,
    error = AlertRed,
    onError = Color.White,
    errorContainer = Color(0xFF4A1420),
    onErrorContainer = Color(0xFFFFB3BE),
    outline = Color(0xFF494D55),
    outlineVariant = Color(0xFF33313E),
)

private val LightColors = lightColorScheme(
    primary = ProtonViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DDFF),
    onPrimaryContainer = Color(0xFF1B0B5C),
    secondary = ProtonViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4DDFF),
    onSecondaryContainer = Color(0xFF1B0B5C),
    tertiary = ConnectedGreenLight,
    onTertiary = Color.White,
    background = BgLight,
    onBackground = Color(0xFF1B1626),
    surface = SurfaceLight,
    onSurface = Color(0xFF1B1626),
    surfaceVariant = SurfaceElevatedLight,
    onSurfaceVariant = Color(0xFF5D616B),
    surfaceContainer = SurfaceLight,
    surfaceContainerHigh = SurfaceElevatedLight,
    surfaceContainerHighest = Color(0xFFECEAF4),
    surfaceContainerLow = SurfaceLight,
    surfaceContainerLowest = SurfaceLight,
    error = AlertRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFD9DD),
    onErrorContainer = Color(0xFF5C0E1C),
    outline = Color(0xFFA7A4B5),
    outlineVariant = Color(0xFFE4E2EC),
)

/**
 * Proton-branded NeuroRift theme (issue #34): fixed palette in dark + light,
 * no Material You dynamic color — Proton is a branded app and the identity
 * must stay stable across devices and OS versions.
 */
@Composable
fun NeuroRiftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = NeuroRiftTypography,
        content = content,
    )
}
