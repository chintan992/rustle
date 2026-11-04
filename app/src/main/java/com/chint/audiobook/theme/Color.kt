package com.chint.audiobook.theme

import androidx.compose.ui.graphics.Color

// Warm amber / paper brand palette. Built from an amber seed following the
// Material 3 tonal-role model: dark themes take the LIGHT tones for accent
// roles (primary = tone ~80) so FABs, switches and active states pop against
// the warm near-black surface; light themes take the deep tones (~40).
//
// Named by tone so light/dark usage stays obvious: Amber80 = light tone for
// dark theme, Amber40 = deep tone for light theme, etc.

// Amber (primary)
val Amber95 = Color(0xFFFFDFBE)
val Amber90 = Color(0xFFFFDDB0)
val Amber80 = Color(0xFFFFB95C)
val Amber40 = Color(0xFF8B5300)
val Amber35 = Color(0xFF684400)
val Amber30 = Color(0xFF533500)
val Amber10 = Color(0xFF2C1700)

// Sand (secondary)
val Sand90 = Color(0xFFFCDFB9)
val Sand80 = Color(0xFFE2C299)
val Sand70 = Color(0xFFC8AB88)
val Sand40 = Color(0xFF745B39)
val Sand30 = Color(0xFF58442A)
val Sand10 = Color(0xFF2C1700)

// Terracotta (tertiary — the contrast accent, e.g. karaoke highlight)
val Terracotta80 = Color(0xFFFFB5A1)
val Terracotta40 = Color(0xFF984227)
val Terracotta35 = Color(0xFF7C2F1E)
val Terracotta90 = Color(0xFFFFDAD2)
val Terracotta30 = Color(0xFF5F1504)

// Error (M3 baseline)
val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
val ErrorLight = Color(0xFFB3261E)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFF9DEDC)
val OnErrorContainerLight = Color(0xFF410E0B)

// Warm near-black neutrals (dark theme surfaces)
val Ink90 = Color(0xFFEAE1D5)   // on surface
val Ink80 = Color(0xFFD1C5B4)   // on surface variant
val Ink50 = Color(0xFF9C8F7F)   // outline
val Ink35 = Color(0xFF51473C)   // outline variant / surfaceVariant
val Ink25 = Color(0xFF39332B)   // surface container highest
val Ink20 = Color(0xFF2E2821)   // surface container high
val Ink17 = Color(0xFF241E17)   // surface container
val Ink14 = Color(0xFF1E1913)   // surface container low
val Ink08 = Color(0xFF17130E)   // background / surface
val Ink04 = Color(0xFF110D08)   // surface container lowest

// Paper neutrals (light theme surfaces)
val Paper99 = Color(0xFFFDF9F3)  // background / surface
val Paper97 = Color(0xFFF7F1E8)  // surface container low
val Paper95 = Color(0xFFF1EAE0)  // surface container
val Paper93 = Color(0xFFEBE4D9)  // surface container high
val Paper91 = Color(0xFFE5DED3)  // surface container highest
val Paper60 = Color(0xFF817567)  // outline
val Paper60Dim = Color(0xFFD3C5B5)  // outline variant
val Paper40 = Color(0xFF4F4539)  // on surface variant
val Paper20 = Color(0xFF201B14)  // on surface
