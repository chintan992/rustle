package com.chint.audiobook.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
  primary = Amber80,
  onPrimary = Amber10,
  primaryContainer = Amber35,
  onPrimaryContainer = Amber90,
  inversePrimary = Amber40,
  secondary = Sand80,
  onSecondary = Sand10,
  secondaryContainer = Sand30,
  onSecondaryContainer = Sand90,
  tertiary = Terracotta80,
  onTertiary = Terracotta30,
  tertiaryContainer = Terracotta35,
  onTertiaryContainer = Terracotta90,
  error = ErrorDark,
  onError = OnErrorDark,
  errorContainer = ErrorContainerDark,
  onErrorContainer = OnErrorContainerDark,
  background = Ink08,
  onBackground = Ink90,
  surface = Ink08,
  onSurface = Ink90,
  surfaceVariant = Ink35,
  onSurfaceVariant = Ink80,
  surfaceContainerLowest = Ink04,
  surfaceContainerLow = Ink14,
  surfaceContainer = Ink17,
  surfaceContainerHigh = Ink20,
  surfaceContainerHighest = Ink25,
  surfaceDim = Ink08,
  surfaceBright = Ink25,
  outline = Ink50,
  outlineVariant = Ink35,
  inverseSurface = Ink90,
  inverseOnSurface = Ink17,
  scrim = Color(0xFF000000),
)

private val LightColorScheme = lightColorScheme(
  primary = Amber40,
  onPrimary = Color(0xFFFFFFFF),
  primaryContainer = Amber90,
  onPrimaryContainer = Amber10,
  inversePrimary = Amber80,
  secondary = Sand40,
  onSecondary = Color(0xFFFFFFFF),
  secondaryContainer = Sand90,
  onSecondaryContainer = Sand10,
  tertiary = Terracotta40,
  onTertiary = Color(0xFFFFFFFF),
  tertiaryContainer = Terracotta90,
  onTertiaryContainer = Terracotta30,
  error = ErrorLight,
  onError = OnErrorLight,
  errorContainer = ErrorContainerLight,
  onErrorContainer = OnErrorContainerLight,
  background = Paper99,
  onBackground = Paper20,
  surface = Paper99,
  onSurface = Paper20,
  surfaceVariant = Paper60Dim,
  onSurfaceVariant = Paper40,
  surfaceContainerLowest = Color(0xFFFFFFFF),
  surfaceContainerLow = Paper97,
  surfaceContainer = Paper95,
  surfaceContainerHigh = Paper93,
  surfaceContainerHighest = Paper91,
  surfaceDim = Paper91,
  surfaceBright = Paper99,
  outline = Paper60,
  outlineVariant = Paper60Dim,
  inverseSurface = Paper20,
  inverseOnSurface = Paper99,
  scrim = Color(0xFF000000),
)

// Shape scheme: consistently rounded containers. Cards/book rows read at 16dp,
// sheets and large containers get the 28dp extraLarge radius via defaults.
private val AppShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(12.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(20.dp),
  extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AudiobookTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(colorScheme = colorScheme, shapes = AppShapes, typography = Typography, content = content)
}
