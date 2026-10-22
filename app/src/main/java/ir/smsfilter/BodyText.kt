package ir.smsfilter

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import ir.smsfilter.core.InboxPolicy

internal fun isPersian(text: String) = text.any { it in '\u0600'..'\u06FF' }

/**
 * Every message body in the app goes through here.
 *
 * A Persian sentence painted in a left-to-right paragraph is not just misaligned: the bidi
 * algorithm re-orders its Latin tokens and clips the line on the wrong side, so the first word
 * disappears off the right edge. Per-page `Text` calls kept forgetting that (the Ads page shipped
 * with left-to-right previews while the inbox was correct), so the direction lives in one place.
 */
@Composable
internal fun BodyText(
    text: String,
    fontSize: TextUnit,
    color: Color = Color.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    modifier: Modifier = Modifier
) {
    val farsi = remember(text) { isPersian(text) }
    val shown = remember(text, farsi) { if (farsi) InboxPolicy.forDisplay(text) else text }
    Text(
        shown,
        fontSize = fontSize,
        color = color,
        lineHeight = lineHeight,
        maxLines = maxLines,
        overflow = overflow,
        style = TextStyle(textDirection = if (farsi) TextDirection.Rtl else TextDirection.Ltr),
        textAlign = if (farsi) TextAlign.Right else TextAlign.Start,
        modifier = modifier
    )
}
