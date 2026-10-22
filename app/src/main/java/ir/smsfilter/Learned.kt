package ir.smsfilter

import android.content.Context

/**
 * What the user taught the app by tapping «تبلیغ» / «این مهم است».
 *
 * Deliberately content-first: sender numbers churn and the same number sends both ads and real
 * notices, so a sender tally is only a tiebreaker that a single "important" tap cancels.
 * Learned ad phrases may override the SOFT guard (bank/telco-ish wording); they never override
 * the hard guard (OTP, transaction confirmations, government).
 */
class Learned(ctx: Context) {

    private val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** words seen in user-confirmed ads, longer words only - short ones are too common */
    val adWords: Set<String>
        get() = p.getStringSet(KEY_WORDS, emptySet()) ?: emptySet()

    fun markAd(sender: String, text: String) {
        val words = text.split(Regex("[^\\p{L}\\p{N}]+"))
            .map { Normalizer.normalize(it).replace(" ", "") }
            .filter { it.length >= 4 && it !in STOP }
            .sortedByDescending { it.length }
            .take(8)
        val next = adWords.toMutableSet().apply { addAll(words) }
        // keep the store bounded: newest 2000 words is plenty for one person's inbox
        val bounded = if (next.size > 2000) next.toList().takeLast(2000).toSet() else next
        p.edit().putStringSet(KEY_WORDS, bounded).apply()

        val n = (p.getInt(KEY_COUNT + sender, 0)) + 1
        p.edit().putInt(KEY_COUNT + sender, n).apply()
        if (n >= 2) p.edit().putStringSet(KEY_BLOCKED, blocked().toMutableSet().apply { add(sender) }).apply()
    }

    /** one "important" tap from a sender cancels its ad tally */
    fun markImportant(sender: String) {
        p.edit().remove(KEY_COUNT + sender).apply()
        val b = blocked().toMutableSet().apply { remove(sender) }
        p.edit().putStringSet(KEY_BLOCKED, b).apply()
    }

    fun blocked(): Set<String> = p.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()

    /** how many learned ad words this message contains */
    fun adWordHits(normNoSpaces: String): Int = adWords.count { it in normNoSpaces }

    private companion object {
        const val PREFS = "learned"
        const val KEY_WORDS = "ad_words"
        const val KEY_BLOCKED = "blocked_senders"
        const val KEY_COUNT = "ads_from:"
        // common Persian words that appear in both ads and real messages
        val STOP = setOf("برای", "اینکه", "است", "شد", "شده", "کرد", "کند", "باشید", "میشود",
            "همین", "کلیک", "کنید", "لغو", "دارد", "شما", "مبلغ", "ریال", "تومان", "پیامک")
    }
}
