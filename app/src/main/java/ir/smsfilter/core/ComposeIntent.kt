package ir.smsfilter.core

/**
 * Turning an `sms:`/`smsto:`/`mms:`/`mmsto:` URI into a compose request.
 *
 * This is how the address book, a browser link or another app opens the messenger, e.g.
 * `smsto:+989121234567?body=hi`. Pure Kotlin so the parsing and the number matching are unit
 * tested instead of being discovered on the phone.
 */
object ComposeIntent {

    /** schemes the app answers to, same set the stock MIUI messenger declares */
    private val SCHEMES = setOf("sms", "smsto", "mms", "mmsto")

    /**
     * number (still as written, e.g. "+989121234567") and optional prefilled body, or null when
     * this URI is not a message link.
     */
    fun parse(uri: String): Pair<String, String?>? {
        val scheme = uri.substringBefore(':', "").lowercase()
        if (scheme !in SCHEMES) return null
        var rest = uri.substringAfter(':')
        if (rest.startsWith("//")) rest = rest.removePrefix("//")
        val body = rest.substringAfter("body=", "")
            .substringBefore('&')
            .takeIf { it.isNotEmpty() }
            ?.let { decodeForm(it) }
        var number = rest.substringBefore('?').substringBefore('&')
        // sms:+98...;?body= is legal (semicolon after the number)
        number = number.removeSuffix(";")
        if (number.isEmpty()) {
            // sms://?body= is a compose with no recipient; still useful (picker opens)
            return if (body == null) null else "" to body
        }
        return decodeNumber(number) to body
    }

    /**
     * Comparison key for "is this the same phone number": national digits.
     * +989121234567, 00989121234567, 989121234567 and 09121234567 all become 9121234567,
     * so a number coming from the address book cannot open a second thread for a conversation
     * that already exists. Short codes (300017) are returned unchanged.
     */
    fun numberKey(address: String): String {
        var d = address.filter { it in '0'..'9' }
        when {
            d.length >= 12 && d.startsWith("0098") -> d = d.drop(4)
            d.length >= 12 && d.startsWith("98") -> d = d.drop(2)
            d.length == 11 && d.startsWith("0") -> d = d.drop(1)
        }
        return d
    }

    /** same conversation? compares the national key, then the 9-digit tail as a fallback */
    fun sameNumber(a: String, b: String): Boolean {
        val ka = numberKey(a)
        val kb = numberKey(b)
        if (ka.isNotEmpty() && ka == kb) return true
        val ta = ka.takeLast(9)
        val tb = kb.takeLast(9)
        return ta.length == 9 && ta == tb
    }

    /**
     * Percent-decoding that keeps a literal '+': a phone number written "+98912…" must not turn
     * into " 98912…" just because form decoding treats '+' as a space.
     */
    /**
     * Ways the same number can be written when asking the address book for a name: the address book
     * hands us "+989121234567", the contact may be saved as "09121234567". Without this the thread
     * header showed a bare number for a saved contact opened from the contacts app.
     */
    fun lookupVariants(address: String): List<String> {
        val key = numberKey(address)
        if (key.length != 10) return listOf(address)
        return listOf(address, "+98$key", "0$key", key).distinct()
    }

    private fun decodeNumber(s: String): String = pct(s.replace("+", "%2B"))

    /** message bodies come from apps that encode spaces as '+', so there form decoding is right */
    private fun decodeForm(s: String): String = pct(s)

    private fun pct(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (t: Throwable) {
        s
    }
}
