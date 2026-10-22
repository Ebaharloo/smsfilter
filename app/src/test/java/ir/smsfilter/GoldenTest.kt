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
}
