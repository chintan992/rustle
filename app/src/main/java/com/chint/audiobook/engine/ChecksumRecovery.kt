package com.chint.audiobook.engine

/**
 * Round 10 (Task 3): pure decision logic for the A8 pack-checksum recovery
 * flow, extracted from [ModelManager.runDownloadFlow] so the JVM suite pins the
 * exact branches. The flow maps each verdict to the SAME progress / log /
 * exception text the pre-extraction code emitted — the throw-site strings stay
 * byte-identical ("Download incomplete: still missing after all files
 * fetched: ...", "Pack verification failed after download+retry: ...").
 */
object ChecksumRecovery {

  /** What to do after the first sha256 pass over the files this run staged. */
  sealed interface StagedVerdict {
    /** Nothing missing, nothing mismatched: move the staged files into the final dir. */
    data object Clean : StagedVerdict

    /**
     * Some staged-pack files are still absent/empty after every fetch attempt
     * (transport or server-side failure): hard fail, no refetch retry.
     */
    data class Incomplete(val missing: List<String>) : StagedVerdict

    /**
     * Some staged files mismatched their sha256: delete them, refetch once
     * (same per-file retrying download as the first pass), then re-verify.
     */
    data class Refetch(val mismatched: List<String>) : StagedVerdict
  }

  /** Verdict after the sha256 re-check that follows a refetch. */
  sealed interface RecoveryVerdict {
    /** The refetch repaired every mismatched file: safe to move + clean staging. */
    data class Recovered(val recovered: List<String>) : RecoveryVerdict

    /**
     * The single refetch retry was exhausted — the freshly downloaded bytes
     * still mismatch (persisting bad mirror content, on-path corruption, or
     * deliberate tampering): fail so the user gets the exact offending files.
     */
    data class Exhausted(
      val recheckMissing: List<String>,
      val recheckMismatch: List<String>
    ) : RecoveryVerdict
  }

  /**
   * Missing dominates: an incomplete staged pack can never be repaired by a
   * checksum-motivated refetch of present-but-wrong files, so the flow fails
   * fast — exactly as the pre-extraction code ordered its branches.
   */
  fun decideStaged(missing: List<String>, mismatch: List<String>): StagedVerdict = when {
    missing.isNotEmpty() -> StagedVerdict.Incomplete(missing)
    mismatch.isNotEmpty() -> StagedVerdict.Refetch(mismatch)
    else -> StagedVerdict.Clean
  }

  /**
   * Pass-through only when the re-verify is spotless on BOTH dimensions; any
   * residue — including files that blinked clean then went missing — is an
   * exhausted retry, and the offending lists are surfaced verbatim.
   */
  fun decideAfterRefetch(
    recheckMissing: List<String>,
    recheckMismatch: List<String>,
    refetched: List<String>
  ): RecoveryVerdict =
    if (recheckMissing.isEmpty() && recheckMismatch.isEmpty()) {
      RecoveryVerdict.Recovered(refetched)
    } else {
      RecoveryVerdict.Exhausted(recheckMissing, recheckMismatch)
    }
}
