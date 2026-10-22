package ir.smsfilter.core

/**
 * Pure inbox rules: no Android imports, no I/O, no globals.
 *
 * Everything that decides what the user sees or gets notified about lives here so it can be
 * unit-tested on the JVM. Android-side code only adapts provider rows into [ThreadRow] and
 * applies the answer - that keeps new UI work from silently changing filter behaviour.
 */
data class ThreadRow(
    val id: Long,
    val address: String,
    val snippet: String,
    val date: Long,
    val unread: Int,
    val outgoing: Boolean
)

enum class Notify { NORMAL, DIGEST, SILENT }

/** result of a horizontal row swipe */
enum class SwipeAction { NONE, RIGHT, LEFT, HEAVY }

/** inbox tabs, mirroring MIUI's "promotional / financial messages in separate tabs" */
enum class Tab(val label: String) {
    ALL("All"),
    TRANSACTIONS("Transactions"),
    OTP("Codes"),
    PROMOTIONS("Promotions")
}

object InboxPolicy {

    /**
     * Which tab a conversation belongs to, from its newest message.
     * Order matters: a bank message contains «خرید» and an ad contains «کد تخفیف»,
     * so codes win over transactions and promotions can never swallow a real code.
     */
    fun categoryOf(snippet: String): Tab {
        val s = snippet.lowercase()
        return when {
            OTP_MARKERS.any { s.contains(it) } -> Tab.OTP
            TX_MARKERS.any { s.contains(it) } -> Tab.TRANSACTIONS
            PROMO_MARKERS.any { s.contains(it) } -> Tab.PROMOTIONS
            else -> Tab.ALL
        }
    }

    /**
     * A saved contact is never a promotion or a transaction: people write words like "تخفیف" or
     * "واریز" in normal conversation, and filing a friend under Promotions is how a real message
     * gets missed. Contacts stay in All.
     */
    fun inTab(row: ThreadRow, tab: Tab, isContact: Boolean = false): Boolean =
        tab == Tab.ALL || (!isContact && categoryOf(row.snippet) == tab)

    /** counts per tab, for the tab labels */
    fun counts(rows: List<ThreadRow>, contacts: Set<String> = emptySet()): Map<Tab, Int> {
        val out = HashMap<Tab, Int>()
        rows.forEach { row ->
            if (suffix(row.address) in contacts) return@forEach
            val tab = categoryOf(row.snippet)
            out[tab] = (out[tab] ?: 0) + 1
        }
        out[Tab.ALL] = rows.size
        return out
    }

    /**
     * Which swipe action a drag offset means. Pure so the gesture contract (read / archive /
     * filter, and the 60% "heavy" band) is unit-tested instead of eyeballed.
     */
    fun swipeAction(dx: Float, widthPx: Float, triggerPx: Float): SwipeAction = when {
        dx <= -(widthPx * 0.6f) -> SwipeAction.HEAVY
        dx <= -triggerPx -> SwipeAction.LEFT
        dx >= triggerPx -> SwipeAction.RIGHT
        else -> SwipeAction.NONE
    }

    /** last 9 digits, the same key the filter compares senders on */
    fun suffix(address: String): String {
        val digits = address.filter { it in '0'..'9' }
        return digits.takeLast(9)
    }

    private val OTP_MARKERS = listOf(
        "رمز پویا", "رمز یکبار", "رمز یک بار", "رمز دوم", "رمز دوم کارت", "رمز اینترنتی",
        "کد تایید", "کد تأیید", "کد فعالسازی", "کد فعال سازی", "کد ورود", "کد امنیتی",
        "کد یکبار", "otp", "verification code", "cvv2"
    )

    private val TX_MARKERS = listOf(
        "واریز", "برداشت", "مانده", "موجودی", "تراکنش", "انتقال", "کارت به کارت",
        "خرید", "پرداخت", "ریال", "تومان", "صورتحساب", "قسط", "اعلام وصول"
    )

    private val PROMO_MARKERS = listOf(
        "تخفیف", "حراج", "فروش ویژه", "ویژه", "هدیه", "برنده", "قرعه", "جایزه",
        "لغو ۱۱", "لغو11", "لغو اشتراک", "وام", "بدون ضامن", "کلیک کنید", "ثبت نام",
        "ثبت‌نام", "ارسال رایگان", "مشاوره", "سرمایه", "درآمد", "کد تخفیف"
    )

    /** archived rows are hidden from the inbox and shown only in the archive view */
    fun visible(
        rows: List<ThreadRow>,
        archivedIds: Set<Long>,
        archivedMode: Boolean,
        query: String = "",
        names: Map<String, String?> = emptyMap(),
        spamIds: Set<Long> = emptySet()
    ): List<ThreadRow> {
        // a conversation the user (or a sweep) marked as advertising belongs to the Ads page only:
        // showing it in Archive as well made the swipe look like it did nothing
        val scoped = rows.filter {
            it.id !in spamIds && if (archivedMode) it.id in archivedIds else it.id !in archivedIds
        }
        if (query.isBlank()) return scoped
        return scoped.filter { row ->
            row.address.contains(query, true) ||
                row.snippet.contains(query, true) ||
                (names[row.address]?.contains(query, true) == true)
        }
    }

    /** pinned conversations first, then newest-first; pinning never reorders within a group */
    fun order(rows: List<ThreadRow>, pinnedIds: Set<Long>): List<ThreadRow> =
        rows.sortedWith(
            compareByDescending<ThreadRow> { it.id in pinnedIds }.thenByDescending { it.date }
        )

    /**
     * What the notification should be.
     * - muted conversations never notify, but their messages are still stored
     * - spam never notifies individually, it only updates the digest counter
     */
    fun notifyFor(muted: Boolean, spam: Boolean): Notify = when {
        spam -> Notify.DIGEST
        muted -> Notify.SILENT
        else -> Notify.NORMAL
    }

    private val OTP_WORDS = listOf("رمز", "کد", "code", "otp", "password", "پین")
    private val OTP_DIGITS = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")

    /** code from a message that talks about a code, or null - never guesses on plain text */
    fun otp(body: String): String? {
        if (OTP_WORDS.none { body.contains(it, true) }) return null
        val cleaned = body.replace(",", " ").replace("،", " ")
        return OTP_DIGITS.find(cleaned)?.groupValues?.get(1)
    }

    fun digestTitle(count: Int): String = "$count تبلیغ فیلتر شد"

    /**
     * Which pinned message the banner shows: the newest pinned message that is still in the
     * conversation. A pin whose message was deleted must not leave a dangling banner.
     */
    fun pinnedBanner(pinnedIds: Set<Long>, messageIdsOldestFirst: List<Long>): Long? =
        messageIdsOldestFirst.lastOrNull { it in pinnedIds }

    /**
     * Bidi control characters some senders embed in the message itself (banks love U+202A LRE / U+202C PDF).
     * They fight the paragraph direction we set, which is how "1,234,567" ends up painted as "756,402,1".
     */
    private fun stripBidi(text: String): String = text.filterNot {
        it == '\u061C' || it in '\u200E'..'\u200F' || it in '\u202A'..'\u202E' ||
            it in '\u2066'..'\u2069' || it in '\u206A'..'\u206F'
    }

    /** links, latin tokens carrying a digit, amounts/dates/card numbers */
    private val LTR_RUN = Regex(
        "(https?://|www\\.)\\S+" +
            "|\\b(?:t\\.me|bit\\.ly|b2n\\.ir|yun\\.ir|zarinp\\.al)\\S*" +
            "|[a-zA-Z0-9-]+\\.(?:ir|com|net|org)\\S*" +
            // commas stay inside the run: splitting "1,234,567" into three isolates is what
            // made the amount paint as "756,402,1" inside an RTL paragraph
            "|[A-Za-z0-9@._,:/٬٫-]*[0-9][A-Za-z0-9@._,:/٬٫-]*" +
            "|[0-9]"
    )

    /**
     * Text ready to paint in an RTL paragraph: every sender-supplied direction mark removed, and each
     * LTR run wrapped once in LRI/PDI so its internal order survives the surrounding RTL text.
     */
    fun forDisplay(text: String): String {
        val clean = stripBidi(text)
        return if (clean.none { it in '\u0600'..'\u06FF' }) clean
        else LTR_RUN.replace(clean) { "\u2066" + it.value + "\u2069" }
    }

    /** regexes shared by the UI for link/number affordances */
    val URL: Regex = Regex("""(https?://|www\.)\S+|\b(?:t\.me|b2n\.ir|yun\.ir|rizy\.ir|zarinp\.al|bit\.ly)/\S+""")
    val PHONE: Regex = Regex("""(?:\+98|0)9\d{9}""")
}
