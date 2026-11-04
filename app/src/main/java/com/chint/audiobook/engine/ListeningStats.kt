package com.chint.audiobook.engine

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * WS2: forward-looking listening-time accounting. A small JSON sidecar
 * (filesDir/stats/listening.json) fed by the playback heartbeat — no invented
 * history: the totals start when this build first runs. Per-day buckets drive
 * the streak/week rollups; per-book buckets drive the manage-sheet line.
 * (Storage-round verdict: JSON sidecar, not SQLite — single-object
 * read-modify-write, no relational queries.)
 */
@Serializable
data class ListeningStatsData(
  val perDaySeconds: Map<String, Long> = emptyMap(),
  val perBookSeconds: Map<String, Long> = emptyMap()
)

class ListeningStatsRepository(private val statsDir: File) {

  private val json = Json { ignoreUnknownKeys = true }
  private val file get() = File(statsDir, "listening.json")

  fun load(): ListeningStatsData =
    runCatching {
      if (!file.exists()) return ListeningStatsData()
      json.decodeFromString<ListeningStatsData>(file.readText())
    }.getOrDefault(ListeningStatsData())

  /** Adds [seconds] of listening for [bookSlug]; corrupt/missing reads start fresh. */
  fun addListening(bookSlug: String, seconds: Long, nowMs: Long = System.currentTimeMillis()) {
    if (seconds <= 0L || bookSlug.isBlank()) return
    val data = load()
    val day = dayKeyOf(nowMs)
    val updated = data.copy(
      perDaySeconds = data.perDaySeconds + (day to (data.perDaySeconds[day] ?: 0L) + seconds),
      perBookSeconds = data.perBookSeconds + (bookSlug to (data.perBookSeconds[bookSlug] ?: 0L) + seconds)
    )
    runCatching {
      statsDir.mkdirs()
      val tmp = File(statsDir, "listening.json.tmp")
      tmp.writeText(json.encodeToString(updated))
      if (file.exists()) file.delete()
      tmp.renameTo(file)
    }
  }

  fun totalForBook(bookSlug: String): Long = load().perBookSeconds[bookSlug] ?: 0L

  companion object {
    /** Local-date key "yyyy-MM-dd" — bucket boundaries follow the listener's clock. */
    fun dayKeyOf(nowMs: Long): String {
      val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
      fmt.timeZone = TimeZone.getDefault()
      return fmt.format(Date(nowMs))
    }

    fun parseDayKey(key: String): Long? = runCatching {
      val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
      fmt.timeZone = TimeZone.getDefault()
      fmt.parse(key)?.time
    }.getOrNull()

    /**
     * Consecutive days ENDING today (or yesterday, so today's streak is not
     * lost before the first listen of the day) with ≥ [minDaySeconds].
     */
    fun streakDays(perDaySeconds: Map<String, Long>, todayKey: String, minDaySeconds: Long = 60L): Int {
      var streak = 0
      var cursor = parseDayKey(todayKey) ?: return 0
      // A streak survives "today not listened yet" — start checking yesterday
      // if today has nothing.
      if ((perDaySeconds[todayKey] ?: 0L) < minDaySeconds) cursor -= DAY_MS
      while (true) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        val key = fmt.format(Date(cursor))
        if ((perDaySeconds[key] ?: 0L) >= minDaySeconds) {
          streak++
          cursor -= DAY_MS
        } else {
          return streak
        }
      }
    }

    /**
     * Q5: total seconds across calendar year [yearKey] ("2026") — the yearly
     * reading-goal progress line. Bucket keys outside the year are ignored.
     */
    fun yearSeconds(perDaySeconds: Map<String, Long>, yearKey: String): Long =
      perDaySeconds
        .filterKeys { it.startsWith("$yearKey-") }
        .values
        .sum()

    /** Total seconds across the 7-day window ending [todayKey] (inclusive). */
    fun weekSeconds(perDaySeconds: Map<String, Long>, todayKey: String): Long {
      val today = parseDayKey(todayKey) ?: return 0L
      val keys = (0 until 7).map { offset ->
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        fmt.format(Date(today - offset * DAY_MS))
      }
      return keys.sumOf { perDaySeconds[it] ?: 0L }
    }

    /** "3h 42m" / "12h" / "5m" — compact human duration. */
    fun formatDuration(seconds: Long): String {
      val totalMinutes = seconds / 60
      val hours = totalMinutes / 60
      val minutes = totalMinutes % 60
      return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
      }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
  }
}
