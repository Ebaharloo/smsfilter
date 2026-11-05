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
 * Wraps a user-supplied token in FSI/PDI so a Latin sentence frame and a Persian value cannot
 * re-order each other ("not " + a Persian term reads as one directionless run without this).
 * Same isolation character the message pipeline uses in InboxPolicy.forDisplay.
 */
internal fun isolate(text: String) = "\u2068$text\u2069"

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
    style: TextStyle = TextStyle(),
    color: Color = Color.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    /** text already went through InboxPolicy.forDisplay (e.g. precomputed off the UI thread) */
    preformatted: Boolean = false,
    modifier: Modifier = Modifier
) {
    val farsi = remember(text) { isPersian(text) }
    val shown = remember(text, farsi, preformatted) {
        if (farsi && !preformatted) InboxPolicy.forDisplay(text) else text
    }
    Text(
        shown,
        fontSize = fontSize,
        color = color,
        lineHeight = lineHeight,
        maxLines = maxLines,
        overflow = overflow,
        style = style.copy(textDirection = if (farsi) TextDirection.Rtl else TextDirection.Ltr),
        textAlign = if (farsi) TextAlign.Right else TextAlign.Start,
        modifier = modifier
    )
}
