package com.chint.audiobook.theme

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

internal object AppMotion {
  fun <T> enterSpec() = tween<T>(durationMillis = 220, easing = FastOutSlowInEasing)

  fun <T> exitSpec() = tween<T>(durationMillis = 180, easing = FastOutLinearInEasing)

  fun <T> navigationSpec() = tween<T>(durationMillis = 300, easing = FastOutSlowInEasing)
}
