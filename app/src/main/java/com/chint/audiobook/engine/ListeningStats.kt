package com.chint.audiobook.engine

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
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

    /** Converts a clamped heartbeat interval in milliseconds to whole seconds. */
    fun elapsedListeningSeconds(startedAtMs: Long, nowMs: Long): Long =
      (nowMs - startedAtMs).coerceIn(0L, MAX_LISTENING_INTERVAL_MS) / 1_000L

    /**
     * Consecutive days ENDING today (or yesterday, so today's streak is not
     * lost before the first listen of the day) with ≥ [minDaySeconds].
     */
    fun streakDays(perDaySeconds: Map<String, Long>, todayKey: String, minDaySeconds: Long = 60L): Int {
      var streak = 0
      var cursorKey = todayKey
      if (parseDayKey(cursorKey) == null) return 0
      // A streak survives "today not listened yet" — start checking yesterday
      // if today has nothing.
      if ((perDaySeconds[cursorKey] ?: 0L) < minDaySeconds) {
        cursorKey = shiftDayKey(cursorKey, -1) ?: return 0
      }
      while (true) {
        if ((perDaySeconds[cursorKey] ?: 0L) >= minDaySeconds) {
          streak++
          cursorKey = shiftDayKey(cursorKey, -1) ?: return streak
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
      if (parseDayKey(todayKey) == null) return 0L
      val keys = (0 until 7).mapNotNull { offset -> shiftDayKey(todayKey, -offset) }
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

    private const val MAX_LISTENING_INTERVAL_MS = 30_000L

    private fun shiftDayKey(dayKey: String, days: Int): String? {
      val date = parseDayKey(dayKey)?.let(::Date) ?: return null
      val timeZone = TimeZone.getDefault()
      val calendar = Calendar.getInstance(timeZone, Locale.US).apply {
        timeInMillis = date.time
        add(Calendar.DAY_OF_YEAR, days)
      }
      val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        this.timeZone = timeZone
      }
      return formatter.format(calendar.time)
    }
  }
}
