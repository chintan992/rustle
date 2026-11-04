package com.chint.audiobook.engine

/**
 * Cache/prep round (B3): pure decision for whether background chapter
 * preparation (download-ahead synthesis) may run right now. Pure so the full
 * condition matrix is unit-testable; the Android probes live in
 * [DeviceConditions].
 *
 * batterySaver (a legacy setting that previously had no consumer) freezes
 * preparation entirely; a 0-chapter setting disables the feature.
 */
object PreloadPolicy {

  fun shouldPreload(
    prepareAheadChapters: Int,
    wifiOnly: Boolean,
    onlyWhileCharging: Boolean,
    batterySaver: Boolean,
    isCharging: Boolean,
    isMetered: Boolean
  ): Boolean =
    prepareAheadChapters > 0 &&
      !batterySaver &&
      (!onlyWhileCharging || isCharging) &&
      (!wifiOnly || !isMetered)
}

/**
 * Cache/prep round (B3): the two cheap Android probes the policy needs —
 * charging state from the sticky battery broadcast, metered state from
 * ConnectivityManager. No new permissions (ACCESS_NETWORK_STATE is already in
 * the manifest; the battery sticky intent needs none).
 */
object DeviceConditions {

  fun isCharging(context: android.content.Context): Boolean {
    val status = context.registerReceiver(
      null,
      android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
    )?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
    return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
      status == android.os.BatteryManager.BATTERY_STATUS_FULL
  }

  /** Unknown connectivity reads as metered — the conservative default. */
  fun isMetered(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
      as? android.net.ConnectivityManager ?: return true
    return cm.isActiveNetworkMetered
  }
}
