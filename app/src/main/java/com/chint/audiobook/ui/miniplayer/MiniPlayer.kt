package com.chint.audiobook.ui.miniplayer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chint.audiobook.data.model.Book
import com.chint.audiobook.theme.AppMotion
import com.chint.audiobook.theme.AppIconsPause

/**
 * ElevenReader plan (A1 / APP_PLAN §S1 "chrome"): the persistent-listening bar.
 * Docks at the bottom edge of every non-Reader destination while audio plays:
 * amber cover orb, book title + chapter, play/pause. Tapping anywhere but the
 * pause button returns to the Reader at the live current chapter.
 *
 * Hidden while paused and inside the Reader — navigation owns the visibility
 * rule (spec: "whenever audio is playing and the user is away from the Reader").
 */
@Composable
fun MiniPlayer(
  book: Book?,
  currentChapterIndex: Int,
  isPlaying: Boolean,
  visible: Boolean,
  // UI Round 4 (R11): chapter position for the card-bottom progress line.
  chapterPositionMs: Long = 0L,
  chapterDurationMs: Long = 0L,
  onPlayPauseClick: () -> Unit,
  onOpenReader: () -> Unit,
  modifier: Modifier = Modifier
) {
  AnimatedVisibility(
    visible = visible && book != null,
    enter = fadeIn(animationSpec = AppMotion.enterSpec()) +
      slideInVertically(animationSpec = AppMotion.enterSpec()) { it / 5 },
    exit = fadeOut(animationSpec = AppMotion.exitSpec()) +
      slideOutVertically(animationSpec = AppMotion.exitSpec()) { it / 5 },
    modifier = modifier
  ) {
    Card(
      shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
      ),
      // UI Round 4 (R1): sit above the system nav bar instead of the gesture strip.
      modifier = Modifier
        .navigationBarsPadding()
        .fillMaxWidth()
        .clickable(role = Role.Button) { onOpenReader() }
        .semantics { contentDescription = "Return to the reader" }
    ) {
      Column {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(12.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Cover orb: the amber circle stands in for the book cover.
        Box(
          modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary
          )
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = book?.title ?: "",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            text = "Chapter $currentChapterIndex — listening",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
          )
        }

        IconButton(
          onClick = onPlayPauseClick,
          modifier = Modifier.semantics {
            contentDescription = if (isPlaying) "Pause playback" else "Resume playback"
          }
        ) {
          Icon(
            imageVector = if (isPlaying) AppIconsPause else Icons.Default.PlayArrow,
            contentDescription = null
          )
        }
      }

      // UI Round 4 (R11): 2dp chapter-progress line on the card's bottom edge —
      // hidden while the render frontier hasn't measured a duration yet.
      key(book?.slug, currentChapterIndex) {
        val targetProgress = if (chapterDurationMs > 0L) {
          (chapterPositionMs.toFloat() / chapterDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
          0f
        }
        val animatedProgress by animateFloatAsState(
          targetValue = targetProgress,
          animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
          label = "miniPlayerProgress"
        )
        if (chapterDurationMs > 0L) {
          LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
          )
        }
      }
      }
    }
  }
}
