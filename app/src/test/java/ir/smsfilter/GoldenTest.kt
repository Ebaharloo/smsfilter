package ir.smsfilter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real Kotlin classifier over tests/golden.jsonl on the JVM - no emulator, ~1s loop.
 * The Python mirror (tools/mirror.py) runs the same file, so rule tuning is checked in both.
 */
class GoldenTest {

    private val rules = RuleSet(JSONObject(File("../rules/rules.json").readText()))
    private val model = Model(JSONObject(File("src/main/assets/model.json").readText()))
    private val clf = Classifier(rules, model)

    private fun rows(): List<JSONObject> =
        File("../tests/golden.jsonl").readLines()
            .filter { it.isNotBlank() }
            .map { JSONObject(it) }

    @Test
    fun goldenSetMatches() {
        val failures = mutableListOf<String>()
        for (r in rows()) {
            val contacts = r.optJSONArray("contacts")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                ?: emptySet()
            val v = clf.classify(r.getString("text"), r.optString("sender"), contacts)
            if (v.label.name != r.getString("label")) {
                failures += "${r.getString("label")} -> ${v.label} (${v.score}) ${v.why}: ${r.getString("text").take(60)}"
            }
        }
        assertTrue("mismatches:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** The only unacceptable result: an IMPORTANT message auto-filtered. */
    @Test
    fun importantNeverFiltered() {
        val fatal = rows().filter { it.getString("label") != "SPAM" }.filter {
            clf.classify(it.getString("text"), it.optString("sender")).label == Label.SPAM
        }
        assertEquals("important messages classified SPAM", 0, fatal.size)
    }

    @Test
    fun normalizationHandlesPersianVariants() {
        assertEquals("تخفیف", Normalizer.normalize("تخـفیف"))
        assertEquals("12345", Normalizer.normalize("۱۲۳۴۵"))
        assertEquals("بانک ملی", Normalizer.normalize("بانك ملى"))
        assertEquals("وام", Normalizer.squash(Normalizer.normalize("و ا م")))
    }

    /** what the user's taps contribute, without Android */
    private class FakeLearned(
        private val hits: Int,
        private val blockedSenders: Set<String> = emptySet(),
        private val allowedSenders: Set<String> = emptySet()
    ) : LearnedSignals {
        override fun adWordHits(normNoSpaces: String) = hits
        override fun blocked() = blockedSenders
        override fun allowed() = allowedSenders
    }

    /**
     * Real failure: a DIGIKALA delivery notice («کد تحویل … مامور ارسال») scored SPAM 80 because the
     * 9 ad words learned from DIGIKALA promo taps are also delivery vocabulary. Learned words must not
     * outvote a guard hit.
     */
    @Test
    fun learnedAdWordsDoNotOverrideTheGuard() {
        val delivery = "کاربر نمونه عزیز!\nسفارشتون تا چند ساعت آینده به دستتون می‌رسه. " +
            "کد تحویل رو در زمان دریافت سفارش به مامور ارسال دیجی‌کالا اعلام کنید."
        val v = Classifier(rules, model, FakeLearned(9)).classify(delivery, "DIGIKALA")
        assertEquals(Label.IMPORTANT, v.label)
        assertTrue(v.why.any { it.startsWith("guard:") })
    }

    /** the same learned words must still catch a real promo that trips no guard */
    @Test
    fun learnedAdWordsStillFilterUnguardedAds() {
        val promo = "🔥تخفیف ویژه تا ۴۰٪\njanebi.com/a08\nلغو۱۱"
        assertEquals(Label.SPAM, Classifier(rules, model, FakeLearned(9)).classify(promo, "+989120000000").label)
    }

    /**
     * One sender does both. Restoring a message from DIGIKALA must stop sender reputation from
     * filtering them, but it must NOT let DIGIKALA ads through - content still decides.
     */
    @Test
    fun allowlistedSenderStillLosesWhenTheContentIsAnAd() {
        val learned = FakeLearned(9, blockedSenders = setOf("DIGIKALA"), allowedSenders = setOf("DIGIKALA"))
        val ad = "تخفیف ویژه janebi.com/a08 لغو۱۱"
        assertEquals(Label.SPAM, Classifier(rules, model, learned).classify(ad, "DIGIKALA").label)
    }

    /** ...and the order/delivery notice from that same sender comes back to the inbox */
    @Test
    fun allowlistedSenderKeepsOrderNotices() {
        val learned = FakeLearned(9, allowedSenders = setOf("DIGIKALA"))
        val delivery = "سفارش شما ۵۰۷۵۳۸ آماده تحویل است، کد تحویل ۴۹۴۲ به مامور ارسال بگویید."
        val v = Classifier(rules, model, learned).classify(delivery, "DIGIKALA")
        assertEquals(Label.IMPORTANT, v.label)
        assertTrue(v.why.any { it.startsWith("guard:") })
    }

    /** reputation alone never filters: a plain notice from a sender the user once marked is kept */
    @Test
    fun senderReputationAloneDoesNotFilter() {
        val learned = FakeLearned(9, blockedSenders = setOf("SNAPP"))
        val notice = "سفارش شما ثبت شد و در حال آماده‌سازی است."
        assertEquals(Label.IMPORTANT, Classifier(rules, model, learned).classify(notice, "SNAPP").label)
    }
}
