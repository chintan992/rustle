package com.chint.audiobook.engine

import com.chint.audiobook.data.model.LedgerEvent
import kotlin.math.ceil

/**
 * Round 9 (Task 4): auditable aggregation of per-chunk RTF values taken from the
 * per-book ledgers' `render_done` events (the service logs one event per
 * synthesized real chunk with its wall-vs-audio RTF). Pure math so it is unit
 * tested on the JVM; the Model screen prints the result so the ≥200-chunk bar
 * of APP_PLAN §10 is verifiable on-device.
 */
data class RtfStats(
  val count: Int,
  val mean: Double,
  val p50: Double,
  val p95: Double,
  /** All RTF values sorted ascending (audit trail). */
  val sorted: List<Double>
) {
  companion object {
    /** Coverage: what happened across [booksDirs]' ledgers might be empty. */
    val EMPTY = RtfStats(count = 0, mean = 0.0, p50 = 0.0, p95 = 0.0, sorted = emptyList())

    /** Aggregation over raw RTF values. Invalid values (<= 0 or non-finite) are dropped honestly. */
    fun from(rtfValues: List<Double>): RtfStats {
      val valid = rtfValues.filter { it.isFinite() && it > 0.0 }.sorted()
      if (valid.isEmpty()) return EMPTY
      return RtfStats(
        count = valid.size,
        mean = valid.average(),
        p50 = percentile(valid, 0.50),
        p95 = percentile(valid, 0.95),
        sorted = valid.toList()
      )
    }

    /** Aggregation over ledger events: exactly the `render_done` events that carry an RTF. */
    fun fromEvents(events: List<LedgerEvent>): RtfStats =
      from(events.filter { it.event == "render_done" && it.rtf != null }.mapNotNull { it.rtf })

    /**
     * Nearest-rank percentile: ceil(q * n) ranked element (n >= 1 guaranteed by [from]).
     * p95 of 200 chunks = the 190th worst-inclusive value; not interpolated.
     */
    fun percentile(sortedAscending: List<Double>, fraction: Double): Double {
      if (sortedAscending.isEmpty()) return 0.0
      val rank = ceil(fraction * sortedAscending.size).toInt().coerceIn(1, sortedAscending.size)
      return sortedAscending[rank - 1]
    }
  }
}
