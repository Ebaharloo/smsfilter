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

    fun inTab(row: ThreadRow, tab: Tab): Boolean =
        tab == Tab.ALL || categoryOf(row.snippet) == tab

    /** counts per tab, for the chip labels */
    fun counts(rows: List<ThreadRow>): Map<Tab, Int> {
        val out = HashMap<Tab, Int>()
        rows.forEach { out[categoryOf(it.snippet)] = (out[categoryOf(it.snippet)] ?: 0) + 1 }
        out[Tab.ALL] = rows.size
        return out
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
        names: Map<String, String?> = emptyMap()
    ): List<ThreadRow> {
        val scoped = rows.filter { if (archivedMode) it.id in archivedIds else it.id !in archivedIds }
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

    /** regexes shared by the UI for link/number affordances */
    val URL: Regex = Regex("""(https?://|www\.)\S+|\b(?:t\.me|b2n\.ir|yun\.ir|rizy\.ir|zarinp\.al|bit\.ly)/\S+""")
    val PHONE: Regex = Regex("""(?:\+98|0)9\d{9}""")
}
