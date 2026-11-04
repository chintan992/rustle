package com.chint.audiobook.playback

/**
 * Q6: minimal shake detection from raw accelerometer samples. Pure and
 * testable: feed each sensor event's acceleration vector; a shake is a burst
 * of total-magnitude excursions above [threshold] within the [windowMs] —
 * requiring [requiredExcursions] of them filters single jolts (car door,
 * table bump) from an actual shake.
 */
class ShakeDetector(
  private val threshold: Float = SHAKE_THRESHOLD,
  private val windowMs: Long = SHAKE_WINDOW_MS,
  private val requiredExcursions: Int = SHAKE_REQUIRED_EXCURSIONS,
  private val cooldownMs: Long = SHAKE_COOLDOWN_MS
) {

  private var windowStartMs = 0L
  private var excursions = 0

  /** Starts at -[cooldownMs] so the FIRST fire is never cooldown-suppressed. */
  private var lastFireMs = -cooldownMs

  /**
   * @param eventMs wall-clock ms of the sample
   * @param x/y/z acceleration including gravity, in m/s²
   * @return true exactly once per detected shake
   */
  fun onSample(eventMs: Long, x: Float, y: Float, z: Float): Boolean {
    val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
    val over = magnitude > threshold

    if (eventMs - windowStartMs > windowMs) {
      windowStartMs = eventMs
      excursions = 0
    }
    if (over) excursions++

    if (excursions >= requiredExcursions) {
      excursions = 0
      if (eventMs - lastFireMs >= cooldownMs) {
        lastFireMs = eventMs
        return true
      }
    }
    return false
  }

  companion object {
    /** Earth gravity is ~9.8 — a shake doubles or triples it briefly. */
    const val SHAKE_THRESHOLD = 15.0f
    const val SHAKE_WINDOW_MS = 600L
    const val SHAKE_REQUIRED_EXCURSIONS = 3
    const val SHAKE_COOLDOWN_MS = 1_500L
  }
}
