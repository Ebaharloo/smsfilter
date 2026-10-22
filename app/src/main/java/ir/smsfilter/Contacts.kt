package ir.smsfilter

import android.content.Context
import android.provider.ContactsContract

/**
 * Contact allow-list. Any message from someone in the address book is never filtered - the user
 * knowing the sender outranks any content score. Numbers are compared on their last 9 digits,
 * because the same person arrives as +98912..., 0912..., 0098912... depending on the sender.
 */
object Contacts {

    /** a device contact, as the recipient picker needs it */
    data class Person(val name: String, val number: String)

    /**
     * All phone numbers in the address book, name-sorted, deduped by number.
     * Returns an empty list when READ_CONTACTS is missing, so callers fall back to typing a number.
     */
    fun all(ctx: Context, limit: Int = 3000): List<Person> {
        val out = LinkedHashMap<String, Person>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val number = c.getString(1)?.trim().orEmpty()
                    val key = digits(number).takeLast(9)
                    if (key.length < 4 || number.isBlank()) continue
                    val label = name.ifBlank { number }
                    // a contact with several numbers keeps one row per number
                    out.putIfAbsent(key + "|" + label, Person(label, number))
                }
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return out.values.sortedBy { it.name.lowercase() }
    }


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

    /**
     * Names for a whole inbox in one provider query, keyed by the same last-9-digit suffix the
     * filter uses. Asking per row meant ~150 content queries on every list load.
     */
    fun namesFor(ctx: Context): Map<String, String> {
        val out = HashMap<String, String>(512)
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val suffix = digits(c.getString(1).orEmpty()).takeLast(9)
                    if (name.isNotEmpty() && suffix.length == 9) out.putIfAbsent(suffix, name)
                }
            }
        } catch (t: Throwable) {
            return emptyMap()
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

    /** contact photo uri for a number, or null when there is none */
    fun photoFor(ctx: Context, address: String): android.net.Uri? = try {
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(address)
        )
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.PHOTO_URI), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { android.net.Uri.parse(it) } else null }
    } catch (t: Throwable) {
        null
    }

    /** sender label as shown in a list: provider junk like ".IRANCELL." or "**bank**" cleaned up */
    fun displayName(address: String): String {
        val trimmed = address.trim().trim('.', '*', ' ', '_')
        return trimmed.ifBlank { address.ifBlank { "Unknown" } }
    }

    /** contact display name for a number, or null when unknown */
    fun nameFor(ctx: Context, address: String): String? = try {
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(address)
        )
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (t: Throwable) {
        null
    }
}
