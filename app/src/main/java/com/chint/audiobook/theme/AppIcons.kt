package com.chint.audiobook.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// The core material-icons set has no pause glyph and we deliberately avoid the
// (huge) icons-extended dependency — so "pause" is defined locally, matching
// the Material Symbols pause path (Apache-2.0 icon set). Compose's Icon()
// tints the drawn path with the ambient icon color, so the fill sentinel
// color below is irrelevant.
val AppIconsPause: ImageVector =
  ImageVector.Builder(
    name = "AppIcons.Pause",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
  ).apply {
    path(
      fill = SolidColor(Color(0xFF000000)),
      stroke = null,
      strokeLineWidth = 1f,
      strokeLineCap = StrokeCap.Butt,
      strokeLineJoin = StrokeJoin.Bevel,
      pathBuilder = {
        // M6 19h4V5H6v14z
        moveTo(6f, 19f)
        horizontalLineTo(10f)
        verticalLineTo(5f)
        horizontalLineTo(6f)
        verticalLineToRelative(14f)
        close()
        // m8 -14 v14 h4 V5 h-4 z
        moveToRelative(8f, -14f)
        verticalLineToRelative(14f)
        horizontalLineToRelative(4f)
        verticalLineTo(5f)
        horizontalLineToRelative(-4f)
        close()
      }
    )
  }.build()

// ElevenReader-plan transport icons (reader transport + bookmarks + sleep).
// Stroked and filled sub-paths tint wholesale via Icon(), same as pause.

/** Bookmark ribbon (add / open the bookmarks sheet). */
val AppBookmark: ImageVector =
  ImageVector.Builder(
    name = "AppIcons.Bookmark",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
  ).apply {
    path(
      fill = SolidColor(Color(0xFF000000)),
      stroke = null,
      strokeLineWidth = 1f,
      strokeLineCap = StrokeCap.Butt,
      strokeLineJoin = StrokeJoin.Bevel,
      pathBuilder = {
        // M6 4h12v17l-6-3.8L6 21z
        moveTo(6f, 4f)
        horizontalLineToRelative(12f)
        verticalLineToRelative(17f)
        lineToRelative(-6f, -3.8f)
        lineToRelative(-6f, 3.8f)
        close()
      }
    )
  }.build()

/**
 * Circular replay arrow, gap on the upper-left, arrowhead pointing
 * counterclockwise (skip-minus). The forward twin is the same vector rotated
 * 90° at the call site — Icon() renders the whole path; rotation is a Modifier,
 * not new path data.
 */
val AppReplayArrow: ImageVector =
  ImageVector.Builder(
    name = "AppIcons.ReplayArrow",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
  ).apply {
    // ~325° ring arc: current point (9.26, 4.48) → (5.45, 7.41). The arcTo call
    // carries only the END point; the start is the current point.
    path(
      fill = null,
      stroke = SolidColor(Color(0xFF000000)),
      strokeLineWidth = 2f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
      pathBuilder = {
        moveTo(9.26f, 4.48f)
        arcTo(8f, 8f, 0f, true, true, 5.45f, 7.41f)
      },
    )
    // Arrowhead triangle across the ring gap, tip pushed outward.
    path(
      fill = SolidColor(Color(0xFF000000)),
      stroke = null,
      strokeLineWidth = 1f,
      strokeLineCap = StrokeCap.Butt,
      strokeLineJoin = StrokeJoin.Bevel,
      pathBuilder = {
        moveTo(9.26f, 4.48f)
        lineTo(4.18f, 1.82f)
        lineTo(5.45f, 7.41f)
        close()
      },
    )
  }.build()

/** Schedule clock (sleep timer button). */
val AppClock: ImageVector =
  ImageVector.Builder(
    name = "AppIcons.Clock",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
  ).apply {
    // Full ring: two half-circle strokes.
    path(
      fill = null,
      stroke = SolidColor(Color(0xFF000000)),
      strokeLineWidth = 2f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
      pathBuilder = {
        moveTo(20f, 12f)
        arcTo(8f, 8f, 0f, false, true, 4f, 12f)
        arcTo(8f, 8f, 0f, false, true, 20f, 12f)
      },
    )
    // L-shaped hands: up from (12, 7) to (12, 12), right to (16, 13).
    path(
      fill = SolidColor(Color(0xFF000000)),
      stroke = null,
      strokeLineWidth = 1f,
      strokeLineCap = StrokeCap.Butt,
      strokeLineJoin = StrokeJoin.Bevel,
      pathBuilder = {
        moveTo(11.1f, 6.8f)
        horizontalLineTo(12.9f)
        verticalLineTo(12.4f)
        horizontalLineTo(16.9f)
        verticalLineTo(14.2f)
        horizontalLineTo(11.1f)
        close()
      },
    )
  }.build()

/**
 * Media round (A4): slim chapter-boundary chevrons for the chapter transport
 * row (‹ Chapter 42 of 3220 ›). Stroked single-path glyphs, same family as
 * AppClock/AppReplayArrow — visually distinct from the ±10 s replay arrows so
 * "skip chapter" cannot be confused with "skip 10 seconds".
 */
val AppChevronLeft: ImageVector =
  ImageVector.Builder(
    name = "AppIcons.ChevronLeft",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
  ).apply {
    path(
      fill = null,
      stroke = SolidColor(Color(0xFF000000)),
      strokeLineWidth = 2.5f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
      pathBuilder = {
        moveTo(14.5f, 5f)
        lineTo(7.5f, 12f)
        lineTo(14.5f, 19f)
      },
    )
  }.build()

/** Media round (A4): mirror of [AppChevronLeft]. */
val AppChevronRight: ImageVector =
  ImageVector.Builder(
    name = "AppIcons.ChevronRight",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
  ).apply {
    path(
      fill = null,
      stroke = SolidColor(Color(0xFF000000)),
      strokeLineWidth = 2.5f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
      pathBuilder = {
        moveTo(9.5f, 5f)
        lineTo(16.5f, 12f)
        lineTo(9.5f, 19f)
      },
    )
  }.build()

/**
 * UI Round 4 (R9): equalizer sliders — the TTS engine/model-manager glyph.
 * Replaces the material wrench (Icons.Default.Build) which reads as "repair",
 * not "speech engine". Matches the stroked language of AppClock/AppReplayArrow.
 */
val AppEngine: ImageVector =
  ImageVector.Builder(
    name = "AppIcons.Engine",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
  ).apply {
    // Three vertical rails.
    path(
      fill = null,
      stroke = SolidColor(Color(0xFF000000)),
      strokeLineWidth = 2f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
      pathBuilder = {
        moveTo(6f, 4f)
        verticalLineTo(20f)
        moveTo(12f, 4f)
        verticalLineTo(20f)
        moveTo(18f, 4f)
        verticalLineTo(20f)
      },
    )
    // Knobs: short thick strokes with round caps read as filled dots.
    path(
      fill = null,
      stroke = SolidColor(Color(0xFF000000)),
      strokeLineWidth = 4.5f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
      pathBuilder = {
        moveTo(6f, 8.75f)
        lineTo(6f, 9.25f)
        moveTo(12f, 14.75f)
        lineTo(12f, 15.25f)
        moveTo(18f, 7.25f)
        lineTo(18f, 7.75f)
      },
    )
  }.build()

/**
 * UI Round 4 (R12): closed-book glyph with a spine line — the .epub import
 * hero. Matches the stroked language of AppClock/AppReplayArrow/AppEngine.
 */
val AppEpub: ImageVector =
  ImageVector.Builder(
    name = "AppIcons.Epub",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
  ).apply {
    // Book body: straight right edge, rounded spine-side corners.
    path(
      fill = null,
      stroke = SolidColor(Color(0xFF000000)),
      strokeLineWidth = 2f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
      pathBuilder = {
        moveTo(18.5f, 2.5f)
        horizontalLineTo(8f)
        arcTo(2.5f, 2.5f, 0f, false, false, 5.5f, 5f)
        verticalLineTo(19f)
        arcTo(2.5f, 2.5f, 0f, false, false, 8f, 21.5f)
        horizontalLineTo(18.5f)
        close()
      },
    )
    // Spine rule just inside the rounded left edge.
    path(
      fill = null,
      stroke = SolidColor(Color(0xFF000000)),
      strokeLineWidth = 2f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
      pathBuilder = {
        moveTo(9f, 2.5f)
        verticalLineTo(21.5f)
      },
    )
  }.build()

/** Downward arrow icon for reader jump-to-active sentence floating pill. */
val AppArrowDown: ImageVector =
  ImageVector.Builder(
    name = "AppIcons.ArrowDown",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
  ).apply {
    path(
      fill = null,
      stroke = SolidColor(Color(0xFF000000)),
      strokeLineWidth = 2.5f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
      pathBuilder = {
        moveTo(12f, 5f)
        verticalLineTo(19f)
        moveTo(5f, 12f)
        lineTo(12f, 19f)
        lineTo(19f, 12f)
      }
    )
  }.build()

/** Upward arrow icon for reader jump-to-active sentence floating pill. */
val AppArrowUp: ImageVector =
  ImageVector.Builder(
    name = "AppIcons.ArrowUp",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
  ).apply {
    path(
      fill = null,
      stroke = SolidColor(Color(0xFF000000)),
      strokeLineWidth = 2.5f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
      pathBuilder = {
        moveTo(12f, 19f)
        verticalLineTo(5f)
        moveTo(5f, 12f)
        lineTo(12f, 5f)
        lineTo(19f, 12f)
      }
    )
  }.build()
