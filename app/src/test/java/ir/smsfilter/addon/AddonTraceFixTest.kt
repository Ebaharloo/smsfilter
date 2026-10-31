package ir.smsfilter.addon

import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** F1/F2/F3 from the S5 test team: trace truncation, trace self-heal, cooling names in the line. */
class AddonTraceFixTest {

    /** Prefs map plus the one service the clipboard actions need to count as a success. */
    private class P {
        val values = mutableMapOf<String, Any?>()
        val ctx: Context = run {
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(
                AddonTraceFixTest::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { _, m, a ->
                when (m.name) {
                    "putString", "putBoolean", "putInt", "putLong", "putFloat", "putStringSet" -> {
                        values[a[0] as String] = a[1]; editor
                    }
                    "remove" -> { values.remove(a[0] as String); editor }
                    "clear" -> { values.clear(); editor }
                    "commit" -> true
                    else -> null
                }
            } as SharedPreferences.Editor
            val sp = Proxy.newProxyInstance(
                AddonTraceFixTest::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)
            ) { _, m, a ->
                when (m.name) {
                    "getString" -> values[a[0] as String] as? String
                    "getBoolean" -> values[a[0] as String] as? Boolean ?: false
                    "getLong" -> values[a[0] as String] as? Long ?: 0L
                    "contains" -> values.containsKey(a[0] as String)
                    "edit" -> editor
                    else -> null
                }
            } as SharedPreferences
            object : ContextWrapper(null) {
                override fun getSharedPreferences(name: String?, mode: Int) = sp
                override fun getSystemService(name: String): Any? =
                    if (name == Context.CLIPBOARD_SERVICE)
                        ClipboardManager::class.java.getDeclaredConstructor().apply { isAccessible = true }
                            .newInstance() as ClipboardManager
                    else null
            }
        }
    }

    private fun ev(body: String) = SmsEvent("123", body, 0L, 0, 7L, false, false, null, 0)

    private fun line(body: String) = AddonRules.decisionLine(0L, ev(body), emptyList(), emptyList(), "keyword")

    private fun prefix(body: String) = line(body).substringAfter("· 123 · ").substringBefore(" → ")

    @Test
    fun asciiHeadStaysFortyCodePoints() {
        assertEquals("a".repeat(40), prefix("a".repeat(60)))
    }

    @Test
    fun emojiAtTheCutIsKeptWholeNeverSplit() {
        val prefix = prefix("a".repeat(39) + "😀" + "tail")
        assertTrue("whole pair kept: $prefix", prefix.endsWith("😀"))
        assertTrue("last unit is the low half", prefix.last().isLowSurrogate())

        // survives the JSON round trip the real prefs do
        val p = P()
        AddonRules.recordDecision(p.ctx, line("a".repeat(39) + "😀" + "tail"))
        assertEquals(1, AddonRules.recentDecisions(p.ctx).size)
    }

    @Test
    fun corruptedTracePrefSelfHeals() {
        val p = P()
        p.values["addon_decisions"] = "{not json"
        assertEquals(emptyList<String>(), AddonRules.recentDecisions(p.ctx))
        AddonRules.recordDecision(p.ctx, "first")
        AddonRules.recordDecision(p.ctx, "second")
        assertEquals("fresh list started, newest first", listOf("second", "first"), AddonRules.recentDecisions(p.ctx))
    }

    @Test
    fun coolingRuleIsNamedEvenWhenAnotherRuleFired() {
        val p = P()
        val cool = AddonRule(id = "cool", name = "Cool", priority = 1, keywordContains = "کد", actions = listOf("copy_body"), cooldownSeconds = 3600)
        val hot = AddonRule(id = "hot", name = "Hot", priority = 2, keywordContains = "کد", actions = listOf("copy_body"))
        AddonRules.save(p.ctx, listOf(cool, hot))
        AddonRules.markFired(p.ctx, cool, System.currentTimeMillis())
        AddonBus.dispatch(p.ctx, ev("کد 42"))
        val line = AddonRules.recentDecisions(p.ctx).single()
        assertTrue("fired rule reported: $line", line.contains("Hot"))
        assertTrue("action reported: $line", line.contains("copy_body"))
        assertTrue("blocked rule named: $line", line.contains("; cooling: Cool"))
        assertFalse("the hot rule is not listed as cooling: $line", line.contains("cooling: Cool, Hot"))
    }
}
