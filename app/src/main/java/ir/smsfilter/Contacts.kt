package ir.smsfilter

import android.content.Context
import android.provider.ContactsContract

/**
 * Contact allow-list. Any message from someone in the address book is never filtered - the user
 * knowing the sender outranks any content score. Numbers are compared on their last 9 digits,
 * because the same person arrives as +98912..., 0912..., 0098912... depending on the sender.
 */
object Contacts {

    fun suffixes(ctx: Context): Set<String> {
        val out = HashSet<String>(512)
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    digits(c.getString(0).orEmpty()).takeLast(9)
                        .takeIf { it.length == 9 }?.let { out += it }
                }
            }
        } catch (t: Throwable) {
            // no permission or provider trouble: behave as if the address book were empty
        }
        return out
    }

    /** digits only, Persian/Arabic digits folded to ASCII first */
    fun digits(raw: String): String {
        val n = Normalizer.normalize(raw)
        val sb = StringBuilder(n.length)
        for (ch in n) if (ch in '0'..'9') sb.append(ch)
        return sb.toString()
    }

    fun suffixOf(number: String): String? = digits(number).takeLast(9).takeIf { it.length == 9 }
}
