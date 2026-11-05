package ir.smsfilter.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
    // ---------- S7: "-term" negation ----------

    @Test
    fun negativeTermExcludesAdButKeepsTheOtp() {
        val rule = AddonRule(keywords = listOf("کد", "-لغو"), keywordMode = "AND")
        assertTrue("bank OTP body still matches", AddonRules.matches(rule, event(body = "کد تایید شما: 12345")))
        assertFalse("ad body with لغو11 must not match", AddonRules.matches(rule, event(body = "لغو11 تخفیف ویژه")))
    }

    @Test
    fun onlyNegativeTermsMatchACleanBody() {
        val rule = AddonRule(keywords = listOf("-لغو"))
        assertTrue(AddonRules.matches(rule, event(body = "کد تایید شما: 12345")))
        assertFalse(AddonRules.matches(rule, event(body = "برای لغو عدد 11 را بفرستید")))
    }

    @Test
    fun andModeWithTwoNegativesFailsOnEitherOne() {
        val rule = AddonRule(keywords = listOf("کد", "-لغو", "-تخفیف"), keywordMode = "AND")
        assertTrue(AddonRules.matches(rule, event(body = "کد تایید شما: 12345")))
        assertFalse(AddonRules.matches(rule, event(body = "کد تایید، لغو 11")))
        assertFalse(AddonRules.matches(rule, event(body = "کد تایید، تخفیف ویژه")))
        assertFalse("positive missing still fails", AddonRules.matches(rule, event(body = "سلام")))
    }

    @Test
    fun orModeStillExcludesEveryNegative() {
        val rule = AddonRule(keywords = listOf("کد", "رمز", "-لغو"), keywordMode = "OR")
        assertTrue(AddonRules.matches(rule, event(body = "رمز یکبار مصرف")))
        assertFalse("a present negative kills the OR hit too", AddonRules.matches(rule, event(body = "رمز، لغو 11")))
        assertFalse("no positive at all", AddonRules.matches(rule, event(body = "سلام")))
    }

    @Test
    fun negativeSeesPersianVariantsAndSpacedEvasion() {
        val rule = AddonRule(keywords = listOf("کد", "-لغو"))
        assertFalse("ZWNJ form", AddonRules.matches(rule, event(body = "کد تایید، لغو\u200C11")))
        assertFalse("spaced-out form", AddonRules.matches(rule, event(body = "کد تایید، ل غ و 11")))
        assertFalse("Arabic kaf variant", AddonRules.matches(rule, event(body = "كد تاييد، لغو")))
    }

    @Test
    fun negativeRegexMeansPatternMustBeAbsent() {
        val rule = AddonRule(keywords = listOf("-^09\\d{9}$"), keywordRegex = true)
        assertTrue("clean body matches", AddonRules.matches(rule, event(body = "hello")))
        assertFalse("body that is exactly a mobile number is excluded", AddonRules.matches(rule, event(body = "09123456789")))
        assertTrue(AddonRules.regexUsable(AddonRules.keywordTerm("-^09\\d{9}$").second))
    }

    @Test
    fun escapedHyphenIsALiteralPositiveTerm() {
        val rule = AddonRule(keywords = listOf("\\-abc"))
        assertTrue(AddonRules.matches(rule, event(body = "promo -abc here")))
        assertFalse("literal hyphen required", AddonRules.matches(rule, event(body = "promo abc here")))
        assertFalse(AddonRules.keywordTerm("\\-abc").first)
    }

    @Test
    fun unusableNegativeCannotTurnTheRuleIntoMatchAll() {
        // A negative that can never hit would be vacuously satisfied; the rule must go dead instead.
        assertFalse("refused regex negative", AddonRules.matches(
            AddonRule(keywords = listOf("-("), keywordRegex = true), event(body = "anything")))
        assertFalse("ZWNJ-only negative", AddonRules.matches(
            AddonRule(keywords = listOf("-\u200C")), event(body = "anything")))
        assertFalse("refused negative next to a good positive", AddonRules.matches(
            AddonRule(keywords = listOf("کد", "-(("), keywordRegex = true), event(body = "کد 42")))
        // A bare '-' next to a usable term is simply dropped, so the rule behaves like the positive alone.
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("کد", "-")), event(body = "کد 42")))
    }

    @Test
    fun bareHyphenIsNoTermAndDoesNotWiden() {
        assertFalse("only '-' leaves no condition", AddonRules.matches(AddonRule(keywords = listOf("-")), event(body = "anything")))
        assertFalse("'--' is unusable too", AddonRules.matches(AddonRule(keywords = listOf("--")), event(body = "anything")))
        val withSender = AddonRule(senderContains = "bank", keywords = listOf("-"))
        assertFalse("a bare '-' is never a free pass, even with other conditions", AddonRules.matches(withSender, event(sender = "bank", body = "anything")))
        assertFalse(AddonRules.matches(withSender, event(sender = "999", body = "anything")))
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

    // ---------- S5: decision trace, dry-run reason, cooldown clamp, honest send ----------

    @Test
    fun decisionTraceCapsAtTwentyNewestFirst() {
        val p = FakePrefs()
        for (i in 0 until 25) AddonRules.recordDecision(p.ctx, "d$i")
        val lines = AddonRules.recentDecisions(p.ctx)
        assertEquals(AddonRules.DEC_MAX, lines.size)
        assertEquals("newest first", "d24", lines.first())
        assertEquals("oldest kept is the 20th from the end", "d5", lines.last())
    }

    @Test
    fun dispatchRecordsOneLinePerSmsWithTheRuleThatRan() {
        val p = FakeClipPrefs()
        AddonRules.save(p.ctx, listOf(AddonRule(id = "r1", name = "Copy", keywordContains = "کد", action = "copy_body", cooldownSeconds = 60)))
        AddonBus.dispatch(p.ctx, event(body = "کد 42"))
        val line = AddonRules.recentDecisions(p.ctx).single()
        assertTrue("names the rule: $line", line.contains("Copy"))
        assertTrue("names the action that ran: $line", line.contains("copy_body"))
        assertEquals("one SMS is one entry", 1, AddonRules.recentDecisions(p.ctx).size)
    }

    @Test
    fun dispatchRecordsTheConditionThatMissed() {
        val p = FakePrefs()
        AddonRules.save(p.ctx, listOf(AddonRule(id = "r1", name = "Kod", keywordContains = "کد")))
        AddonBus.dispatch(p.ctx, event(body = "hello"))
        val line = AddonRules.recentDecisions(p.ctx).single()
        assertTrue("says why: $line", line.contains("keyword"))
        assertEquals("keyword", AddonRules.missReason(AddonRule(keywordContains = "کد"), event(body = "hello")))
        assertEquals("sender", AddonRules.missReason(
            AddonRule(senderContains = "999", minLength = 1), event(sender = "123", body = "hello")))
        assertEquals("no conditions", AddonRules.missReason(AddonRule(), event()))
        assertNull(AddonRules.missReason(AddonRule(keywordContains = "کد"), event(body = "کد 42")))
        assertNull(AddonRules.missReason(AddonRule(matchAny = true), event()))
    }

    @Test
    fun cooldownBlockedAttemptIsRecordedAsCooldown() {
        val p = FakeClipPrefs()
        AddonRules.save(p.ctx, listOf(AddonRule(id = "cd", name = "Cool", keywordContains = "کد", action = "copy_body", cooldownSeconds = 60)))
        AddonBus.dispatch(p.ctx, event(body = "کد 1"))
        AddonBus.dispatch(p.ctx, event(body = "کد 2"))
        val lines = AddonRules.recentDecisions(p.ctx)
        assertEquals("two SMS, two lines", 2, lines.size)
        assertTrue("second SMS was blocked: ${lines.first()}", lines.first().contains("cooldown"))
        assertFalse("first SMS really ran: ${lines.last()}", lines.last().contains("cooldown"))
    }

    @Test
    fun clearDecisionsEmptiesTheTrace() {
        val p = FakePrefs()
        AddonRules.recordDecision(p.ctx, "x")
        AddonRules.clearDecisions(p.ctx)
        assertEquals(emptyList<String>(), AddonRules.recentDecisions(p.ctx))
    }

    @Test
    fun cooldownInputClampsInsteadOfOverflowingToZero() {
        assertEquals("11 digits used to become 0 = every time", 86_400, AddonRules.clampCooldown("99999999999"))
        assertEquals(AddonRules.MAX_COOLDOWN, AddonRules.clampCooldown("86400"))
        assertEquals(86_400, AddonRules.clampCooldown("999999999999999999999999"))
        assertEquals(30, AddonRules.clampCooldown("30"))
        assertEquals("non-digits never reach the model", 0, AddonRules.clampCooldown("abc"))
        assertEquals(0, AddonRules.clampCooldown(""))
    }

    @Test
    fun sendThatFailsIsNotSuccess() {
        val p = FakeClipPrefs()
        val e = event(sender = "123", fromContact = true, body = "hello")
        val real = AddonRules.send
        try {
            AddonRules.send = { _, _, _, _ -> false }
            assertFalse("autoreply must report the failed send",
                AddonRules.execute(p.ctx, AddonRule(action = "autoreply", actionParam = "hi"), e))
            assertFalse("forward must report the failed send",
                AddonRules.execute(p.ctx, AddonRule(action = "forward", actionParam = "0912"), e))
            AddonRules.send = { _, _, _, _ -> true }
            assertTrue(AddonRules.execute(p.ctx, AddonRule(action = "autoreply", actionParam = "hi"), e))
            assertTrue(AddonRules.execute(p.ctx, AddonRule(action = "forward", actionParam = "0912"), e))
        } finally {
            AddonRules.send = real
        }
    }

    // ---------- S8: whole-word keywords, Persian-aware boundaries ----------

    private fun wordRule(vararg terms: String) =
        AddonRule(keywords = terms.toList(), keywordWholeWord = true)

    @Test
    fun wholeWordRejectsWordInteriorMatches() {
        val rule = wordRule("وام")
        assertTrue(AddonRules.matches(rule, event(body = "وام ۵۰۰ میلیون")))
        assertFalse("prefix of a longer word", AddonRules.matches(rule, event(body = "دوام")))
        assertFalse("suffix of a longer word", AddonRules.matches(rule, event(body = "وامدار")))
        // The naive (\\W|^)kw(\\W|$) wrap fails exactly here: Java's \\W treats Persian letters as
        // non-word, so it would report a boundary inside رمزنگاری.
        assertFalse("ASCII \\W would have matched inside رمزنگاری",
            AddonRules.matches(wordRule("رمز"), event(body = "رمزنگاری")))
    }

    @Test
    fun wholeWordOffKeepsLegacySubstringMatching() {
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("وام")), event(body = "دوام")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "وام"), event(body = "دوام")))
    }

    @Test
    fun wholeWordPunctuationEdgesAndEmptyBody() {
        val rule = wordRule("وام")
        listOf("(وام)", "وام.", "وام،", "وام", "وام ۵۰۰", "وام\n۵۰۰").forEach {
            assertTrue("must match <$it>", AddonRules.matches(rule, event(body = it)))
        }
        assertFalse("empty body has no boundaries to match", AddonRules.matches(rule, event(body = "")))
        assertFalse(AddonRules.matches(rule, event(body = "وامدار ۵۰۰")))
    }

    @Test
    fun wholeWordFoldsVariantsBeforeTheBoundaryTest() {
        assertTrue("Arabic kaf folds", AddonRules.matches(wordRule("كارت"), event(body = "کارت به کارت")))
        assertTrue("Arabic yeh folds", AddonRules.matches(wordRule("ايران"), event(body = "ایران خودرو")))
        assertTrue("Persian digits fold", AddonRules.matches(wordRule("وام ۵۰۰"), event(body = "وام 500 میلیون")))
        // Digits are word characters: an attached number is not a boundary (a probe worth arguing about).
        assertFalse(AddonRules.matches(wordRule("وام"), event(body = "وام500 میلیون")))
    }

    @Test
    fun wholeWordZwnjAndSpacedVariants() {
        // normalize() rewrites ZWNJ to a space on both sides, so the two spellings are one term here.
        assertTrue("ZWNJ keyword vs spaced body",
            AddonRules.matches(wordRule("نیم\u200Cفاصله"), event(body = "نیم فاصله")))
        assertTrue("spaced keyword vs ZWNJ body",
            AddonRules.matches(wordRule("نیم فاصله"), event(body = "نیم\u200Cفاصله")))
        // Same rewrite, unwanted side: a ZWNJ compound reads as two words and matches.
        assertTrue("known hole: ZWNJ compound reads as two words",
            AddonRules.matches(wordRule("وام"), event(body = "وام\u200Cدار")))
    }

    @Test
    fun wholeWordNegativeOnlyExcludesWholeWords() {
        val rule = AddonRule(keywords = listOf("کد", "-وام"), keywordWholeWord = true)
        assertTrue("وام inside دوام is not the excluded word", AddonRules.matches(rule, event(body = "کد دوام")))
        assertTrue("وامدار is a different word", AddonRules.matches(rule, event(body = "کد وامدار")))
        assertFalse("a standalone وام is excluded", AddonRules.matches(rule, event(body = "کد وام")))
    }

    @Test
    fun regexModeIgnoresTheWholeWordFlag() {
        val rule = AddonRule(keywords = listOf("وام"), keywordRegex = true, keywordWholeWord = true)
        assertTrue("the pattern owns its boundaries; nothing is wrapped around it",
            AddonRules.matches(rule, event(body = "دوام")))
    }

    @Test
    fun wholeWordFlagSurvivesSaveAndLoad() {
        val p = FakePrefs()
        AddonRules.save(p.ctx, listOf(AddonRule(name = "w", keywords = listOf("وام"), keywordWholeWord = true)))
        assertTrue("whole-word must persist", AddonRules.load(p.ctx).single().keywordWholeWord)
        assertFalse("default stays off", AddonRule().keywordWholeWord)
    }

    @Test
    fun twentyWholeWordRulesOn200CharsStayCheap() {
        val words = listOf("وام", "کد", "بانک", "مبلغ", "تخفیف", "کارت", "حساب", "شماره", "پرداخت", "برداشت",
            "موجودی", "قسط", "سود", "اعتبار", "خرید", "فروش", "تایید", "لغو", "رمز", "قسط2")
        val rules = words.map { AddonRule(keywords = listOf(it), keywordWholeWord = true) }
        val body = ("وام 500 میلیون تومان؛ " + "لورم ایپسوم متن ساختگی برای سنجش ".repeat(10)).take(200)
        fun pass(): Long {
            val t0 = System.nanoTime()
            rules.forEach { AddonRules.matches(it, event(body = body)) }
            return System.nanoTime() - t0
        }
        repeat(50) { pass() } // JIT warmup: a cold run costs ~10x
        val best = (1..5).minOf { pass() } / 1_000_000
        println("S8 whole-word cost: 20 rules x ${body.length}-char body = ${best}ms (budget 100ms)")
        assertTrue("20 whole-word rules took ${best}ms, budget 100ms", best < 100)
    }

    // ---------- S9: nested condition tree ----------

    private fun treeRule(c: Cond) = AddonRule(conditions = c)

    private fun Tree(vararg items: Cond) = Cond.Group("AND", items = items.toList())

    @Test
    fun nestedOrAndNotGroup() {
        // (otp OR رمز) AND NOT لغو
        val rule = treeRule(
            Tree(
                Cond.Group("OR", items = listOf(Cond.Keyword("otp"), Cond.Keyword("رمز"))),
                Cond.Group("AND", not = true, items = listOf(Cond.Keyword("لغو")))
            )
        )
        assertTrue(AddonRules.matches(rule, event(body = "your otp code")))
        assertTrue(AddonRules.matches(rule, event(body = "رمز شما")))
        assertFalse("neither branch", AddonRules.matches(rule, event(body = "سلام")))
        assertFalse("the NOT group excludes", AddonRules.matches(rule, event(body = "رمز شما، لغو 11")))
    }

    @Test
    fun threeLevelsOfGroups() {
        // (bank AND (otp OR کد)) OR واریز
        val rule = treeRule(
            Cond.Group(
                "OR", items = listOf(
                    Cond.Group(
                        "AND", items = listOf(
                            Cond.Sender("bank"),
                            Cond.Group("OR", items = listOf(Cond.Keyword("otp"), Cond.Keyword("کد")))
                        )
                    ),
                    Cond.Keyword("واریز")
                )
            )
        )
        assertTrue(AddonRules.matches(rule, event(sender = "bank", body = "کد 42")))
        assertTrue(AddonRules.matches(rule, event(sender = "999", body = "واریز شد")))
        assertFalse("right sender, no keyword", AddonRules.matches(rule, event(sender = "bank", body = "سلام")))
        assertFalse("keyword on the wrong sender", AddonRules.matches(rule, event(sender = "999", body = "کد 42")))
    }

    @Test
    fun notOnAGroupAndNotOnALeafAgree() {
        val leafNot = treeRule(Tree(Cond.Keyword("کد"), Cond.Keyword("لغو", not = true)))
        val groupNot = treeRule(
            Tree(Cond.Keyword("کد"), Cond.Group("AND", not = true, items = listOf(Cond.Keyword("لغو"))))
        )
        val bodies = listOf("کد 42", "کد، لغو 11", "لغو 11", "سلام")
        bodies.forEach { b ->
            assertEquals("body=$b", AddonRules.matches(leafNot, event(body = b)), AddonRules.matches(groupNot, event(body = b)))
        }
        assertTrue(AddonRules.matches(leafNot, event(body = "کد 42")))
        assertFalse(AddonRules.matches(leafNot, event(body = "کد، لغو 11")))
    }

    @Test
    fun treeLeavesEvaluate() {
        val base = event(body = "hi", fromContact = true, otp = "1234")
        assertTrue(AddonRules.matches(treeRule(Cond.Contact()), base))
        assertFalse(AddonRules.matches(treeRule(Cond.Contact()), base.copy(fromContact = false)))
        assertTrue(AddonRules.matches(treeRule(Cond.Contact(not = true)), base.copy(fromContact = false)))
        assertTrue(AddonRules.matches(treeRule(Cond.Spam(false)), base))
        assertFalse(AddonRules.matches(treeRule(Cond.Spam(false)), base.copy(isSpam = true)))
        assertTrue(AddonRules.matches(treeRule(Cond.Otp(true)), base))
        assertFalse(AddonRules.matches(treeRule(Cond.Otp(true)), base.copy(otp = null)))
        assertTrue(AddonRules.matches(treeRule(Cond.Emoji(true)), base.copy(body = "🔥 hello")))
        assertFalse(AddonRules.matches(treeRule(Cond.Emoji(true)), base))
        assertTrue(AddonRules.matches(treeRule(Cond.Emoji(false)), base))
        assertTrue(AddonRules.matches(treeRule(Cond.Link(true)), base.copy(body = "see https://x.y")))
        assertFalse(AddonRules.matches(treeRule(Cond.Link(true)), base))
        assertTrue(AddonRules.matches(treeRule(Cond.Length(min = 2, max = 2)), base))
        assertFalse(AddonRules.matches(treeRule(Cond.Length(min = 3)), base))
        assertTrue(AddonRules.matches(treeRule(Cond.Time(days = setOf(java.util.Calendar.MONDAY), startHour = 9, endHour = 17)),
            event(), ts(java.util.Calendar.MONDAY, 12)))
        assertFalse(AddonRules.matches(treeRule(Cond.Time(days = setOf(java.util.Calendar.MONDAY), startHour = 9, endHour = 17)),
            event(), ts(java.util.Calendar.MONDAY, 20)))
        assertTrue("overnight window wraps", AddonRules.matches(treeRule(Cond.Time(startHour = 22, endHour = 6)),
            event(), ts(java.util.Calendar.MONDAY, 3)))
    }

    @Test
    fun keywordScopeAndMatchModes() {
        val onSender = treeRule(Tree(Cond.Keyword("bank", scope = "sender")))
        assertTrue(AddonRules.matches(onSender, event(sender = "bank sms", body = "hello")))
        assertFalse("body is not the sender scope", AddonRules.matches(onSender, event(sender = "123", body = "bank otp")))
        val any = treeRule(Tree(Cond.Keyword("bank", scope = "any")))
        assertTrue(AddonRules.matches(any, event(sender = "bank", body = "hello")))
        assertTrue(AddonRules.matches(any, event(sender = "123", body = "bank otp")))
        assertFalse(AddonRules.matches(treeRule(Tree(Cond.Keyword("otp", match = "exact"))), event(body = "your otp")))
        assertTrue(AddonRules.matches(treeRule(Tree(Cond.Keyword("otp", match = "exact"))), event(body = "otp")))
        assertTrue(AddonRules.matches(treeRule(Tree(Cond.Sender("bank", "exact"))), event(sender = "bank")))
        assertFalse(AddonRules.matches(treeRule(Tree(Cond.Sender("bank", "exact"))), event(sender = "bank2")))
    }

    @Test
    fun treeKeepsTheNoWideningInvariants() {
        val zwnj = "\u200C"
        assertFalse(AddonRules.matches(treeRule(Cond.Keyword(zwnj)), event(body = "anything")))
        assertFalse("NOT must not turn an unusable term into a free pass",
            AddonRules.matches(treeRule(Cond.Keyword(zwnj, not = true)), event(body = "anything")))
        assertFalse(AddonRules.matches(treeRule(Cond.Keyword("(", match = "regex")), event(body = "a(b")))
        assertFalse(AddonRules.matches(treeRule(Cond.Keyword("(", match = "regex", not = true)), event(body = "a(b")))
        assertFalse("pattern over the 120-char cap", AddonRules.matches(
            treeRule(Cond.Keyword("a".repeat(121), match = "regex")), event(body = "a".repeat(121))))
        assertTrue(AddonRules.matches(treeRule(Cond.Keyword("a".repeat(120), match = "regex")), event(body = "a".repeat(120))))
        assertFalse("unusable sender term", AddonRules.matches(treeRule(Cond.Sender(zwnj)), event(sender = "bank")))
        // whole-word uses isLetterOrDigit boundaries, not Java's ASCII \W
        assertFalse(AddonRules.matches(treeRule(Cond.Keyword("رمز", match = "word")), event(body = "رمزنگاری")))
        assertTrue(AddonRules.matches(treeRule(Cond.Keyword("رمز", match = "word")), event(body = "رمز شما")))
        assertFalse("an unusable OR branch cannot widen the group", AddonRules.matches(
            treeRule(Cond.Group("OR", items = listOf(Cond.Keyword(zwnj), Cond.Keyword("otp")))), event(body = "hello")))
    }

    @Test
    fun legacyFlatRulesMatchThroughTheMigratedTree() {
        val cases = listOf(
            AddonRule(senderContains = "bank", keywords = listOf("کد", "رمز", "-لغو"), keywordMode = "OR"),
            AddonRule(keywords = listOf("وام"), keywordWholeWord = true, minLength = 5, maxLength = 40),
            AddonRule(daysOfWeek = "2", startHour = 9, endHour = 17, onlyNotSpam = true, otpRequired = false)
        )
        val events = listOf(
            event(sender = "bank", body = "کد تایید 1234", otp = "1234"),
            event(sender = "bank", body = "رمز، لغو 11"),
            event(body = "وام 500 میلیون"),
            event(body = "دوام"),
            event(body = "x", isSpam = true),
            event(body = "سلام", otp = null)
        )
        val times = listOf(ts(java.util.Calendar.MONDAY, 12), ts(java.util.Calendar.MONDAY, 20))
        cases.forEach { flat ->
            val migrated = AddonRule(conditions = AddonRules.toTree(flat))
            events.forEach { e ->
                times.forEach { t ->
                    assertEquals("flat=$flat body=${e.body}", AddonRules.matches(flat, e, t), AddonRules.matches(migrated, e, t))
                }
            }
        }
    }

    @Test
    fun conditionsAndActionListRoundTrip() {
        val p = FakePrefs()
        val tree = Tree(
            Cond.Sender("bank"),
            Cond.Group("OR", items = listOf(Cond.Keyword("کد"), Cond.Keyword("رمز", match = "word", scope = "any")))
        )
        val acts = listOf(
            Act("forward", mapOf("number" to "0912")),
            Act("open_app", mapOf("package" to "com.telegram.messenger")),
            Act("reply_blank")
        )
        AddonRules.save(p.ctx, listOf(AddonRule(id = "t", conditions = tree, actionList = acts)))
        val back = AddonRules.load(p.ctx).single()
        assertEquals(tree, back.conditions)
        assertEquals(acts, back.actionList)
        assertEquals(listOf("forward", "open_app", "reply_blank"), AddonRules.actsOf(back).map { it.type })
        assertTrue("the tree is what matches, not the projected flat fields", AddonRules.matches(back, event(sender = "bank", body = "رمز شما")))
    }

    // ---------- S10: tree caps ----------

    @Test
    fun treeCapsMakeTheRuleUnusableNeverMatchAll() {
        val wide = Cond.Group("AND", items = (1..100).map { Cond.Keyword("k$it") })
        assertNotNull(AddonRules.treeProblem(wide))
        assertFalse(AddonRules.matches(treeRule(wide), event(body = "k1")))
        var deep: Cond = Cond.Keyword("otp")
        repeat(8) { deep = Cond.Group("AND", items = listOf(deep)) }
        assertNotNull(AddonRules.treeProblem(deep))
        assertFalse(AddonRules.matches(treeRule(deep), event(body = "otp")))
        // just under the ceiling still works
        val ok = Cond.Group("AND", items = (1..60).map { Cond.Keyword("otp") })
        assertNull(AddonRules.treeProblem(ok))
        assertTrue(AddonRules.matches(treeRule(ok), event(body = "your otp")))
    }

    @Test
    fun hundredNodeImportedJsonIsUnusable() {
        val items = (1..100).joinToString(",") { """{"type":"keyword","text":"k$it"}""" }
        val p = FakePrefs()
        p.values["addon_rules_json"] = """[{"id":"big","conditions":{"type":"group","op":"AND","items":[$items]}}]"""
        val r = AddonRules.load(p.ctx).single()
        assertNotNull("the imported tree must be parsed, then flagged", r.conditions)
        assertFalse(AddonRules.matches(r, event(body = "k1")))
        assertTrue(AddonRules.matching(p.ctx, event(body = "k1"), 0L).isEmpty())
    }

    @Test
    fun unreadableConditionsJsonIsDeadNotMatchAll() {
        val p = FakePrefs()
        p.values["addon_rules_json"] = """[{"id":"bad","conditions":{"type":"no_such_node","text":"x"}}]"""
        val r = AddonRules.load(p.ctx).single()
        assertFalse(AddonRules.matches(r, event(body = "anything")))
        assertTrue(AddonRules.matching(p.ctx, event(body = "anything"), 0L).isEmpty())
    }

    // ---------- S11: per-action params, open_app / call / share ----------

    private fun withLaunch(fake: (Context, Intent) -> Boolean, block: () -> Unit) {
        val real = AddonRules.launch
        try {
            AddonRules.launch = fake
            block()
        } finally {
            AddonRules.launch = real
        }
    }

    @Test
    fun launchesReportFalseWhenTheTargetIsMissing() {
        val p = FakeClipPrefs()
        withLaunch({ _, _ -> false }) {
            assertFalse(AddonRules.executeAction(p.ctx, Act("open_app", mapOf("package" to "com.nope")), event()))
            assertFalse(AddonRules.executeAction(p.ctx, Act("call", mapOf("number" to "0912")), event()))
            assertFalse("chooser with no share app", AddonRules.executeAction(p.ctx, Act("share"), event()))
            assertFalse("blank package is guarded before launch", AddonRules.executeAction(p.ctx, Act("open_app"), event()))
            assertFalse("no number and no sender", AddonRules.executeAction(p.ctx, Act("call"), event(sender = " ")))
        }
    }

    @Test
    fun launchesReportTrueWhenTheyResolve() {
        // shortcut: android.jar's Intent is a stub, so action/extras are unreadable on the JVM; what is
        // verified here is the success path and that exactly one intent per action reaches the seam.
        // Upgrade to Robolectric when the payload itself must be asserted.
        val p = FakeClipPrefs()
        val seen = mutableListOf<Intent>()
        withLaunch({ _, i -> seen += i; true }) {
            assertTrue(AddonRules.executeAction(p.ctx, Act("open_app", mapOf("package" to "com.telegram.messenger")), event(body = "hello")))
            assertTrue("empty number falls back to the sender", AddonRules.executeAction(p.ctx, Act("call"), event(sender = "09121234567")))
            assertTrue(AddonRules.executeAction(p.ctx, Act("call", mapOf("number" to "021")), event(sender = "0912")))
            assertTrue(AddonRules.executeAction(p.ctx, Act("share", mapOf("package" to "com.whatsapp")), event(body = "body")))
            assertEquals(4, seen.size)
        }
    }

    @Test
    fun twoForwardsUseTheirOwnNumbers() {
        val p = FakeClipPrefs()
        val sent = mutableListOf<Pair<String, String>>()
        val real = AddonRules.send
        try {
            AddonRules.send = { _, to, text, _ -> sent += to to text; true }
            val rule = AddonRule(
                id = "f",
                actionList = listOf(
                    Act("forward", mapOf("number" to "111")),
                    Act("forward", mapOf("number" to "222")),
                    Act("autoreply", mapOf("text" to "hi"))
                )
            )
            assertEquals(3, AddonRules.executeAll(p.ctx, rule, event(sender = "123", fromContact = true, body = "hello")))
            assertEquals(listOf("111", "222", "123"), sent.map { it.first })
            assertEquals(listOf("hello", "hello", "hi"), sent.map { it.second })
        } finally {
            AddonRules.send = real
        }
    }

    @Test
    fun replyBlankSendsEmptySmsToTheSenderOnItsOwnSim() {
        val p = FakeClipPrefs()
        val calls = mutableListOf<Triple<String, String, Int>>()
        val real = AddonRules.send
        try {
            AddonRules.send = { _, to, text, sub -> calls += Triple(to, text, sub); true }
            val e = event(sender = " 0912 ", body = "لغو11").copy(subId = 3)
            assertTrue(AddonRules.execute(p.ctx, AddonRule(action = "reply_blank"), e))
            assertEquals("exactly one SMS", 1, calls.size)
            assertEquals("0912", calls.single().first)
            assertTrue("empty body, or the single-space fallback", calls.single().second.isBlank())
            assertEquals("sent on the receiving subscription", 3, calls.single().third)
        } finally {
            AddonRules.send = real
        }
    }

    @Test
    fun replyBlankWithNoSenderDoesNotSend() {
        val p = FakeClipPrefs()
        var calls = 0
        val real = AddonRules.send
        try {
            AddonRules.send = { _, _, _, _ -> calls++; true }
            assertFalse(AddonRules.execute(p.ctx, AddonRule(action = "reply_blank"), event(sender = "   ")))
            assertEquals(0, calls)
        } finally {
            AddonRules.send = real
        }
    }

    @Test
    fun throwingReplyBlankIsFalseAndDoesNotArmTheCooldown() {
        val p = FakePrefs()
        val rule = AddonRule(id = "rb", keywordContains = "لغو11", action = "reply_blank", cooldownSeconds = 60)
        rules(p, rule)
        val real = AddonRules.send
        try {
            AddonRules.send = { _, _, _, _ -> throw IllegalStateException("no radio") }
            AddonBus.dispatch(p.ctx, event(body = "لغو11"))
            assertFalse("a failed send must not start the cooldown", p.values.containsKey("addon_fired_rb"))
            assertTrue("so the next SMS retries", AddonRules.allowedNow(p.ctx, rule, System.currentTimeMillis()))
        } finally {
            AddonRules.send = real
        }
    }

    @Test
    fun replyBlankFiresOnceThenTheCooldownBlocks() {
        val p = FakePrefs()
        val rule = AddonRule(id = "unsub", keywordContains = "لغو11", action = "reply_blank", cooldownSeconds = 60)
        rules(p, rule)
        val sent = mutableListOf<String>()
        val real = AddonRules.send
        try {
            AddonRules.send = { _, to, _, _ -> sent += to; true }
            AddonBus.dispatch(p.ctx, event(sender = "1000", body = "لغو11"))
            AddonBus.dispatch(p.ctx, event(sender = "1000", body = "لغو11"))
            assertEquals("second SMS inside the window is blocked", listOf("1000"), sent)
            assertTrue(p.values.containsKey("addon_fired_unsub"))
        } finally {
            AddonRules.send = real
        }
    }

    @Test
    fun failedLaunchDoesNotArmTheCooldown() {
        val p = FakeClipPrefs()
        val rule = AddonRule(
            id = "app",
            keywordContains = "کد",
            cooldownSeconds = 60,
            actionList = listOf(Act("open_app", mapOf("package" to "com.nope")))
        )
        AddonRules.save(p.ctx, listOf(rule))
        withLaunch({ _, _ -> false }) { AddonBus.dispatch(p.ctx, event(body = "کد 42")) }
        assertFalse("a failed launch did nothing, so it must not start the cooldown", p.prefs.values.containsKey("addon_fired_app"))
    }

    // ---------- the editor's sentence, and the ceilings it must respect ----------

    @Test
    fun summarizeReadsLikeASentence() {
        val tree = Tree(
            Cond.Sender("Bank", "exact"),
            Cond.Group("OR", items = listOf(Cond.Keyword("کد"), Cond.Keyword("رمز"))),
            Cond.Keyword("لغو", not = true)
        )
        assertEquals(
            "sender is 'Bank' AND (keyword 'کد' OR keyword 'رمز') AND NOT 'لغو'",
            AddonRules.summarize(tree)
        )
    }

    @Test
    fun legacyRuleSummarizesAsItsMigratedTree() {
        val legacy = AddonRule(senderContains = "Bank", keywords = listOf("کد", "رمز", "-لغو"), keywordMode = "OR")
        assertEquals(
            "sender contains 'Bank' AND (keyword 'کد' OR keyword 'رمز') AND NOT 'لغو'",
            AddonRules.summarize(AddonRules.toTree(legacy))
        )
    }

    @Test
    fun groupNegationAndEmptyGroupsReadCorrectly() {
        val tree = Tree(Cond.Group("AND", not = true, items = listOf(Cond.Keyword("a"), Cond.Length(min = 5))))
        assertEquals("NOT (keyword 'a' AND length >= 5)", AddonRules.summarize(tree))
        assertEquals("any SMS", AddonRules.summarize(Cond.Group()))
        assertEquals("never", AddonRules.summarize(Cond.Group("OR")))
    }

    @Test
    fun actionSummaryCarriesPerActionParams() {
        val acts = listOf(
            Act("forward", mapOf("number" to "0912")),
            Act("open_app", mapOf("package" to "org.telegram.messenger")),
            Act("call")
        )
        val labels = mapOf("org.telegram.messenger" to "Telegram")
        assertEquals(
            "forward 0912, open Telegram, call sender",
            acts.joinToString(", ") { AddonRules.summarizeAct(it) { p -> labels[p] ?: p } }
        )
    }

    @Test
    fun editorCeilingsCountWhatTheMatcherCounts() {
        val tree = Tree(Cond.Group(items = listOf(Cond.Keyword("a"), Cond.Contact())))
        assertEquals(4, AddonRules.nodeCount(tree))
        assertEquals(2, AddonRules.leafCount(tree))
        assertNull(AddonRules.treeProblem(tree))
    }
}
