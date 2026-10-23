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


    /** names by suffix + the suffix set the filter uses, from a single provider sweep */
    data class Index(val names: Map<String, String>, val suffixes: Set<String>)

    @Volatile private var indexCache: Pair<Long, Index>? = null

    /**
     * One pass over the address book, cached for a minute.
     *
     * Callers used to run two separate sweeps per inbox load (names, then suffixes), and the receive
     * path asked for the suffix set on every single message. Contacts change slowly; a stale minute
     * is cheaper than a full `Phone.CONTENT_URI` scan 50 times an hour.
     */
    fun index(ctx: Context): Index {
        val now = android.os.SystemClock.elapsedRealtime()
        indexCache?.let { if (now - it.first < 60_000) return it.second }
        val names = HashMap<String, String>(512)
        val suffixes = HashSet<String>(512)
        var failed = false
        var opened = false
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { c ->
                opened = true
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val suffix = digits(c.getString(1).orEmpty()).takeLast(9)
                    if (suffix.length != 9) continue
                    suffixes += suffix
                    if (name.isNotEmpty()) names.putIfAbsent(suffix, name)
                }
            }
        } catch (t: Throwable) {
            // no permission or provider trouble: behave as if the address book were empty
            failed = true
        }
        val fresh = Index(names, suffixes)
        // a failed sweep (boot, provider busy, permission glitch) must not be cached: the receive
        // path would then treat saved contacts as unknown for a whole minute, and their messages
        // would be filtered out of the inbox instead of passing the contact guard
        if (failed || !opened) return fresh
        indexCache = now to fresh
        return fresh
    }

    fun suffixes(ctx: Context): Set<String> = index(ctx).suffixes

    /**
     * Names for a whole inbox in one provider query, keyed by the same last-9-digit suffix the
     * filter uses. Asking per row meant ~150 content queries on every list load.
     */
    fun namesFor(ctx: Context): Map<String, String> = index(ctx).names

    /** digits only, Persian/Arabic digits folded to ASCII first */
    fun digits(raw: String): String {
        val n = Normalizer.normalize(raw)
        val sb = StringBuilder(n.length)
        for (ch in n) if (ch in '0'..'9') sb.append(ch)
        return sb.toString()
    }

    fun suffixOf(number: String): String? = digits(number).takeLast(9).takeIf { it.length == 9 }

    /** contact photo uri for a number, or null when there is none */
    fun photoFor(ctx: Context, address: String): android.net.Uri? =
        lookup(ctx, address, ContactsContract.PhoneLookup.PHOTO_URI)
            ?.let { android.net.Uri.parse(it) }

    /** sender label as shown in a list: provider junk like ".IRANCELL." or "**bank**" cleaned up */
    fun displayName(address: String): String {
        val trimmed = address.trim().trim('.', '*', ' ', '_')
        return trimmed.ifBlank { address.ifBlank { "Unknown" } }
    }

    /** contact display name for a number, or null when unknown */
    fun nameFor(ctx: Context, address: String): String? =
        lookup(ctx, address, ContactsContract.PhoneLookup.DISPLAY_NAME)

    /**
     * PhoneLookup for every way this number could be dialled. A number arriving as +989121234567
     * matched nothing when the contact was saved as 09121234567, so a saved sender still showed up
     * as a bare number (that is the "is this contact saved?" question).
     */
    private fun lookup(ctx: Context, address: String, column: String): String? {
        for (candidate in ir.smsfilter.core.ComposeIntent.lookupVariants(address)) {
            try {
                val uri = android.net.Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(candidate)
                )
                ctx.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            } catch (t: Throwable) {
                // provider trouble: fall through to the next form
            }
        }
        return null
    }
}
