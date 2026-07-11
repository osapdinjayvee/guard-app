package com.minsu.guardapp.feature.scan

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * The alignment reticle.
 *
 * A scanner is one of the few screens where the chrome is doing real work: the guard is holding a
 * phone at arm's length in bad light, and the only thing telling them where to point it is this
 * frame. Everything outside it is dimmed so the eye goes to the cutout, the corners are marked so
 * the frame reads as a target rather than a border, and the sweep line says the camera is alive —
 * a still frame on a dark screen is indistinguishable from a hung one.
 *
 * The frame is drawn, not laid out, because it has to be punched *through* a scrim: a Compose
 * Box with a background cannot make a hole in the thing behind it.
 */
@Composable
fun ScannerOverlay(
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val sweep by rememberInfiniteTransition(label = "scan").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweep",
    )

    Canvas(modifier.fillMaxSize()) {
        val frame = reticle()
        val corner = 28.dp.toPx()

        // The scrim, with the reticle punched out of it.
        val cutout = Path().apply {
            addRoundRect(RoundRect(frame, cornerRadius(corner)))
        }
        clipPath(cutout, ClipOp.Difference) {
            drawRect(Color.Black.copy(alpha = 0.62f))
        }

        drawCorners(frame, corner, accent)

        // The sweep, confined to the cutout so it never bleeds over the dimmed surround.
        clipPath(cutout) {
            val y = frame.top + frame.height * sweep
            drawLine(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to accent,
                    1f to Color.Transparent,
                ),
                start = Offset(frame.left, y),
                end = Offset(frame.right, y),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * A square, centred, and biased slightly above centre — a phone held up to a wall sits lower in
 * the hand than the thing it is pointed at, and a dead-centre frame makes the guard stoop.
 */
private fun DrawScope.reticle(): Rect {
    val side = min(size.width, size.height) * 0.72f
    val left = (size.width - side) / 2f
    val top = (size.height - side) / 2f - size.height * 0.06f
    return Rect(offset = Offset(left, top), size = Size(side, side))
}

private fun cornerRadius(px: Float) = androidx.compose.ui.geometry.CornerRadius(px, px)

/**
 * Corner brackets rather than a full outline. A closed rectangle reads as a frame around the
 * picture; four corners read as crosshairs, and tell the guard to put the code *inside* them.
 */
private fun DrawScope.drawCorners(frame: Rect, radius: Float, accent: Color) {
    val arm = frame.width * 0.13f
    val stroke = 4.dp.toPx()

    val path = Path().apply {
        // top-left
        moveTo(frame.left, frame.top + radius + arm)
        lineTo(frame.left, frame.top + radius)
        quadraticTo(frame.left, frame.top, frame.left + radius, frame.top)
        lineTo(frame.left + radius + arm, frame.top)

        // top-right
        moveTo(frame.right - radius - arm, frame.top)
        lineTo(frame.right - radius, frame.top)
        quadraticTo(frame.right, frame.top, frame.right, frame.top + radius)
        lineTo(frame.right, frame.top + radius + arm)

        // bottom-right
        moveTo(frame.right, frame.bottom - radius - arm)
        lineTo(frame.right, frame.bottom - radius)
        quadraticTo(frame.right, frame.bottom, frame.right - radius, frame.bottom)
        lineTo(frame.right - radius - arm, frame.bottom)

        // bottom-left
        moveTo(frame.left + radius + arm, frame.bottom)
        lineTo(frame.left + radius, frame.bottom)
        quadraticTo(frame.left, frame.bottom, frame.left, frame.bottom - radius)
        lineTo(frame.left, frame.bottom - radius - arm)
    }

    drawPath(
        path = path,
        color = accent,
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = stroke,
            cap = StrokeCap.Round,
        ),
    )
}
