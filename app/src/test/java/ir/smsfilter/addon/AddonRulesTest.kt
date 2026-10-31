package ir.smsfilter.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy

class AddonRulesTest {
    private fun event(
        sender: String = "123",
        body: String = "hello",
        fromContact: Boolean = false,
        isSpam: Boolean = false,
        otp: String? = null
    ) = SmsEvent(sender, body, 0L, 0, 1L, isSpam, fromContact, otp, 0)

    @Test
    fun emptyConditionsMatch() {
        assertTrue(AddonRules.matches(AddonRule(enabled = true), event()))
    }

    @Test
    fun disabledNeverMatches() {
        assertFalse(AddonRules.matches(AddonRule(enabled = false), event()))
    }

    @Test
    fun keywordAndSenderAllMustMatch() {
        val rule = AddonRule(senderContains = "123", keywordContains = "otp")
        assertTrue(AddonRules.matches(rule, event(body = "your otp is 42")))
        assertFalse(AddonRules.matches(rule, event(sender = "999", body = "your otp is 42")))
        assertFalse(AddonRules.matches(rule, event(body = "hello")))
    }

    @Test
    fun otpAndContactConditions() {
        val rule = AddonRule(onlyFromContacts = true, otpRequired = true)
        assertTrue(AddonRules.matches(rule, event(fromContact = true, otp = "42")))
        assertFalse(AddonRules.matches(rule, event(fromContact = false, otp = "42")))
        assertFalse(AddonRules.matches(rule, event(fromContact = true, otp = null)))
    }

    @Test
    fun spamConditions() {
        assertTrue(AddonRules.matches(AddonRule(onlySpam = true), event(isSpam = true)))
        assertFalse(AddonRules.matches(AddonRule(onlySpam = true), event(isSpam = false)))
        assertTrue(AddonRules.matches(AddonRule(onlyNotSpam = true), event(isSpam = false)))
        assertFalse(AddonRules.matches(AddonRule(onlyNotSpam = true), event(isSpam = true)))
    }

    @Test
    fun noOtpCondition() {
        assertTrue(AddonRules.matches(AddonRule(otpRequired = false), event(otp = null)))
        assertFalse(AddonRules.matches(AddonRule(otpRequired = false), event(otp = "42")))
    }

    @Test
    fun lengthConditions() {
        val rule = AddonRule(minLength = 5, maxLength = 10)
        assertTrue(AddonRules.matches(rule, event(body = "12345")))
        assertFalse(AddonRules.matches(rule, event(body = "12")))
        assertFalse(AddonRules.matches(rule, event(body = "12345678901")))
    }

    @Test
    fun senderCaseInsensitive() {
        assertTrue(AddonRules.matches(AddonRule(senderContains = "ABC"), event(sender = "xxabcxx")))
        assertFalse(AddonRules.matches(AddonRule(senderContains = "ABC"), event(sender = "xyz")))
    }

    @Test
    fun keywordCaseInsensitive() {
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "OTP"), event(body = "your otp code")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "OTP"), event(body = "hello")))
    }

    @Test
    fun matchAllRequiresEveryActiveCondition() {
        val rule = AddonRule(senderContains = "123", onlyFromContacts = true)
        assertTrue(AddonRules.matches(rule, event(sender = "123", fromContact = true)))
        assertFalse(AddonRules.matches(rule, event(sender = "123", fromContact = false)))
        assertFalse(AddonRules.matches(rule, event(sender = "999", fromContact = true)))
    }

    private fun ts(dayOfWeek: Int, hour: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.DAY_OF_WEEK, dayOfWeek)
        c.set(java.util.Calendar.HOUR_OF_DAY, hour)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Test
    fun daysOfWeekCondition() {
        val rule = AddonRule(daysOfWeek = "1") // Sunday
        assertTrue(AddonRules.matches(rule, event(), ts(java.util.Calendar.SUNDAY, 12)))
        assertFalse(AddonRules.matches(rule, event(), ts(java.util.Calendar.MONDAY, 12)))
    }

    @Test
    fun hourRangeNormal() {
        val rule = AddonRule(startHour = 9, endHour = 17)
        assertTrue(AddonRules.matches(rule, event(), ts(java.util.Calendar.MONDAY, 12)))
        assertFalse(AddonRules.matches(rule, event(), ts(java.util.Calendar.MONDAY, 20)))
    }

    @Test
    fun hourRangeWrap() {
        val rule = AddonRule(startHour = 22, endHour = 6)
        assertTrue(AddonRules.matches(rule, event(), ts(java.util.Calendar.MONDAY, 23)))
        assertTrue(AddonRules.matches(rule, event(), ts(java.util.Calendar.MONDAY, 3)))
        assertFalse(AddonRules.matches(rule, event(), ts(java.util.Calendar.MONDAY, 12)))
    }

    @Test
    fun timeAndContentCombined() {
        val rule = AddonRule(keywordContains = "otp", startHour = 9, endHour = 17)
        assertTrue(AddonRules.matches(rule, event(body = "otp code"), ts(java.util.Calendar.MONDAY, 12)))
        assertFalse(AddonRules.matches(rule, event(body = "otp code"), ts(java.util.Calendar.MONDAY, 20)))
        assertFalse(AddonRules.matches(rule, event(body = "hello"), ts(java.util.Calendar.MONDAY, 12)))
    }

    // --- Persian normalization (ZWNJ) ---

    @Test
    fun zwnjKeywordMatchesPlainBody() {
        val rule = AddonRule(keywordContains = "نیم\u200Cفاصله")
        assertTrue(AddonRules.matches(rule, event(body = "این پیام نیم فاصله دارد")))
        assertTrue(AddonRules.matches(rule, event(body = "نیم\u200Cفاصله")))
        // squash() also bridges fully-closed forms
        assertTrue(AddonRules.matches(rule, event(body = "نیمفاصله")))
    }

    @Test
    fun arabicYehAndKafMatchPersian() {
        assertTrue(
            AddonRules.matches(
                AddonRule(keywordContains = "تخفیف".replace('ی', 'ي')),
                event(body = "تخفیف ویژه")
            )
        )
        assertTrue(
            AddonRules.matches(
                AddonRule(keywordContains = "کد".replace('ک', 'ك')),
                event(body = "کد 42")
            )
        )
        assertTrue(
            AddonRules.matches(
                AddonRule(senderContains = "کد".replace('ک', 'ك')),
                event(sender = "کدبانو")
            )
        )
    }

    @Test
    fun persianDigitsMatchAscii() {
        assertTrue(AddonRules.matches(AddonRule(senderContains = "۱۲۳"), event(sender = "x123y")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "کد ۴۲"), event(body = "کد 42 شما")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "۴۲"), event(body = "code 42")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "۴۲"), event(body = "code 43")))
    }

    @Test
    fun latinKeywordCaseFoldedAfterNormalize() {
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "OtP"), event(body = "your OTP code")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "OtP"), event(body = "your code")))
    }

    @Test
    fun blankKeywordStillMatchesAnything() {
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "   "), event(body = "anything")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = ""), event(body = "")))
    }

    @Test
    fun spacedOutAndPhraseKeywordsMatch() {
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "کد   تخفیف"), event(body = "کد تخفیف ویژه")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "تخفیف"), event(body = "ت خ ف ی ف")))
    }

    // --- S2: keyword list, ALL/ANY, regex ---

    @Test
    fun orModeNeedsOnlyOneTerm() {
        val rule = AddonRule(keywords = listOf("otp", "رمز"), keywordMode = "OR")
        assertTrue(AddonRules.matches(rule, event(body = "your otp code")))
        assertTrue(AddonRules.matches(rule, event(body = "رمز شما 42")))
        assertFalse(AddonRules.matches(rule, event(body = "hello")))
    }

    @Test
    fun andModeNeedsEveryTerm() {
        val rule = AddonRule(keywords = listOf("otp", "bank"), keywordMode = "AND")
        assertTrue(AddonRules.matches(rule, event(body = "bank otp")))
        assertFalse(AddonRules.matches(rule, event(body = "bank only")))
        assertFalse(AddonRules.matches(rule, event(body = "otp only")))
    }

    @Test
    fun unknownModeFallsBackToAnd() {
        val rule = AddonRule(keywords = listOf("otp", "bank"), keywordMode = "XOR")
        assertTrue(AddonRules.matches(rule, event(body = "bank otp")))
        assertFalse(AddonRules.matches(rule, event(body = "bank only")))
    }

    @Test
    fun regexMatchesNormalizedBody() {
        val rule = AddonRule(keywords = listOf("^09\\d{9}"), keywordRegex = true)
        assertTrue(AddonRules.matches(rule, event(body = "۰۹۱۲۳۴۵۶۷۸۹")))
        assertFalse(AddonRules.matches(rule, event(body = "شماره ۰۹۱۲۳۴۵۶۷۸۹ چیست")))
        // body is normalized (lowercased) before matching, so case must not matter
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("^hello"), keywordRegex = true), event(body = "HELLO there")))
    }

    @Test
    fun invalidRegexDoesNotThrowAndDoesNotMatch() {
        assertFalse(AddonRules.matches(AddonRule(keywords = listOf("("), keywordRegex = true), event(body = "a(b")))
        // OR: a broken term must not poison a working one ...
        assertTrue(
            AddonRules.matches(
                AddonRule(keywords = listOf("(", "otp"), keywordMode = "OR", keywordRegex = true),
                event(body = "your otp")
            )
        )
        // ... and AND: a broken term makes the rule fail, not match
        assertFalse(
            AddonRules.matches(
                AddonRule(keywords = listOf("(", "otp"), keywordMode = "AND", keywordRegex = true),
                event(body = "your otp")
            )
        )
    }

    @Test
    fun legacyKeywordContainsStillMatches() {
        // Migration: load() needs a Context, so this pins the pure decision instead of the JSON path.
        // A rule built the pre-S2 way (keywordContains only, keywords empty) must behave exactly as before.
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "کد"), event(body = "کد ۴۲")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "کد"), event(body = "خداحافظ")))
        // load() copies legacy keywordContains into keywords; the list must win identically.
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "کد", keywords = listOf("کد")), event(body = "کد 42")))
    }

    @Test
    fun emptyKeywordListIsNoCondition() {
        assertTrue(AddonRules.matches(AddonRule(keywords = emptyList()), event(body = "")))
        assertTrue(AddonRules.matches(AddonRule(keywords = emptyList(), onlyNotSpam = true), event(isSpam = false)))
    }

    @Test
    fun singleTermMatchesLikeLegacy() {
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("OTP")), event(body = "your otp code")))
        assertFalse(AddonRules.matches(AddonRule(keywords = listOf("OTP")), event(body = "hello")))
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("تخفیف")), event(body = "ت خ ف ی ف")))
    }

    @Test
    fun termNormalizingToEmptyInListDoesNotWiden() {
        val zwnj = "\u200C"
        assertFalse(
            "AND: unusable term must fail the rule",
            AddonRules.matches(AddonRule(keywords = listOf("otp", zwnj), keywordMode = "AND"), event(body = "your otp code"))
        )
        assertTrue(
            "OR: usable term still matches",
            AddonRules.matches(AddonRule(keywords = listOf("otp", zwnj), keywordMode = "OR"), event(body = "your otp code"))
        )
        assertFalse(
            "OR: unusable term must not make everything match",
            AddonRules.matches(AddonRule(keywords = listOf("otp", zwnj), keywordMode = "OR"), event(body = "hello"))
        )
        assertFalse(
            "OR: unusable term alone never matches",
            AddonRules.matches(AddonRule(keywords = listOf(zwnj), keywordMode = "OR"), event(body = "anything"))
        )
        assertFalse(
            "regex mode: empty pattern is inert",
            AddonRules.matches(AddonRule(keywords = listOf(""), keywordMode = "OR", keywordRegex = true), event(body = "anything"))
        )
    }
    // ---------- S2: bounded regex work ----------

    @Test
    fun regexNeverSeesTextPastChar200() {
        // Intended: the subject is capped at 200 chars, so a term only present later can never match.
        assertFalse("match past char 200 must NOT match (truncation is intended)", AddonRules.matches(
            AddonRule(keywords = listOf("needle"), keywordRegex = true), event(body = "x".repeat(200) + "needle")))
        assertTrue("same term fully inside 200 chars still matches", AddonRules.matches(
            AddonRule(keywords = listOf("needle"), keywordRegex = true), event(body = "x".repeat(194) + "needle")))
    }

    @Test
    fun overLongRegexPatternIsRejected() {
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("a".repeat(120)), keywordRegex = true), event(body = "a".repeat(120))))
        assertFalse("pattern over 120 chars must be rejected", AddonRules.matches(
            AddonRule(keywords = listOf("a".repeat(121)), keywordRegex = true), event(body = "a".repeat(121))))
    }

    @Test
    fun nestedQuantifierRegexIsRejected() {
        assertFalse(AddonRules.matches(AddonRule(keywords = listOf("(a+)+$"), keywordRegex = true), event(body = "aaaa")))
        assertFalse(AddonRules.matches(AddonRule(keywords = listOf("(a*)*b"), keywordRegex = true), event(body = "aaab")))
        assertFalse(AddonRules.matches(AddonRule(keywords = listOf("(ab?){1,9}"), keywordRegex = true), event(body = "abab")))
        // flat quantifiers are still allowed
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("(ab)+"), keywordRegex = true), event(body = "abab")))
    }

    @Test
    fun pathologicalRegexCostsUnder100ms() {
        val p = "(a+)+$"
        fun cost(body: String): Long {
            repeat(5) { AddonRules.matches(AddonRule(keywords = listOf(p), keywordRegex = true), event(body = body)) }
            val t0 = System.nanoTime()
            AddonRules.matches(AddonRule(keywords = listOf(p), keywordRegex = true), event(body = body))
            return (System.nanoTime() - t0) / 1_000_000
        }
        val m1000 = cost("a".repeat(1000))
        val f1000 = cost("a".repeat(1000) + "b")
        val f5000 = cost("a".repeat(5000) + "b")
        println("S2 fixed regex cost '$p' (ms): match1000=$m1000 fail1000=$f1000 fail5000=$f5000")
        assertTrue("pathological regex took ${f1000}ms/1000-char, ${f5000}ms/5001-char, budget 100ms", f1000 < 100 && f5000 < 100 && m1000 < 100)
    }

    // ---------- S2c: the editor can tell a live regex from a dead one ----------

    @Test
    fun regexUsableMirrorsRuntimeGate() {
        // Ordinary patterns must stay usable, or the editor would block rules that work today.
        listOf("^09\\d{9}$", "ab+", "[0-9]{4}", "(?:ab)+", "https?://\\S+", "\\d{2}:\\d{2}")
            .forEach { assertTrue("'$it' must be usable", AddonRules.regexUsable(it)) }
        // The two date shapes carry 3 quantifiers: the quantifier cap refuses them, so they can never
        // match. The editor now surfaces that instead of failing silently on every SMS.
        assertFalse(AddonRules.regexUsable("\\d{2}/\\d{2}/\\d{4}"))
        assertFalse(AddonRules.regexUsable("\\d{4}-\\d{2}-\\d{2}"))
        assertFalse(AddonRules.regexUsable("(a|b)+"))
        assertFalse("invalid syntax", AddonRules.regexUsable("("))
        assertFalse("over the 120-char cap", AddonRules.regexUsable("a".repeat(121)))
        assertFalse("blank is inert, not usable", AddonRules.regexUsable("   "))
    }

    // ---------- S2b: overlapping alternation and quantifier piles ----------

    private fun regexCost(pattern: String, body: String): Long {
        val rule = AddonRule(keywords = listOf(pattern), keywordRegex = true)
        repeat(200) { AddonRules.matches(rule, event(body = body)) } // JIT warmup: a cold run costs ~10x
        var best = Long.MAX_VALUE
        repeat(5) {
            val t0 = System.nanoTime()
            AddonRules.matches(rule, event(body = body))
            best = minOf(best, System.nanoTime() - t0)
        }
        return best / 1_000_000
    }

    @Test
    fun overlappingAlternationRegexIsRejectedAndFast() {
        // 'b' must sit inside the 200-char cap: a 300-char body truncates to 200 a's and the pattern matches.
        val body = "a".repeat(199) + "b"
        val cost = regexCost("(a|aa)+$", body)
        println("S2b rejected '(a|aa)+$' cost (ms): $cost")
        assertFalse("overlapping alternation must be refused", AddonRules.matches(
            AddonRule(keywords = listOf("(a|aa)+$"), keywordRegex = true), event(body = body)))
        assertTrue("rejection must be cheap, took ${cost}ms", cost < 100)
        assertFalse(AddonRules.matches(AddonRule(keywords = listOf("(a|b)+"), keywordRegex = true), event(body = "abab")))
    }

    @Test
    fun nonCapturingGroupStaysUsable() {
        // "(?:" is group syntax, not a quantifier: this is a normal user pattern and must keep working.
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("(?:ab)+"), keywordRegex = true), event(body = "xxababxx")))
        assertFalse(AddonRules.matches(AddonRule(keywords = listOf("(?:ab)+"), keywordRegex = true), event(body = "xx")))
    }

    @Test
    fun quantifierPileIsRejected() {
        assertFalse("a*a*a*b costs seconds on 200 chars", AddonRules.matches(
            AddonRule(keywords = listOf("a*a*a*b"), keywordRegex = true), event(body = "aaab")))
        assertFalse(AddonRules.matches(AddonRule(keywords = listOf("a*a*a*a*b"), keywordRegex = true), event(body = "aaaab")))
        // two quantifiers stay allowed; ordinary patterns must not be collateral damage
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("a*a*b"), keywordRegex = true), event(body = "aaab")))
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("ab+"), keywordRegex = true), event(body = "abbc")))
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("[0-9]{4}"), keywordRegex = true), event(body = "code 1234")))
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("^09\\d{9}"), keywordRegex = true), event(body = "09123456789")))
    }

    @Test
    fun worstAllowedRegexStaysUnder100ms() {
        // Worst still buildable inside 200 chars: two bounded quantifiers, each up to 200 wide.
        val p = "a{0,200}a{0,200}b"
        val body = "a".repeat(200)
        val worst = regexCost(p, body)
        val plain = regexCost("a*a*b", body)
        println("S2b worst allowed regex cost (ms): '$p'=$worst  'a*a*b'=$plain")
        assertTrue("worst allowed construct took ${worst}ms, budget 100ms", worst < 100 && plain < 100)
    }

    // ---------- S3: every matching rule, multi-action, cooldown, inert empty rules ----------

    /** Minimal proxy Context: AddonRules only ever asks for SharedPreferences. */
    private class FakePrefs {
        val values = mutableMapOf<String, Any?>()
        var editor: SharedPreferences.Editor? = null
        val ctx: Context = run {
            editor = Proxy.newProxyInstance(
                AddonRulesTest::class.java.classLoader,
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
                AddonRulesTest::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)
            ) { _, m, a ->
                when (m.name) {
                    "getString" -> values[a[0] as String] as? String
                    "getBoolean" -> values[a[0] as String] as? Boolean ?: (a.getOrNull(1) as? Boolean ?: false)
                    "getLong" -> values[a[0] as String] as? Long ?: (a.getOrNull(1) as? Long ?: 0L)
                    "contains" -> values.containsKey(a[0] as String)
                    "edit" -> editor
                    else -> null
                }
            } as SharedPreferences
            object : ContextWrapper(null) {
                override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = sp
            }
        }
    }

    private fun rules(p: FakePrefs, vararg r: AddonRule) = AddonRules.save(p.ctx, r.toList())

    @Test
    fun everyMatchingRuleIsReturnedInPriorityOrder() {
        val p = FakePrefs()
        rules(p,
            AddonRule(id = "late", priority = 5, keywordContains = "کد"),
            AddonRule(id = "first", priority = 1, keywordContains = "کد"),
            AddonRule(id = "miss", priority = 2, keywordContains = "zzz"),
            AddonRule(id = "tie", priority = 1, senderContains = "123")
        )
        // equal priorities keep their saved order: sortedBy is stable
        assertEquals(listOf("first", "tie", "late"), AddonRules.matching(p.ctx, event(body = "کد 42"), 0L).map { it.id })
    }

    @Test
    fun zeroConditionRuleIsInertUnlessMatchAny() {
        val p = FakePrefs()
        rules(p,
            AddonRule(id = "inert", priority = 1),
            AddonRule(id = "spaces", priority = 2, keywordContains = "   "),
            AddonRule(id = "any", priority = 3, matchAny = true)
        )
        assertEquals(listOf("any"), AddonRules.matching(p.ctx, event(body = "anything"), 0L).map { it.id })
        // matches() stays the pure "are the conditions satisfied" answer, pinned by the S1/S2 suite
        assertTrue(AddonRules.matches(AddonRule(), event()))
    }

    @Test
    fun disabledRuleInTheMiddleIsSkipped() {
        val p = FakePrefs()
        rules(p,
            AddonRule(id = "1", priority = 1, keywordContains = "کد"),
            AddonRule(id = "2", priority = 2, keywordContains = "کد", enabled = false),
            AddonRule(id = "3", priority = 3, keywordContains = "کد")
        )
        assertEquals(listOf("1", "3"), AddonRules.matching(p.ctx, event(body = "کد 42"), 0L).map { it.id })
    }

    @Test
    fun cooldownBlocksSecondFireAndAllowsAfterExpiry() {
        val p = FakePrefs()
        val rule = AddonRule(id = "cd", keywordContains = "کد", cooldownSeconds = 60)
        val t0 = 1_700_000_000_000L
        assertTrue("first fire", AddonRules.allowedNow(p.ctx, rule, t0))
        AddonRules.markFired(p.ctx, rule, t0)
        assertEquals("last fire must land in prefs, not memory", t0, p.values["addon_fired_cd"])
        assertFalse("inside the window", AddonRules.allowedNow(p.ctx, rule, t0 + 59_999))
        assertTrue("window elapsed", AddonRules.allowedNow(p.ctx, rule, t0 + 60_000))
        // 0 = every time, and it must not write state
        assertTrue(AddonRules.allowedNow(p.ctx, AddonRule(id = "free", cooldownSeconds = 0), t0))
        assertFalse(p.values.containsKey("addon_fired_free"))
    }

    @Test
    fun actionsListKeepsItsOrderThroughSaveAndLoad() {
        val p = FakePrefs()
        val rule = AddonRule(id = "m", action = "mark_read", actions = listOf("mark_read", "copy_otp", "alert"))
        rules(p, rule)
        val back = AddonRules.load(p.ctx).single()
        assertEquals(listOf("mark_read", "copy_otp", "alert"), back.actions)
        assertEquals(rule, back)
    }

    @Test
    fun legacySingleActionRuleStillWorks() {
        val p = FakePrefs()
        rules(p, AddonRule(id = "l", keywordContains = "کد", action = "copy_body"))
        val back = AddonRules.load(p.ctx).single()
        assertEquals(emptyList<String>(), back.actions)
        assertEquals(listOf("l"), AddonRules.matching(p.ctx, event(body = "کد 42"), 0L).map { it.id })
    }

    @Test
    fun executeAllDryRunCoversEveryActionAndIsSafe() {
        val p = FakePrefs()
        // dryRun returns before touching the system: even an unknown action name must not throw
        AddonRules.executeAll(p.ctx, AddonRule(id = "m", actions = listOf("copy_otp", "forward", "email", "no_such_action")),
            event(body = "کد 42"), dryRun = true)
        AddonRules.executeAll(p.ctx, AddonRule(action = "copy_body"), event(), dryRun = true)
    }

    @Test
    fun allFailedActionsDoNotArmTheCooldown() {
        val p = FakePrefs()
        // ContextWrapper(null): every service lookup throws, so no action of this rule can succeed
        val rule = AddonRule(id = "boom", keywordContains = "کد", action = "copy_otp", cooldownSeconds = 60)
        rules(p, rule)
        assertEquals(0, AddonRules.executeAll(p.ctx, rule, event(body = "کد 42", otp = "1234")))
        AddonBus.dispatch(p.ctx, event(body = "کد 42", otp = "1234"))
        assertFalse("a failed fire must not start the cooldown", p.values.containsKey("addon_fired_boom"))
    }

    // ---------- S4: success means the action ran, addon notifications get their own id ----------

    /** FakePrefs plus a JVM-built ClipboardManager: the clipboard actions can actually finish here. */
    private inner class FakeClipPrefs {
        val prefs = FakePrefs()
        val ctx: Context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int) =
                prefs.ctx.getSharedPreferences(name, mode)

            override fun getSystemService(name: String): Any? =
                if (name == Context.CLIPBOARD_SERVICE) clipboard() else null
        }
    }

    // shortcut: android.jar only has the stub ClipboardManager() constructor, so reflection is the
    // way to hand execute() a real instance on the JVM; upgrade to Robolectric when a fake is needed.
    private fun clipboard(): ClipboardManager =
        ClipboardManager::class.java.getDeclaredConstructor()
            .apply { isAccessible = true }.newInstance() as ClipboardManager

    @Test
    fun unknownActionIsNotSuccess() {
        val p = FakeClipPrefs()
        assertFalse(AddonRules.execute(p.ctx, AddonRule(action = "not_a_real_action"), event()))
    }

    @Test
    fun copyBodyOnSpamIsNotSuccess() {
        val p = FakeClipPrefs()
        assertFalse(AddonRules.execute(p.ctx, AddonRule(action = "copy_body"), event(isSpam = true)))
    }

    @Test
    fun copyOtpWithoutACodeIsNotSuccess() {
        val p = FakeClipPrefs()
        assertFalse(AddonRules.execute(p.ctx, AddonRule(action = "copy_otp"), event(otp = null)))
    }

    @Test
    fun copyBodyOnANormalSmsIsSuccess() {
        val p = FakeClipPrefs()
        assertTrue(AddonRules.execute(p.ctx, AddonRule(action = "copy_body"), event(body = "hello")))
    }

    @Test
    fun cooldownArmsOnlyForAnActionThatRan() {
        val p = FakeClipPrefs()
        val noop = AddonRule(id = "noop", keywordContains = "کد", cooldownSeconds = 60,
            actions = listOf("copy_body", "not_a_real_action"))
        val real = AddonRule(id = "real", keywordContains = "کد", cooldownSeconds = 60, actions = listOf("copy_body"))
        AddonRules.save(p.ctx, listOf(noop, real))
        AddonBus.dispatch(p.ctx, event(body = "کد 42", isSpam = true))
        assertFalse("copy_body on spam did nothing", p.prefs.values.containsKey("addon_fired_noop"))
        AddonBus.dispatch(p.ctx, event(body = "کد 42"))
        assertTrue("a copied body is a real fire", p.prefs.values.containsKey("addon_fired_real"))
    }

    @Test
    fun notifyIdZeroSuppressesDismissAndModifyText() {
        val p = FakeClipPrefs()
        val e = SmsEvent("123", "hello", 0L, 0, 7L, false, false, null, 0)
        assertFalse(AddonRules.execute(p.ctx, AddonRule(action = "dismiss"), e))
        assertFalse(AddonRules.execute(p.ctx, AddonRule(action = "modify_text", actionParam = "x"), e))
        assertEquals(0, AddonRules.executeAll(p.ctx, AddonRule(actions = listOf("dismiss", "modify_text")), e))
        // and a rule made only of those must not arm its cooldown for an SMS that has no notification id
        val r = AddonRule(id = "dis", keywordContains = "کد", cooldownSeconds = 60, actions = listOf("dismiss"))
        AddonRules.save(p.ctx, listOf(r))
        AddonBus.dispatch(p.ctx, event(body = "کد 42"))
        assertFalse("a no-op dismiss must not arm", p.prefs.values.containsKey("addon_fired_dis"))
    }

    @Test
    fun guardedOutActionsAreNotSuccess() {
        val p = FakeClipPrefs()
        p.prefs.values["addon_autoreply_text"] = ""   // blank template -> nothing to send
        val e = SmsEvent(" ", "hello", 0L, 0, 0L, false, true, null, 0) // blank sender, threadId 0
        assertFalse("blank forward target", AddonRules.execute(p.ctx, AddonRule(action = "forward"), e))
        assertFalse("blank sender", AddonRules.execute(p.ctx, AddonRule(action = "block_sender"), e))
        for (a in listOf("mark_read", "archive", "pin", "mute"))
            assertFalse("$a without a thread", AddonRules.execute(p.ctx, AddonRule(action = a), e))
        assertFalse("blank template", AddonRules.execute(p.ctx, AddonRule(action = "autoreply"), e.copy(sender = "123")))
    }

    @Test
    fun alertWithoutANotificationManagerIsNotSuccess() {
        val p = FakeClipPrefs()   // getSystemService only answers the clipboard
        assertFalse(AddonRules.execute(p.ctx, AddonRule(action = "alert", actionParam = "x"), event()))
    }

    @Test
    fun alertNotificationIdIsNotTheSmsNotificationId() {
        val e = SmsEvent("123", "hello", 0L, 0, 7L, false, false, null, 2007)
        val addon = AddonRules.addonNotifyId(e)
        assertNotEquals("an alert must not replace the SMS notification", e.notifyId, addon)
        assertEquals("the same conversation reuses its addon id", addon, AddonRules.addonNotifyId(e))
        assertNotEquals(addon, AddonRules.addonNotifyId(e.copy(threadId = 8L)))
        assertNotEquals("no thread falls back to the sender", addon, AddonRules.addonNotifyId(e.copy(threadId = 0L)))
        // two senders without a thread must not share one addon notification
        assertNotEquals(
            "sender fallback must not collide",
            AddonRules.addonNotifyId(e.copy(threadId = 0L, sender = "123")),
            AddonRules.addonNotifyId(e.copy(threadId = 0L, sender = "456"))
        )
    }
}
