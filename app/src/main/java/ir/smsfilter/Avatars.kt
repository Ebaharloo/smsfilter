package ir.smsfilter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Contact photos, cached so scrolling does not re-decode.
 * LruCache rejects null values, so "this contact has no photo" is remembered separately.
 */
private val photoCache = LruCache<String, Bitmap>(80)
private val noPhoto = java.util.Collections.synchronizedSet(HashSet<String>())

/** MIUI uses a neutral grey circle for unknown senders; known contacts get their photo. */
private val NEUTRAL_AVATAR = Color(0xFF3C3C3C)
private val NEUTRAL_INITIALS = Color(0xFFB0B0B0)

private val AVATAR_COLORS = listOf(
    Color(0xFF5C6BC0), Color(0xFF26A69A), Color(0xFFEF6C00), Color(0xFF8E24AA),
    Color(0xFF00897B), Color(0xFFD81B60), Color(0xFF3949AB), Color(0xFF6D4C41)
)

fun avatarColor(address: String): Color =
    AVATAR_COLORS[(address.hashCode() and 0x7FFFFFFF) % AVATAR_COLORS.size]

fun initialsOf(address: String): String {
    val digits = Contacts.digits(address)
    return when {
        address.any { it.isLetter() } -> address.filter { it.isLetter() || it.isDigit() }.take(2).uppercase()
        digits.length >= 4 -> digits.takeLast(2)
        else -> "?"
    }
}

private fun loadPhoto(ctx: Context, address: String): Bitmap? = try {
    val uri = Contacts.photoFor(ctx, address) ?: return null
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeStream(input, null, opts)
    }
} catch (t: Throwable) {
    null
}

@Composable
fun Avatar(
    address: String,
    size: Dp,
    fallbackFontSize: Int = 14,
    neutral: Boolean = false,
    tint: Color? = null
) {
    val ctx = LocalContext.current
    var photo by remember(address) { mutableStateOf(photoCache.get(address)) }
    LaunchedEffect(address) {
        if (photo == null && !noPhoto.contains(address)) {
            val b = withContext(Dispatchers.IO) { loadPhoto(ctx, address) }
            if (b != null) photoCache.put(address, b) else noPhoto += address
            photo = b
        }
    }
    val current = photo
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape)
        )
    } else {
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(tint ?: if (neutral) NEUTRAL_AVATAR else avatarColor(address)),
            contentAlignment = Alignment.Center
        ) {
            // shortcodes have no name to initialise: a person glyph reads as "sender",
            // digits would read as an unread count next to the real badge
            if (address.none { it.isLetter() }) {
                Icon(
                    Icons.Filled.Person, contentDescription = null,
                    tint = if (neutral) NEUTRAL_INITIALS else Color.White,
                    modifier = Modifier.size(size * 0.5f)
                )
            } else {
                Text(
                    initialsOf(address),
                    color = if (neutral) NEUTRAL_INITIALS else Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = fallbackFontSize.sp
                )
            }
        }
    }
}
