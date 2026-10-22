package ir.smsfilter

import ir.smsfilter.Normalizer.normalize
import ir.smsfilter.Normalizer.squash

enum class Label { IMPORTANT, SPAM, UNSURE }

data class Verdict(val label: Label, val score: Int, val why: List<String>, val norm: String)

/** Stage order is a safety property, not a preference. Mirrored by tools/mirror.py. */
class Classifier(
    private val rules: RuleSet,
    private val model: Model? = null,
    private val learned: Learned? = null
) {

    fun classify(text: String, sender: String = "", contacts: Set<String> = emptySet()): Verdict {
        val norm = normalize(text)
        val sq = squash(norm)
        val why = mutableListOf<String>()
        val sLow = normalize(sender)

        // 1. sender rules win over everything
        if (rules.blockExact.any { normalize(it) == sLow }) return Verdict(Label.SPAM, 99, listOf("block:$sender"), norm)
        if (rules.allowExact.any { normalize(it) == sLow }) return Verdict(Label.IMPORTANT, 0, listOf("allow:$sender"), norm)
        rules.allowContains.firstOrNull { normalize(it) in sLow }?.let {
            return Verdict(Label.IMPORTANT, 0, listOf("allow~:$it"), norm)
        }
        // contacts are stored as 9-digit suffixes (see Contacts.kt)
        Contacts.suffixOf(sender)?.let { if (it in contacts) return Verdict(Label.IMPORTANT, 0, listOf("contact"), norm) }

        // 2. content score
        var score = 0
        for (g in rules.spam) {
            var hits = 0
            for (term in g.terms) {
                val t = squash(normalize(term))
                if (t.isEmpty() || t !in sq) continue
                score += g.weight
                why += term
                if (++hits >= 2) break // per-group cap: no runaway scores from near-synonyms
            }
        }
        for (u in URL.findAll(norm).map { it.value }.toSet()) {
            val short = rules.shortlinkHosts.any { it in u }
            score += if (short) rules.shortlinkWeight else rules.urlWeight
            why += "url:${u.take(30)}"
        }
        val emoji = countEmoji(text)
        val hasUrl = URL.containsMatchIn(norm)
        when {
            emoji >= 5 -> { score += rules.emojiWeight; why += "emoji:$emoji" }
            emoji >= 2 -> { score += 1; why += "emoji:$emoji" }
        }
        val lone = LONE_LETTER.findAll(norm).count()
        if (lone >= 3) { score += rules.spacedWeight; why += "spaced_letters:$lone" }
        score += rules.unknownSenderWeight

        // 3. guard: hard groups always protect; soft groups protect only when the model is unsure.
        // Measured on 10k real messages: unconditional soft guard leaked 27pp of ads for no
        // extra safety, so soft guard yields to high-confidence model output.
        fun hit(group: String, terms: List<String>): String? =
            terms.firstOrNull { squash(normalize(it)).let { s -> s.isNotEmpty() && s in sq } }
                ?.let { "guard:$group:$it" }

        val hasAmount = rules.amountRegex.containsMatchIn(norm)
        var guardHit: String? = null
        // hard groups first: a soft hit must never shadow a hard one (a fund notice saying
        // "واریز شد" was matching an earlier soft group and then getting filtered)
        for ((group, terms) in rules.guardAbs.sortedByDescending { it.first in rules.guardHard }) {
            if (group == "transaction" && !hasAmount) continue   // confirmations (transaction_confirm) need no amount
            guardHit = hit(group, terms)
            if (guardHit != null) break
        }
        val p = model?.prob(text) ?: 1.0
        val sq2 = squash(norm)
        val hard = guardHit != null && guardHit.substringAfter("guard:").substringBefore(":") in rules.guardHard
        val learnedHits = learned?.adWordHits(sq2) ?: 0
        val senderBlocked = learned?.blocked()?.contains(sender) == true

        // learned ad phrases beat the soft guard (that is how a "بانک"-mentioning ad gets caught),
        // but never the hard guard: OTP / transaction / government stay protected regardless
        if (!hard) {
            if (senderBlocked) return Verdict(Label.SPAM, 90, listOf("learned:sender") + why, norm)
            if (learnedHits >= 2) return Verdict(Label.SPAM, 80, listOf("learned:words=$learnedHits") + why, norm)
            // one learned word is enough when the message also looks promotional: an emoji or a link.
            // Unknown sender alone is NOT a reason to filter - plenty of real people are not in contacts.
            if (learnedHits >= 1 && (emoji >= 1 || hasUrl)) {
                return Verdict(Label.SPAM, 78, listOf("learned:words=$learnedHits") + why, norm)
            }
        }
        // emoji-stuffed promo: needs a second signal (link or promo/finance keywords) and must not
        // touch a guarded message, so a friend's emoji message or a bank notice is never caught here
        if (!hard && guardHit == null && emoji >= 2 && (hasUrl || score >= rules.thrUnsure)) {
            return Verdict(Label.SPAM, 70, listOf("emoji:$emoji") + why, norm)
        }
        if (guardHit != null && (hard || p < rules.softGuardConf)) {
            return Verdict(Label.IMPORTANT, score, listOf(guardHit) + why, norm)
        }

        // The model is the detector; keywords above are diagnostics only (they scored 0.04 recall
        // on 5707 real messages, so they must never decide). Guard ran first and already returned.
        val label = if (model != null) {
            why += "p=%.3f".format(p)
            when {
                p >= rules.spamP -> Label.SPAM
                p >= rules.unsureP -> Label.UNSURE
                else -> Label.IMPORTANT
            }
        } else {
            when {
                score >= rules.thrSpam -> Label.SPAM
                score >= rules.thrUnsure -> Label.UNSURE
                else -> Label.IMPORTANT
            }
        }
        return Verdict(label, score, why, norm)
    }

    private fun countEmoji(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF) n++
            i += Character.charCount(cp)
        }
        return n
    }

    private companion object {
        val URL = Regex("(?:https?://|www\\.)[^\\s]+|(?:[a-z0-9-]+\\.)+(?:ir|com|net|org|me|io|co|info|shop|site|online|xyz)\\b(?:/[^\\s]*)?")
        val LONE_LETTER = Regex("(?<!\\S)[\\u0600-\\u06FFa-z](?!\\S)")
    }
}
