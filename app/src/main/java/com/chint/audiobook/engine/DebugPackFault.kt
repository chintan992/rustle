package com.chint.audiobook.engine

import java.io.File
import java.io.RandomAccessFile

/**
 * Round 10 (Task 3): debug-only pack-fault injection building blocks.
 *
 * Pure, JVM-testable primitives used by [ModelManager]'s debug hooks to prove
 * the A8 checksum-recovery paths on a real device:
 *  - [DebugPackHooks] — the arm/consume state machine: shots are armed only via
 *    ModelManager's debug API (guarded by BuildConfig.DEBUG) and each is
 *    consumed exactly once by the download flow.
 *  - [DebugPackCorruptor] — flips EXACTLY one byte of an existing non-empty
 *    file. The flip is an xor of the byte's LSB, so re-flipping the same
 *    offset (with the same fractional resolution) restores the byte.
 *
 * In release builds nothing arms the hooks and the flow's consume sites find
 * no shots — the code is inert by construction.
 */
object DebugPackCorruptor {

  /**
   * Flip exactly one byte of [file]: the LSB of the byte at [fraction] of the
   * usable length. The offset is derived deterministically so logs name a
   * reproducible position for a given file size. Returns the offset that was
   * flipped, or null when there was nothing to corrupt (missing or empty file).
   */
  fun flipOneByte(file: File, fraction: Double = 0.5): Long? {
    if (!file.exists() || file.length() == 0L) return null
    val offset = (fraction.coerceIn(0.0, 1.0) * (file.length() - 1)).toLong()
    RandomAccessFile(file, "rw").use { raf ->
      raf.seek(offset)
      val original = raf.read()
      raf.seek(offset)
      raf.write(original xor 0x01)
    }
    return offset
  }

  /** The per-byte fault: xor with 0x01 always differs from the original. */
  const val FLIP_MASK: Int = 0x01
}

/**
 * Arm/disarm/consume state for the debug fault hooks. No Android types — the
 * same class is exercised by the JVM suite.
 */
class DebugPackHooks {

  /** Name of the file to corrupt in the FINAL model dir, consumed at flow entry. */
  private var finalDirShot: String? = null

  /** Name of the file to corrupt in STAGING before each staging sha256 pass. */
  private var stagedShot: String? = null

  /** Remaining staged-verify shots (journey B arms two: first pass + recheck). */
  private var stagedShotsLeft: Int = 0

  /** True while any shot is armed (used to disarm leftovers at flow end). */
  fun isArmed(): Boolean = finalDirShot != null || stagedShotsLeft > 0

  /** Arm one corruption of a final-dir pack file at the next download entry. */
  fun armFinalDirCorruption(fileName: String) {
    finalDirShot = fileName
  }

  /** Arm [times] corruptions injected right before each staging verify pass. */
  fun armStagedVerifyCorruption(fileName: String, times: Int) {
    stagedShot = fileName
    stagedShotsLeft = times.coerceIn(0, 8)
  }

  /** Drop every armed shot without consuming (called at download-flow end). */
  fun disarm() {
    finalDirShot = null
    stagedShot = null
    stagedShotsLeft = 0
  }

  /** Consume the final-dir shot (null when not armed / already consumed). */
  fun consumeFinalDirShot(): String? = finalDirShot.also { finalDirShot = null }

  /** Consume one staged-verify shot (null when none remain). */
  fun consumeStagedVerifyShot(): String? {
    if (stagedShotsLeft <= 0) return null
    stagedShotsLeft--
    return stagedShot
  }
}
