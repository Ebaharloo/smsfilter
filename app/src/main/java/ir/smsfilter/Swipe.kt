package ir.smsfilter

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ir.smsfilter.core.InboxPolicy
import ir.smsfilter.core.SwipeAction
import kotlin.math.roundToInt

/**
 * Three-stage swipe used by every list in the app:
 *   drag right   -> secondary action (read / restore)
 *   drag left    -> primary action (archive / block)
 *   drag left past 60% of the row -> heavy action (filter the ad / delete)
 *
 * Directions come from the raw drag sign, so an RTL layout cannot swap them, and the row itself
 * owns the tap target (a clickable child would swallow the drag).
 */
@Composable
fun SwipeRow(
    rightLabel: String,
    leftLabel: String,
    heavyLabel: String,
    onRight: () -> Unit,
    onLeft: () -> Unit,
    onHeavy: () -> Unit,
    enabled: Boolean = true,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    var widthPx by remember { mutableStateOf(1f) }
    var dx by remember { mutableStateOf(0f) }
    val heavy = dx < -widthPx * 0.6f

    Box(Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }) {
        if (dx != 0f) {
            val paint = when {
                dx > 0 -> MaterialTheme.colorScheme.secondaryContainer
                heavy -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                Modifier.matchParentSize().background(paint).padding(horizontal = 24.dp),
                contentAlignment = if (dx > 0) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Text(
                    when {
                        dx > 0 -> rightLabel
                        heavy -> heavyLabel
                        else -> leftLabel
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (heavy) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box(
            Modifier.offset { IntOffset(dx.roundToInt(), 0) }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    // thresholds come from the real pointer size inside this scope; values captured
                    // from composition were stale (width is still 1px on the first layout pass)
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val triggerPx = 72.dp.toPx()
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { acc = 0f },
                        onDragEnd = {
                            when (InboxPolicy.swipeAction(acc, width, triggerPx)) {
                                SwipeAction.HEAVY -> onHeavy()
                                SwipeAction.LEFT -> onLeft()
                                SwipeAction.RIGHT -> onRight()
                                SwipeAction.NONE -> Unit
                            }
                            dx = 0f
                            acc = 0f
                        },
                        onDragCancel = { dx = 0f; acc = 0f },
                        onHorizontalDrag = { _, delta ->
                            acc += delta
                            dx = acc.coerceIn(-width * 0.85f, width * 0.85f)
                        }
                    )
                }
                .then(
                    if (onTap == null) Modifier else Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { onTap() }, onLongPress = onLongPress?.let {
                            { _: androidx.compose.ui.geometry.Offset -> it() }
                        })
                    }
                )
        ) { content() }
    }
}
