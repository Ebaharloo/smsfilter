package ir.smsfilter.addon

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Adversarial coverage for S1 (Persian normalization in AddonRules.matches)
 * and S2 (keyword list, AND/OR, regex, legacy migration).
 * Every assertion documents a *required* property; failing ones mark real defects.
 */
class AddonRulesAdversarialTest {

    private fun ev(
        sender: String = "123",
        body: String = "hello",
        fromContact: Boolean = false,
        isSpam: Boolean = false,
        otp: String? = null
    ) = SmsEvent(sender, body, 0L, 0, 1L, isSpam, fromContact, otp, 0)

    private fun ts(dayOfWeek: Int, hour: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.DAY_OF_WEEK, dayOfWeek)
        c.set(java.util.Calendar.HOUR_OF_DAY, hour)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ---------- 1. keyword that normalizes to empty ----------

    @Test
    fun zwnjOnlyKeywordDoesNotMatchEveryMessage() {
        // ZWNJ is not whitespace -> isNotBlank() is true -> condition is evaluated,
        // but normalize() turns it into "" -> body.contains("") == true.
        val rule = AddonRule(keywordContains = "\u200C")
        // pre-S1 code was: event.body.contains(rule.keywordContains, ignoreCase = true)
        assertFalse("pre-S1 literal compare did not match", "سلام".contains("\u200C", ignoreCase = true))
        assertFalse("ZWNJ-only keyword must not match a plain SMS", AddonRules.matches(rule, ev(body = "سلام")))
        assertFalse("ZWNJ-only keyword must not match an empty SMS", AddonRules.matches(rule, ev(body = "")))
    }

    @Test
    fun paddedZwnjKeywordDoesNotMatchEveryMessage() {
        // padding defeats the isNotBlank() guard and the normalized form is still empty
        val rule = AddonRule(keywordContains = "  \u200C  ")
        assertFalse("padded ZWNJ keyword must not match every SMS", AddonRules.matches(rule, ev(body = "سلام")))
    }

    @Test
    fun diacriticsOnlyKeywordDoesNotMatchEveryMessage() {
        val rule = AddonRule(keywordContains = "\u064B\u064C\u0652")
        assertFalse("diacritics-only keyword must not match every SMS", AddonRules.matches(rule, ev(body = "anything")))
    }

    @Test
    fun tatweelOnlyKeywordDoesNotMatchEveryMessage() {
        val rule = AddonRule(keywordContains = "\u0640")
        assertFalse("tatweel-only keyword must not match every SMS", AddonRules.matches(rule, ev(body = "anything")))
    }

    @Test
    fun bidiMarkOnlySenderDoesNotMatchEveryMessage() {
        val rule = AddonRule(senderContains = "\u200F")
        assertFalse("RLM-only sender rule must not match every sender", AddonRules.matches(rule, ev(sender = "999")))
    }

    // ---------- 2. short / numeric keywords ----------

    @Test
    fun numericKeywordIsSubstring() {
        // document: plain substring semantics, "23" hits inside "1234"
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "23"), ev(body = "1234")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "23"), ev(body = "4567")))
    }

    @Test
    fun persianDigitsAgainstSpacedAndPlainAscii() {
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "۱۲۳"), ev(body = "123")))
        // squash() bridges space-separated digit evasion
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "۱۲۳"), ev(body = "1 2 3")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "۱۲۳"), ev(body = "1 2 4")))
    }

    @Test
    fun arabicIndicDigitsNormalize() {
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "٤٢"), ev(body = "code 42")))
    }

    @Test
    fun singleCharKeyword() {
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "ک"), ev(body = "کد")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "ک"), ev(body = "abc")))
    }

    // ---------- 3. regex metacharacters are literal ----------

    @Test
    fun regexMetacharsAreLiteral() {
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "\\d+"), ev(body = "code 123")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "\\d+"), ev(body = "regex \\d+ here")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = ".*"), ev(body = "anything")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = ".*"), ev(body = "a.*b")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "("), ev(body = "abc")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "("), ev(body = "a(b")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "["), ev(body = "a[b")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "$^|"), ev(body = "x$^|y")))
    }

    // ---------- 4. empty / blank / whitespace ----------

    @Test
    fun emptyAndBlankBodiesNeverMatchRealKeyword() {
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "otp"), ev(body = "")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "otp"), ev(body = "   ")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "otp"), ev(body = "\u200C\u200C")))
    }

    @Test
    fun keywordPaddedWithSpaces() {
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "  otp  "), ev(body = "your otp code")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "  کد  "), ev(body = "کد 42")))
    }

    @Test
    fun bodyWithDuplicateAndNewlineWhitespace() {
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "کد تخفیف"), ev(body = "کد    تخفیف")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "کد تخفیف"), ev(body = "کد\nتخفیف")))
    }

    // ---------- 5. tatweel + diacritics ----------

    @Test
    fun tatweelInsideKeywordAndBody() {
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "ت\u0640خ\u0640فیف"), ev(body = "تخفیف ویژه")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "تخفیف"), ev(body = "ت\u0640خ\u0640فیف ویژه")))
    }

    @Test
    fun diacriticsInsideKeywordAndBody() {
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "کَدم"), ev(body = "کدم")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "کدم"), ev(body = "کَدِم")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "سفارش"), ev(body = "سِفارِش شما")))
    }

    @Test
    fun arabicLetterVariantsInBody() {
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "کد"), ev(body = "كِد 42")))
    }

    // ---------- 6. sender normalization ----------

    @Test
    fun persianDigitSenderMatchesAsciiRule() {
        assertTrue(AddonRules.matches(AddonRule(senderContains = "123"), ev(sender = "۱۲۳")))
        assertTrue(AddonRules.matches(AddonRule(senderContains = "۱۲۳"), ev(sender = "123")))
    }

    @Test
    fun senderWithPlusAndSpaces() {
        assertTrue(AddonRules.matches(AddonRule(senderContains = "+98"), ev(sender = "+98 912 345 6789")))
        assertTrue(AddonRules.matches(AddonRule(senderContains = "912 345"), ev(sender = "+98 912 345 6789")))
        // sender matching does NOT squash spaces, unlike keyword matching
        assertFalse(AddonRules.matches(AddonRule(senderContains = "912345"), ev(sender = "912 345")))
    }

    @Test
    fun blankSenderNeverMatchesSenderRule() {
        assertFalse(AddonRules.matches(AddonRule(senderContains = "123"), ev(sender = "")))
        assertFalse(AddonRules.matches(AddonRule(senderContains = "123"), ev(sender = "   ")))
    }

    @Test
    fun senderKeywordAndLengthAllRequired() {
        val rule = AddonRule(senderContains = "۱۲۳", keywordContains = "کد", minLength = 10)
        assertTrue(AddonRules.matches(rule, ev(sender = "123", body = "کد شما 4321 است")))
        assertFalse("sender ok, keyword ok, too short", AddonRules.matches(rule, ev(sender = "123", body = "کد 42")))
        assertFalse("sender ok, length ok, no keyword", AddonRules.matches(rule, ev(sender = "123", body = "پیام طولانی بدون کلید")))
        assertFalse("keyword+length ok, wrong sender", AddonRules.matches(rule, ev(sender = "999", body = "کد شما 4321 است")))
    }

    // ---------- 7. Latin case folding ----------

    @Test
    fun latinCaseFolding() {
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "OTP"), ev(body = "your otp code")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "otp"), ev(body = "YOUR OTP CODE")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "OtP"), ev(body = "Otp")))
        assertTrue(AddonRules.matches(AddonRule(senderContains = "BANK"), ev(sender = "bank-mci")))
    }

    // ---------- 8. regressions on other conditions ----------

    @Test
    fun nonContentConditionsUnchanged() {
        assertFalse(AddonRules.matches(AddonRule(enabled = false), ev()))
        assertTrue(AddonRules.matches(AddonRule(), ev()))
        assertTrue(AddonRules.matches(AddonRule(minLength = 5), ev(body = "12345")))
        assertFalse(AddonRules.matches(AddonRule(minLength = 5), ev(body = "1234")))
        assertTrue(AddonRules.matches(AddonRule(maxLength = 5), ev(body = "12345")))
        assertFalse(AddonRules.matches(AddonRule(maxLength = 5), ev(body = "123456")))
        assertTrue(AddonRules.matches(AddonRule(onlyFromContacts = true), ev(fromContact = true)))
        assertFalse(AddonRules.matches(AddonRule(onlyFromContacts = true), ev(fromContact = false)))
        assertTrue(AddonRules.matches(AddonRule(onlySpam = true), ev(isSpam = true)))
        assertFalse(AddonRules.matches(AddonRule(onlySpam = true), ev(isSpam = false)))
        assertTrue(AddonRules.matches(AddonRule(onlyNotSpam = true), ev(isSpam = false)))
        assertFalse(AddonRules.matches(AddonRule(onlyNotSpam = true), ev(isSpam = true)))
        assertTrue(AddonRules.matches(AddonRule(otpRequired = true), ev(otp = "42")))
        assertFalse(AddonRules.matches(AddonRule(otpRequired = true), ev(otp = null)))
        assertTrue(AddonRules.matches(AddonRule(otpRequired = false), ev(otp = null)))
        assertFalse(AddonRules.matches(AddonRule(otpRequired = false), ev(otp = "42")))
    }

    @Test
    fun timeWindowsUnchanged() {
        assertTrue(AddonRules.matches(AddonRule(daysOfWeek = "1"), ev(), ts(java.util.Calendar.SUNDAY, 12)))
        assertFalse(AddonRules.matches(AddonRule(daysOfWeek = "1"), ev(), ts(java.util.Calendar.MONDAY, 12)))
        assertTrue(AddonRules.matches(AddonRule(startHour = 9, endHour = 17), ev(), ts(java.util.Calendar.MONDAY, 12)))
        assertFalse(AddonRules.matches(AddonRule(startHour = 9, endHour = 17), ev(), ts(java.util.Calendar.MONDAY, 20)))
        assertTrue(AddonRules.matches(AddonRule(startHour = 22, endHour = 6), ev(), ts(java.util.Calendar.MONDAY, 3)))
        assertFalse(AddonRules.matches(AddonRule(startHour = 22, endHour = 6), ev(), ts(java.util.Calendar.MONDAY, 12)))
        // garbage daysOfWeek must not throw and must not add a false condition
        assertTrue(AddonRules.matches(AddonRule(daysOfWeek = "abc,,x"), ev(), ts(java.util.Calendar.MONDAY, 12)))
    }

    @Test
    fun conditionsAreAndedNotOred() {
        val rule = AddonRule(senderContains = "123", onlyFromContacts = true)
        assertTrue(AddonRules.matches(rule, ev(sender = "123", fromContact = true)))
        assertFalse(AddonRules.matches(rule, ev(sender = "123", fromContact = false)))
        assertFalse(AddonRules.matches(rule, ev(sender = "999", fromContact = true)))
    }

    // ---------- 9. near-miss Persian words ----------

    @Test
    fun differentWordSharingLettersDoesNotMatch() {
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "سفارش"), ev(body = "سفره شما آماده است")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "کد"), ev(body = "کذب است")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "تخفیف"), ev(body = "تخفیف")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "تخفیف"), ev(body = "تخفیف ویژه")))
    }

    // ---------- hazards ----------

    @Test
    fun hugeBodyDoesNotThrow() {
        val body = "کد " + "a".repeat(200_000)
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "کد"), ev(body = body)))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "zzz"), ev(body = body)))
    }

    @Test
    fun unicodeSurrogatesAndControlsDoNotThrow() {
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "otp"), ev(body = "\uD83D\uDE00\u0000\u0007")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "otp"), ev(body = "\uD83D\uDE00otp\u0000")))
    }

    // ---------- 10. second pass: probes aimed at the empty-normalized-term fix ----------

    @Test
    fun secondPassPaddedZwnjKeywordAndSenderAreInert() {
        // "  \u200C  " passes isNotBlank(), normalizes to "" -> must fail the condition, not satisfy it.
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "  \u200C  "), ev(body = "سلام")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "  \u200C  "), ev(body = "")))
        assertFalse(AddonRules.matches(AddonRule(senderContains = "  \u200C  "), ev(sender = "999")))
        // AND: an unusable term must also poison a rule that has other usable conditions.
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "  \u200C  ", minLength = 1), ev(body = "سلام")))
    }

    @Test
    fun secondPassTatweelDiacriticsMixIsInert() {
        val term = "\u0640\u064B\u0640\u0652\u0670\u0640"
        assertFalse("tatweel+diacritics keyword must not match", AddonRules.matches(AddonRule(keywordContains = term), ev(body = "anything")))
        assertFalse("tatweel+diacritics sender must not match", AddonRules.matches(AddonRule(senderContains = term), ev(sender = "123")))
        // mixed with a real letter it becomes usable again (one visible char left)
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "ک\u0640\u064B"), ev(body = "کد 42")))
    }

    @Test
    fun secondPassOneCharNormalizedKeyword() {
        // ZWNJ + tatweel only -> "" -> inert
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "\u200C\u0640"), ev(body = "anything")))
        // ZWNJ + tatweel around one letter -> exactly "ک": usable single-char substring
        val one = "\u200C\u0640ک\u200C\u0640"
        assertTrue(AddonRules.matches(AddonRule(keywordContains = one), ev(body = "کد 42")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = one), ev(body = "abc")))
    }

    @Test
    fun secondPassDigitScriptsCollapseToAscii() {
        // Arabic-Indic ٠١٢ <-> Persian ۰۱۲ <-> ASCII 012
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "٠١٢"), ev(body = "۰۱۲")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "۰۱۲"), ev(body = "012")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "012"), ev(body = "٠١٢")))
        assertTrue(AddonRules.matches(AddonRule(senderContains = "٠١٢"), ev(sender = "012")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "٠١٢"), ev(body = "۰۱۳")))
    }

    @Test
    fun secondPassUnusableSenderFailsWholeRuleEvenWithValidKeyword() {
        // AND semantics: keyword hits, sender term unusable -> rule must NOT match
        val rule = AddonRule(senderContains = "\u200C", keywordContains = "کد")
        assertFalse(
            "unusable sender must fail the rule, keyword must not rescue it",
            AddonRules.matches(rule, ev(sender = "123", body = "کد 42"))
        )
        // control: same rule, usable sender
        assertTrue(
            AddonRules.matches(AddonRule(senderContains = "123", keywordContains = "کد"), ev(sender = "123", body = "کد 42"))
        )
        // and the mirror: unusable keyword + valid sender
        assertFalse(
            AddonRules.matches(AddonRule(senderContains = "123", keywordContains = "\u0640"), ev(sender = "123", body = "کد 42"))
        )
    }

    @Test
    fun secondPassNoConditionRuleStillMatchesEverything() {
        // Documented current behaviour, NOT the S1 bug: a rule with zero conditions matches all,
        // and because the raw-blank gate skips the condition, a spaces-only keyword is zero conditions.
        // S3 keeps this contract for matches(); matching() is what refuses to run such a rule.
        assertTrue(AddonRules.matches(AddonRule(), ev(sender = "", body = "")))
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "   "), ev(body = "anything")))
        assertTrue(AddonRules.matches(AddonRule(senderContains = "   "), ev(sender = "999")))
    }

    @Test
    fun secondPassSquashFalsePositives() {
        // intended: spaced-out digit evasion
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "123"), ev(body = "1 2 3")))
        // KNOWN FALSE POSITIVE: squash joins digits that were never one number
        assertTrue(
            "false positive: keyword 123 matches body '12 34'",
            AddonRules.matches(AddonRule(keywordContains = "123"), ev(body = "12 34"))
        )
        // mirror direction: spaced keyword matches joined body
        assertTrue(
            "false positive: spaced keyword '12 34' matches body '1234'",
            AddonRules.matches(AddonRule(keywordContains = "12 34"), ev(body = "1234"))
        )
        // sanity: squash does not invent characters
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "1234"), ev(body = "12 3")))
    }

    // ================= S2: keyword list, AND/OR, regex, migration =================

    private fun kw(vararg terms: String, mode: String = "AND", regex: Boolean = false) =
        AddonRule(keywords = terms.toList(), keywordMode = mode, keywordRegex = regex)

    private fun ms(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    // ---------- S2.1 regex safety ----------

    @Test
    fun s2InvalidRegexPatternsAreInertAndNeverThrow() {
        val junk = listOf("(", "[", "*", "(?<", "\\p{", "\\", "a{2,1}", "(?P<x>a)", "\\x{", "(?<=a", ")")
        for (p in junk) {
            val r = kw(p, regex = true)
            assertFalse("pattern '$p' must not match", AddonRules.matches(r, ev(body = "abc ( [ * 123")))
            assertFalse("pattern '$p' must not match empty body", AddonRules.matches(r, ev(body = "")))
        }
        // junk in OR must not poison usable siblings, junk in AND must fail the rule
        assertTrue(AddonRules.matches(kw("(", "کد", mode = "OR", regex = true), ev(body = "کد 42")))
        assertFalse(AddonRules.matches(kw("(", "کد", mode = "AND", regex = true), ev(body = "کد 42")))
    }

    @Test
    fun s2RegexSeesNormalizedBody() {
        // Persian/Arabic digits and letter folding happen before the regex runs
        assertTrue(AddonRules.matches(kw("^09\\d{9}$", regex = true), ev(body = "۰۹۱۲۳۴۵۶۷۸۹")))
        // only the body is normalized, never the pattern: a Persian-digit pattern cannot match ASCII
        assertFalse(AddonRules.matches(kw("^۰۹", regex = true), ev(body = "09123456789")))
        // body-side letter folding does apply
        assertTrue(AddonRules.matches(kw("^کد", regex = true), ev(body = "كِد 42")))
        assertFalse(AddonRules.matches(kw("^09", regex = true), ev(body = "۰ ۹۱۲۳۴۵۶۷۸۹")))
        // regex does NOT get the squash path, so spaced-out evasion misses
        assertFalse(AddonRules.matches(kw("0912", regex = true), ev(body = "0 9 1 2")))
    }

    @Test
    fun s2RegexIsCaseSensitiveOnLowercasedBody() {
        // normalize() lowercases the body; the pattern is NOT lowercased. Consequence: a user
        // pattern with uppercase Latin letters never matches. Documented, not silently "fixed".
        assertTrue(AddonRules.matches(kw("otp", regex = true), ev(body = "YOUR OTP CODE")))
        assertFalse("uppercase pattern cannot match a lowercased body", AddonRules.matches(kw("OTP", regex = true), ev(body = "your otp code")))
        assertTrue(AddonRules.matches(kw("(?i)OTP", regex = true), ev(body = "your otp code")))
    }

    @Test
    fun s2EmptyRegexTermCannotMatchEverything() {
        // Regex("").containsMatchIn(anything) is true; the blank guard is what stops it.
        assertFalse(AddonRules.matches(kw("", mode = "OR", regex = true), ev(body = "anything")))
        assertFalse(AddonRules.matches(kw("", mode = "AND", regex = true), ev(body = "anything")))
        assertFalse("OR with an empty term must not make everything match", AddonRules.matches(kw("", "کد", mode = "OR", regex = true), ev(body = "سلام")))
        assertTrue(AddonRules.matches(kw("", "کد", mode = "OR", regex = true), ev(body = "کد 42")))
        assertFalse(AddonRules.matches(kw("", "کد", mode = "AND", regex = true), ev(body = "کد 42")))
        assertFalse(AddonRules.matches(kw("   ", mode = "OR", regex = true), ev(body = "anything")))
        // terms that normalize away are inert in regex mode too
        assertFalse(AddonRules.matches(kw("\u200C", "\u0640", mode = "OR", regex = true), ev(body = "anything")))
    }

    @Test
    fun s2RegexCatastrophicBacktrackingStaysUnderReceiveBudget() {
        // SMS receive path: IncomingSms -> AddonBus.dispatch -> firstMatch -> matches. A rule that
        // costs hundreds of ms per message delays every following SMS on that thread.
        val p = "(a+)+$"                       // plausible user intent: "body ends with a run of a"
        val match1000 = "a".repeat(1000)
        val match5000 = "a".repeat(5000)
        val fail1000 = "a".repeat(1000) + "b"
        val fail5000 = "a".repeat(5000) + "b"

        // A nested quantifier is refused outright since S2b: the pattern never runs, so cost is zero
        // and an "ends with a run of a" intent has to be written without nesting (e.g. "a+$").
        assertFalse("nested quantifier must be refused, not executed", AddonRules.matches(kw(p, regex = true), ev(body = match1000)))
        assertFalse("nested quantifier must be refused, not executed", AddonRules.matches(kw(p, regex = true), ev(body = match5000)))
        assertFalse(AddonRules.matches(kw(p, regex = true), ev(body = fail1000)))
        assertFalse(AddonRules.matches(kw(p, regex = true), ev(body = fail5000)))

        var tMatch1000 = Long.MAX_VALUE
        var tFail1000 = Long.MAX_VALUE
        repeat(3) { tMatch1000 = minOf(tMatch1000, ms { AddonRules.matches(kw(p, regex = true), ev(body = match1000)) }) }
        repeat(3) { tFail1000 = minOf(tFail1000, ms { AddonRules.matches(kw(p, regex = true), ev(body = fail1000)) }) }
        val tFail5000 = ms { AddonRules.matches(kw(p, regex = true), ev(body = fail5000)) }
        println("S2 regex timing '$p' (ms): match1000=$tMatch1000 fail1000=$tFail1000 fail5000=$tFail5000")

        assertTrue("S2 FAIL: non-matching regex cost ${tFail1000}ms on a 1001-char body, budget 50ms/SMS/rule", tFail1000 < 50)
    }

    @Test
    fun s2RegexStackOverflowIsContainedAndReturnsFalse() {
        // Java's engine can blow the stack on nested quantifiers; regexHit catches Throwable.
        val body = "a".repeat(5000)
        try {
            assertFalse(AddonRules.matches(kw("(a+)+b", regex = true), ev(body = body)))
        } catch (t: Throwable) {
            throw AssertionError("regex failure escaped matches(): ${t.javaClass.name}", t)
        }
    }

    // ---------- S2.2 AND / OR edges ----------

    @Test
    fun s2SingleTermListMatchesLegacyKeyword() {
        val terms = listOf("کد", "123", "12 34", "OTP", "تخفیف", "۱۲۳")
        val bodies = listOf("کد 42", "1234", "1 2 3", "12 34", "your otp code", "تخفیف ویژه", "hello", "", "   ")
        for (t in terms) for (b in bodies) {
            val legacy = AddonRules.matches(AddonRule(keywordContains = t), ev(body = b))
            val listed = AddonRules.matches(AddonRule(keywords = listOf(t)), ev(body = b))
            assertEquals("parity term='$t' body='$b'", legacy, listed)
        }
    }

    @Test
    fun s2DuplicatedAndPaddedTerms() {
        assertTrue(AddonRules.matches(kw("کد", "کد"), ev(body = "کد 42")))
        assertTrue(AddonRules.matches(kw("کد", "کد", mode = "OR"), ev(body = "کد 42")))
        assertTrue(AddonRules.matches(kw("  کد  ", mode = "AND"), ev(body = "کد 42")))
        assertTrue(AddonRules.matches(kw("کد", "  تخفیف "), ev(body = "کد تخفیف")))
        // squash path per term, same as legacy single keyword
        assertTrue(AddonRules.matches(kw("کد تخفیف"), ev(body = "کد    تخفیف")))
        assertTrue(AddonRules.matches(kw("123"), ev(body = "1 2 3")))
        // AND is really AND, OR is really OR
        assertFalse(AddonRules.matches(kw("کد", "پرداخت"), ev(body = "کد 42")))
        assertTrue(AddonRules.matches(kw("کد", "پرداخت", mode = "OR"), ev(body = "کد 42")))
        assertFalse(AddonRules.matches(kw("کد", "پرداخت", mode = "OR"), ev(body = "سلام")))
    }

    @Test
    fun s2BlankTermsNeverMatchEveryMessage() {
        assertFalse(AddonRules.matches(kw("", mode = "AND"), ev(body = "anything")))
        assertFalse(AddonRules.matches(kw("", mode = "OR"), ev(body = "anything")))
        assertFalse(AddonRules.matches(kw("", ""), ev(body = "anything")))
        assertFalse(AddonRules.matches(kw("   ", mode = "OR"), ev(body = "anything")))
        assertFalse(AddonRules.matches(kw("", mode = "OR"), ev(body = "")))
        // note the asymmetry with legacy: a blank legacy keyword is "no condition" (matches all),
        // a blank list term is an unsatisfiable condition. The list is the safer of the two.
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "   "), ev(body = "anything")))
    }

    @Test
    fun s2ListWinsOverLegacyField() {
        val r = AddonRule(keywordContains = "zzz", keywords = listOf("کد"))
        assertTrue(AddonRules.matches(r, ev(body = "کد 42")))
        assertFalse("legacy field must be ignored when the list is non-empty", AddonRules.matches(r, ev(body = "zzz here")))
    }

    @Test
    fun s2NoKeywordListFallsBackToLegacyOrAbsentCondition() {
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "کد", keywords = emptyList()), ev(body = "کد 42")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "کد", keywords = emptyList()), ev(body = "سلام")))
        assertTrue(AddonRules.matches(AddonRule(senderContains = "123"), ev(sender = "123", body = "هر چیز")))
        assertFalse(AddonRules.matches(AddonRule(senderContains = "123"), ev(sender = "999", body = "هر چیز")))
    }

    @Test
    fun s2UnknownModeFallsBackToAnd() {
        assertFalse(AddonRules.matches(AddonRule(keywords = listOf("کد", "پرداخت"), keywordMode = "XOR"), ev(body = "کد 42")))
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("کد", "پرداخت"), keywordMode = "XOR"), ev(body = "کد و پرداخت")))
        assertFalse(AddonRules.matches(AddonRule(keywords = listOf("کد", "پرداخت"), keywordMode = ""), ev(body = "کد 42")))
        // mode is trimmed (S2b): padded " OR " is OR, and a lowercase "or" is OR too
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("کد", "پرداخت"), keywordMode = " OR "), ev(body = "کد 42")))
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("کد", "پرداخت"), keywordMode = "or"), ev(body = "کد 42")))
        assertTrue(AddonRules.matches(AddonRule(keywords = listOf("کد", "پرداخت"), keywordMode = "Or"), ev(body = "کد 42")))
    }

    // ---------- S2.3 interaction with S1 rules ----------

    @Test
    fun s2KeywordListAndSenderAreStillAnded() {
        assertTrue(AddonRules.matches(AddonRule(senderContains = "123", keywords = listOf("کد")), ev(sender = "123", body = "کد 42")))
        assertFalse("sender ok, list short", AddonRules.matches(AddonRule(senderContains = "123", keywords = listOf("کد", "پرداخت")), ev(sender = "123", body = "کد 42")))
        assertFalse("wrong sender", AddonRules.matches(AddonRule(senderContains = "123", keywords = listOf("کد")), ev(sender = "999", body = "کد 42")))
    }

    @Test
    fun s2UnusableSenderKillsRuleEvenWithValidKeywordList() {
        // S1 property must survive the S2 refactor
        assertFalse(AddonRules.matches(AddonRule(senderContains = "\u200C", keywords = listOf("کد")), ev(sender = "123", body = "کد 42")))
        assertFalse(AddonRules.matches(AddonRule(senderContains = "\u200C", keywords = listOf("^کد"), keywordRegex = true), ev(sender = "123", body = "کد 42")))
        assertTrue(AddonRules.matches(AddonRule(senderContains = "123", keywords = listOf("^کد"), keywordRegex = true), ev(sender = "123", body = "کد 42")))
    }

    @Test
    fun s2KeywordListStaysAndedWithOtherConditions() {
        val r = AddonRule(keywords = listOf("کد"), keywordMode = "OR", minLength = 100)
        assertFalse("length condition still ANDed to the keyword list", AddonRules.matches(r, ev(body = "کد 42")))
        assertTrue(AddonRules.matches(r, ev(body = "کد " + "x".repeat(120))))
    }

    // ---------- S2.4 load/save (real JSON via proxy Context + SharedPreferences) ----------

    private class FakePrefs {
        val values = mutableMapOf<String, Any?>()
        val writes = mutableListOf<String>()
        private var editorRef: SharedPreferences.Editor? = null
        val ctx: Context

        init {
            val ed = Proxy.newProxyInstance(
                FakePrefs::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { _, m, args ->
                when (m.name) {
                    "putString", "putBoolean", "putInt", "putLong", "putFloat", "putStringSet" -> {
                        writes += args[0] as String
                        values[args[0] as String] = args[1]
                        editorRef
                    }
                    "remove" -> { values.remove(args[0] as String); editorRef }
                    "clear" -> { values.clear(); editorRef }
                    "commit" -> true
                    else -> null
                }
            } as SharedPreferences.Editor
            editorRef = ed
            val sp = Proxy.newProxyInstance(
                FakePrefs::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)
            ) { _, m, args ->
                when (m.name) {
                    "getString" -> values[args[0] as String] as? String
                    "getBoolean" -> values[args[0] as String] as? Boolean ?: (args.getOrNull(1) as? Boolean ?: false)
                    "getInt" -> values[args[0] as String] as? Int ?: (args.getOrNull(1) as? Int ?: 0)
                    "getLong" -> values[args[0] as String] as? Long ?: (args.getOrNull(1) as? Long ?: 0L)
                    "contains" -> values.containsKey(args[0] as String)
                    "edit" -> editorRef
                    else -> null
                }
            } as SharedPreferences
            ctx = object : android.content.ContextWrapper(null) {
                override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = sp
                // No services here: dispatch()'s execute() hits a null base context and throws,
                // which executeAll() catches per action. Nothing is faked into success.
            }
        }
    }

    private fun prefs(json: String?): FakePrefs = FakePrefs().also { if (json != null) it.values["addon_rules_json"] = json }

    @Test
    fun s2LoadLegacyJsonWithoutKeywordListFallsBack() {
        val r = AddonRules.load(prefs("""[{"id":"r1","name":"legacy","keywordContains":"کد"}]""").ctx).single()
        assertEquals(listOf("کد"), r.keywords)
        assertEquals("AND", r.keywordMode)
        assertFalse(r.keywordRegex)
        assertTrue("legacy rule must keep matching after migration", AddonRules.matches(r, ev(body = "کد 42")))
        assertFalse(AddonRules.matches(r, ev(body = "سلام")))
    }

    @Test
    fun s2LoadKeywordsArrayWinsAndIgnoresNonStrings() {
        val r = AddonRules.load(prefs("""[{"id":"r1","keywordContains":"zzz","keywords":["کد",5,null,{"a":1},"پرداخت"]}]""").ctx).single()
        assertEquals(listOf("کد", "پرداخت"), r.keywords)
    }

    @Test
    fun s2LoadNullEmptyOrWrongTypedKeywordsFallsBackToLegacy() {
        for (raw in listOf(
            """[{"id":"r1","keywordContains":"کد","keywords":null}]""",
            """[{"id":"r1","keywordContains":"کد","keywords":[]}]""",
            """[{"id":"r1","keywordContains":"کد","keywords":"کد"}]"""
        )) {
            assertEquals("raw=$raw", listOf("کد"), AddonRules.load(prefs(raw).ctx).single().keywords)
        }
        // no legacy value either -> no keyword condition at all
        assertEquals(emptyList<String>(), AddonRules.load(prefs("""[{"id":"r1","senderContains":"123"}]""").ctx).single().keywords)
    }

    @Test
    fun s2SaveWritesListModeRegexAndLegacyFirst() {
        val p = prefs(null)
        AddonRules.save(p.ctx, listOf(AddonRule(id = "a", keywords = listOf("کد", "پرداخت"), keywordMode = "OR", keywordRegex = true, keywordContains = "legacy-old")))
        val o = JSONArray(p.values["addon_rules_json"] as String).getJSONObject(0)
        assertEquals("کد", o.getString("keywordContains"))
        val arr = o.getJSONArray("keywords")
        assertEquals(listOf("کد", "پرداخت"), (0 until arr.length()).map { arr.getString(it) })
        assertEquals("OR", o.getString("keywordMode"))
        assertTrue(o.getBoolean("keywordRegex"))
    }

    @Test
    fun s2SaveLoadRoundTripKeepsOrderDuplicatesModeRegex() {
        val p = prefs(null)
        val rules = listOf(
            AddonRule(id = "1", name = "both", keywords = listOf("پرداخت", "کد", "پرداخت"), keywordContains = "پرداخت", keywordMode = "OR"),
            AddonRule(id = "2", name = "re", keywords = listOf("^09\\d{9}"), keywordContains = "^09\\d{9}", keywordRegex = true)
        )
        AddonRules.save(p.ctx, rules)
        assertEquals(rules, AddonRules.load(p.ctx))
    }

    @Test
    fun s2LegacyRuleGainsAKeywordsListOnRoundTrip() {
        // migration is one-way: keywordContains is copied into keywords and never cleared
        val p = prefs(null)
        AddonRules.save(p.ctx, listOf(AddonRule(id = "1", keywordContains = "تخفیف")))
        val back = AddonRules.load(p.ctx).single()
        assertEquals(listOf("تخفیف"), back.keywords)
        assertEquals("تخفیف", back.keywordContains)
        assertTrue(AddonRules.matches(back, ev(body = "تخفیف ویژه")))
    }

    @Test
    fun s2EmptyStringKeywordSurvivesAsAnUnsatisfiableRule() {
        // reachable only through JSON/import (the UI drops empty terms); silent never-match is the
        // documented consequence, never a match-everything.
        val p = prefs(null)
        AddonRules.save(p.ctx, listOf(AddonRule(id = "1", keywords = listOf(""))))
        val back = AddonRules.load(p.ctx).single()
        assertEquals(listOf(""), back.keywords)
        assertFalse(AddonRules.matches(back, ev(body = "anything")))
        assertFalse(AddonRules.matches(AddonRule(keywords = back.keywords, keywordMode = "OR"), ev(body = "anything")))
    }

    // ================= S2b/c/d: cost caps, regexUsable gate, regressions =================

    /** Refused pattern => regexHit returns false without running the engine, so cost is the gate only. */
    @Test
    fun s2cKnownRedosShapesAreRefusedCheaply() {
        val patterns = listOf("(a+)+$", "(a|aa)+$", "a*a*a*b", "a{0,200}a{0,200}a{0,200}b")
        val bodies = listOf(
            "a".repeat(2000),
            "a".repeat(1999) + "b",
            "a".repeat(1000) + "!" + "a".repeat(999)
        )
        var totalNanos = 0L
        for (p in patterns) {
            for (b in bodies) {
                val hit = AddonRules.matches(kw(p, regex = true), ev(body = b))
                val t0 = System.nanoTime()
                repeat(100) { AddonRules.matches(kw(p, regex = true), ev(body = b)) }
                val per = (System.nanoTime() - t0) / 100 / 1000
                totalNanos += per * 1000
                println("S2c cost '$p' on ${b.length}-char body: match=$hit ${per}us")
                assertFalse("pattern '$p' must be refused, never executed", hit)
            }
        }
        println("S2c 12 refused calls, mean cost each: ${totalNanos / 1000 / 12}us")
        assertTrue("refusal path must be fast, took ${totalNanos / 1000}us total", totalNanos < 50_000_000L)

        // control: most of that is normalize() over the 2000-char body, which every rule pays
        val b = "a".repeat(2000)
        repeat(100) { AddonRules.matches(AddonRule(keywordContains = "zzz"), ev(body = b)) }
        val t0 = System.nanoTime()
        repeat(100) { AddonRules.matches(AddonRule(keywordContains = "zzz"), ev(body = b)) }
        println("S2c baseline literal keyword, same body: ${(System.nanoTime() - t0) / 100 / 1000}us")

        // residual risk: two unbounded quantifiers are still allowed -> O(n^2) over the 200-char subject,
        // times each start position (unanchored find). Measured at two body lengths to prove the cap binds.
        for (p in listOf(".*.*b", "a*a*b")) for (len in listOf(200, 2000)) {
            val body = "a".repeat(len)
            repeat(50) { AddonRules.matches(kw(p, regex = true), ev(body = body)) }
            val t1 = System.nanoTime()
            repeat(50) { AddonRules.matches(kw(p, regex = true), ev(body = body)) }
            println("S2c allowed worst case '$p' on ${body.length}-char body: ${(System.nanoTime() - t1) / 50 / 1000}us")
            assertTrue("regexUsable('$p') must stay true: two quantifiers are the cap", AddonRules.regexUsable(p))
        }
    }

    /**
     * regexHit is private, so agreement is asserted the only way that is still honest: a body the
     * pattern would hit must match exactly when regexUsable says the editor will let it through.
     */
    @Test
    fun s2dRegexUsableAndRegexHitAgreeOnEverySample() {
        val samples = listOf(
            // sample pattern to usable?, and a body it would match if the engine ran
            Triple("""\d{2}/\d{2}/\d{4}""", false, "01/02/2024"),
            Triple("""\d{4}-\d{2}-\d{2}""", false, "2024-01-02"),
            Triple("(a|b)+", false, "abab"),
            Triple("(", false, "abc ("),
            Triple("a".repeat(121), false, "a".repeat(121)),
            Triple("", false, "anything"),
            Triple("   ", false, "anything"),
            Triple("""^09\d{9}$""", true, "09123456789"),
            Triple("ab+", true, "abbb"),
            Triple("[0-9]{4}", true, "code 1234"),
            Triple("(?:ab)+", true, "abab"),
            Triple("""https?://\S+""", true, "see https://x.y/z now"),
            Triple("""\d{2}:\d{2}""", true, "at 12:34")
        )
        for ((p, usable, body) in samples) {
            assertEquals("regexUsable('$p')", usable, AddonRules.regexUsable(p))
            assertEquals(
                "regexHit/matches drift for '$p': editor gate and runtime gate must agree",
                usable,
                AddonRules.matches(kw(p, regex = true), ev(body = body))
            )
        }
    }

    @Test
    fun s2cQuantifierCapIsExactlyTwo() {
        // boundary: 2 quantifiers pass, 3 are refused
        assertTrue(AddonRules.regexUsable("""\d{2}/\d{2}"""))
        assertFalse(AddonRules.regexUsable("""\d{2}/\d{2}/\d{4}"""))
        assertTrue(AddonRules.regexUsable("a*b*c"))
        assertFalse(AddonRules.regexUsable("a*b*c*d"))
        // refused even when the counts are tiny: this is a shape rule, not a width analysis
        assertFalse(AddonRules.regexUsable("^\\d{1}x{1}y{1}$"))
        // nested-quantifier rejection is about the group body, not the quantifier count
        assertFalse(AddonRules.regexUsable("(ab?)+"))
        assertTrue(AddonRules.regexUsable("(ab)+"))
    }

    @Test
    fun s2dCapsNeverTouchTheLiteralPath() {
        // caps live only in regexUsable/riskyPattern: literal keywords keep their old semantics
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "ab"), ev(body = "xabx")))
        assertFalse(AddonRules.matches(AddonRule(keywordContains = "ab"), ev(body = "xyz")))
        assertTrue("Persian keyword", AddonRules.matches(AddonRule(keywordContains = "کد"), ev(body = "کد 42")))
        assertTrue("squash path", AddonRules.matches(AddonRule(keywordContains = "تخفیف"), ev(body = "ت خ ف ی ف")))
        assertTrue("metachars stay literal", AddonRules.matches(AddonRule(keywordContains = "(a+)+$"), ev(body = "x (a+)+$ y")))
        assertTrue("long literal keyword", AddonRules.matches(AddonRule(keywordContains = "a".repeat(200)), ev(body = "a".repeat(200))))
        // legacy rule, list rule, AND, OR all intact
        assertTrue(AddonRules.matches(AddonRule(keywordContains = "کد", keywords = emptyList()), ev(body = "کد 42")))
        assertFalse(AddonRules.matches(kw("کد", "پرداخت"), ev(body = "کد 42")))
        assertTrue(AddonRules.matches(kw("کد", "پرداخت", mode = "OR"), ev(body = "کد 42")))
        assertFalse(AddonRules.matches(kw("کد", "پرداخت", mode = "OR"), ev(body = "سلام")))
    }

    @Test
    fun s2dNoInputThrowsOutOfMatches() {
        val patterns = listOf("(", ")", "[", "*", "+", "?", """\p{""", "(?<", "((((((((((a))))))))))", "a{1000}", "a".repeat(500))
        val bodies = listOf("", " ", "a", "a".repeat(2000), "a".repeat(200_000), "\uD83D\uDE00\u0000", "کد ۱۲۳ \u200C")
        for (p in patterns) for (b in bodies) {
            try {
                AddonRules.matches(kw(p, regex = true), ev(body = b))
            } catch (t: Throwable) {
                throw AssertionError("pattern '$p' on ${b.length}-char body escaped matches(): ${t.javaClass.name}", t)
            }
        }
    }

    // ================= S3: every matching rule, multi-action, cooldown, inert empty rules =================

    private fun rules(ctx: Context, vararg r: AddonRule) = AddonRules.save(ctx, r.toList())

    // ---------- S3.1 ordering / enablement ----------

    @Test
    fun s3EveryMatchingRuleIsReturnedOnceInPriorityOrder() {
        val p = prefs(null)
        rules(
            p.ctx,
            AddonRule(id = "mid", priority = 5, keywordContains = "کد"),
            AddonRule(id = "first", priority = 1, keywordContains = "کد"),
            AddonRule(id = "last", priority = 9, keywordContains = "کد")
        )
        assertEquals(listOf("first", "mid", "last"), AddonRules.matching(p.ctx, ev(body = "کد 42"), 0L).map { it.id })
    }

    @Test
    fun s3DisabledMiddleRuleIsSkippedAndLaterRuleStillFires() {
        val p = prefs(null)
        rules(
            p.ctx,
            AddonRule(id = "1", priority = 1, keywordContains = "کد"),
            AddonRule(id = "2", priority = 2, keywordContains = "کد", enabled = false),
            AddonRule(id = "3", priority = 3, keywordContains = "کد")
        )
        assertEquals(listOf("1", "3"), AddonRules.matching(p.ctx, ev(body = "کد 42"), 0L).map { it.id })
    }

    @Test
    fun s3EqualPriorityKeepsSavedOrder() {
        val p = prefs(null)
        rules(
            p.ctx,
            AddonRule(id = "a", priority = 0, keywordContains = "کد"),
            AddonRule(id = "b", priority = 0, keywordContains = "کد"),
            AddonRule(id = "c", priority = 0, keywordContains = "کد")
        )
        assertEquals(listOf("a", "b", "c"), AddonRules.matching(p.ctx, ev(body = "کد 42"), 0L).map { it.id })
    }

    @Test
    fun s3NonMatchingAndDisabledRulesNeverAppear() {
        val p = prefs(null)
        rules(
            p.ctx,
            AddonRule(id = "hit", priority = 0, keywordContains = "کد"),
            AddonRule(id = "miss", priority = 1, keywordContains = "پرداخت"),
            AddonRule(id = "off", priority = 2, keywordContains = "کد", enabled = false)
        )
        assertEquals(listOf("hit"), AddonRules.matching(p.ctx, ev(body = "کد 42"), 0L).map { it.id })
    }

    // ---------- S3.2 cooldown ----------

    @Test
    fun s3CooldownBoundaryIsExact() {
        val p = prefs(null)
        val r = AddonRule(id = "cd", cooldownSeconds = 60, keywordContains = "کد")
        val t0 = 1_700_000_000_000L
        assertTrue("first fire", AddonRules.allowedNow(p.ctx, r, t0))
        assertTrue("nothing marked yet, pref absent", !p.values.containsKey("addon_fired_cd"))
        AddonRules.markFired(p.ctx, r, t0)
        assertEquals("last fire is persisted, not in memory", t0, p.values["addon_fired_cd"])
        assertFalse("59 999 ms is inside the window", AddonRules.allowedNow(p.ctx, r, t0 + 59_999))
        assertTrue("60 000 ms is the boundary and must pass", AddonRules.allowedNow(p.ctx, r, t0 + 60_000))
        assertTrue(AddonRules.allowedNow(p.ctx, r, t0 + 600_000))
    }

    @Test
    fun s3WallClockGoingBackwardsKeepsTheRuleBlocked() {
        val p = prefs(null)
        val r = AddonRule(id = "cd", cooldownSeconds = 60)
        val t0 = 1_700_000_000_000L
        AddonRules.markFired(p.ctx, r, t0)
        // Documented behaviour: a backwards clock (NTP step, manual change) yields a negative delta,
        // so the rule stays blocked until the clock reaches last+cooldown again. Safe direction.
        assertFalse("clock back 1s", AddonRules.allowedNow(p.ctx, r, t0 - 1_000))
        assertFalse("clock back a full window", AddonRules.allowedNow(p.ctx, r, t0 - 60_000))
        assertFalse("clock back a year", AddonRules.allowedNow(p.ctx, r, t0 - 31_536_000_000L))
    }

    @Test
    fun s3CooldownZeroWritesNoPrefAndRulesUseSeparateKeys() {
        val p = prefs(null)
        val free = AddonRule(id = "free", cooldownSeconds = 0)
        AddonRules.markFired(p.ctx, free, 1_000L)
        assertFalse("cooldown 0 must not touch prefs", p.values.keys.any { it.startsWith("addon_fired_") })
        assertTrue(AddonRules.allowedNow(p.ctx, free, 1_000L))

        val a = AddonRule(id = "a", cooldownSeconds = 60)
        val b = AddonRule(id = "b", cooldownSeconds = 60)
        AddonRules.markFired(p.ctx, a, 5_000L)
        assertTrue("b was never fired", AddonRules.allowedNow(p.ctx, b, 5_000L))
        assertFalse("a is cooling, b must not inherit it", AddonRules.allowedNow(p.ctx, a, 5_000L))
        AddonRules.markFired(p.ctx, b, 5_000L)
        assertEquals(5_000L, p.values["addon_fired_a"])
        assertEquals(5_000L, p.values["addon_fired_b"])
    }

    @Test
    fun s3DeletedRuleLeavesAStaleCooldownPref() {
        val p = prefs(null)
        val r = AddonRule(id = "gone", cooldownSeconds = 3600, keywordContains = "کد")
        rules(p.ctx, r)
        AddonRules.markFired(p.ctx, r, 10_000L)
        rules(p.ctx) // delete every rule: save() never prunes addon_fired_* keys
        assertTrue("rule is gone", AddonRules.matching(p.ctx, ev(body = "کد 42"), 0L).isEmpty())
        assertEquals("stale pref survives deletion (non-blocking)", 10_000L, p.values["addon_fired_gone"])
        // and a re-created rule that reuses the id starts out cooling
        rules(p.ctx, r)
        assertFalse("same id inherits the old window", AddonRules.allowedNow(p.ctx, r, 20_000L))
    }

    // ---------- S3.3 cooling rule must not fall through to the legacy actions path ----------

    private class SentinelAction : SmsAction {
        override val id = "test_sentinel"
        var calls = 0
        override fun enabled(ctx: Context) = true
        override fun onSmsReceived(ctx: Context, event: SmsEvent) { calls++ }
    }

    private fun withSentinel(block: (SentinelAction) -> Unit) {
        val s = SentinelAction()
        AddonBus.register(s)
        try { block(s) } finally { AddonBus.unregister(s.id) }
    }

    @Test
    fun s3CoolingRuleDoesNotFallThroughToLegacyActions() {
        withSentinel { sentinel ->
            val p = prefs(null)
            val r = AddonRule(id = "cool", cooldownSeconds = 60, keywordContains = "کد", action = "copy_otp")
            rules(p.ctx, r)
            AddonRules.markFired(p.ctx, r, System.currentTimeMillis())
            val before = p.values["addon_fired_cool"]
            AddonBus.dispatch(p.ctx, ev(body = "کد 42", otp = "1234"))
            assertEquals("legacy path must NOT run for a matched-but-cooling rule", 0, sentinel.calls)
            assertEquals("cooling rule did not re-fire", before, p.values["addon_fired_cool"])
        }
    }

    @Test
    fun s3NoRuleMatchedRunsLegacyPathExactlyOnce() {
        withSentinel { sentinel ->
            val p = prefs(null)
            rules(p.ctx, AddonRule(id = "other", keywordContains = "پرداخت"))
            AddonBus.dispatch(p.ctx, ev(body = "کد 42"))
            assertEquals("unchanged legacy behaviour", 1, sentinel.calls)
        }
    }

    @Test
    fun s3FiringRuleAlsoSkipsLegacyPath() {
        withSentinel { sentinel ->
            val p = prefs(null)
            rules(p.ctx, AddonRule(id = "hit", keywordContains = "کد", action = "copy_otp"))
            AddonBus.dispatch(p.ctx, ev(body = "کد 42", otp = "1234"))
            assertEquals("a claimed SMS must not also run the legacy actions", 0, sentinel.calls)
        }
    }

    @Test
    fun s3InertConditionlessRuleDoesNotClaimTheSmsSoLegacyStillRuns() {
        withSentinel { sentinel ->
            val p = prefs(null)
            rules(p.ctx, AddonRule(id = "inert", action = "copy_otp"))
            AddonBus.dispatch(p.ctx, ev(body = "کد 42", otp = "1234"))
            assertEquals("no rule matched -> legacy path", 1, sentinel.calls)
        }
    }

    // ---------- S3.4 inertness of condition-less rules ----------

    @Test
    fun s3ZeroConditionRuleMatchesButNeverFires() {
        val p = prefs(null)
        rules(p.ctx, AddonRule(id = "empty"))
        val e = ev(sender = "", body = "")
        assertTrue("matches() keeps its documented 'no condition = true' contract", AddonRules.matches(AddonRule(id = "empty"), e))
        assertTrue("but matching() refuses to hand it to the executor", AddonRules.matching(p.ctx, e, 0L).isEmpty())
    }

    @Test
    fun s3MatchAnyRestoresAZeroConditionRule() {
        val p = prefs(null)
        rules(p.ctx, AddonRule(id = "any", matchAny = true), AddonRule(id = "empty"))
        assertEquals(listOf("any"), AddonRules.matching(p.ctx, ev(body = "anything"), 0L).map { it.id })
        assertTrue(AddonRules.matches(AddonRule(matchAny = true), ev(body = "anything")))
    }

    @Test
    fun s3SpacesOnlyConditionsCountAsNoCondition() {
        val p = prefs(null)
        rules(
            p.ctx,
            AddonRule(id = "blankKw", keywordContains = "   "),
            AddonRule(id = "blankSender", senderContains = "   "),
            AddonRule(id = "blankList", keywords = listOf("   "))
        )
        // A list term is an unsatisfiable condition, not "no condition", so it is inert either way.
        assertTrue(AddonRules.matching(p.ctx, ev(sender = "123", body = "anything"), 0L).isEmpty())
        assertTrue("matches() still says true for the legacy blanks", AddonRules.matches(AddonRule(keywordContains = "   "), ev(body = "x")))
    }

    @Test
    fun s3ExistingConditionlessRuleFromAnOldInstallIsSilentlyDead() {
        // The pre-S3 "New rule" default: name + copy_otp, no conditions at all.
        val p = prefs("""[{"id":"old","name":"New rule","action":"copy_otp","priority":0}]""")
        val loaded = AddonRules.load(p.ctx).single()
        assertTrue("rule loads without error", loaded.enabled && loaded.actions.isEmpty())
        assertTrue("matches() would say yes", AddonRules.matches(loaded, ev(body = "code 42", otp = "42")))
        assertTrue("matching() excludes it, so it never fires", AddonRules.matching(p.ctx, ev(body = "code 42", otp = "42"), 0L).isEmpty())
        // no migration exists: save() writes matchAny=false, the rule stays dead after any UI edit
        AddonRules.save(p.ctx, listOf(loaded))
        assertTrue(AddonRules.matching(p.ctx, ev(body = "code 42", otp = "42"), 0L).isEmpty())
    }

    @Test
    fun s3DefaultsCreatedFromLegacyTogglesAreNotConditionless() {
        // ensureDefaults() must not create rules that S3 will refuse to run.
        val p = prefs(null)
        p.values["addon_otp_autocopy"] = true
        p.values["addon_copy_body"] = true
        p.values["addon_autoreply"] = true
        AddonRules.ensureDefaults(p.ctx)
        val defaults = AddonRules.load(p.ctx)
        assertEquals(3, defaults.size)
        for (r in defaults) {
            assertTrue("default rule '${r.name}' has a condition", AddonRules.matching(p.ctx, ev(body = "code 42", otp = "42", fromContact = true), 0L).any { it.id == r.id })
        }
    }

    // ---------- S3.5 multi-action ----------

    @Test
    fun s3ActionsCooldownAndMatchAnySurviveSaveLoad() {
        val p = prefs(null)
        val r = AddonRule(
            id = "multi", name = "multi", keywordContains = "کد", keywords = listOf("کد"),
            action = "mark_read", actions = listOf("mark_read", "copy_otp", "alert"),
            cooldownSeconds = 90, matchAny = false
        )
        AddonRules.save(p.ctx, listOf(r))
        val back = AddonRules.load(p.ctx).single()
        assertEquals(listOf("mark_read", "copy_otp", "alert"), back.actions)
        assertEquals("order is part of the contract", "mark_read", back.actions.first())
        assertEquals(90, back.cooldownSeconds)
        assertFalse(back.matchAny)
        assertEquals(r, back)
    }

    @Test
    fun s3LegacySingleActionRuleStillFiresOnce() {
        val p = prefs(null)
        rules(p.ctx, AddonRule(id = "l", keywordContains = "کد", action = "copy_otp"))
        val hit = AddonRules.matching(p.ctx, ev(body = "کد 42"), 0L)
        assertEquals(1, hit.size)
        assertEquals("legacy row has no actions list", emptyList<String>(), hit.single().actions)
        // executeAll falls back to the single `action`; nothing else can double it
        AddonRules.executeAll(p.ctx, hit.single(), ev(body = "کد 42", otp = "1234"), dryRun = true)
    }

    @Test
    fun s3UnknownActionDoesNotStopTheRestOfTheRule() {
        val p = prefs(null)
        val r = AddonRule(id = "x", keywordContains = "کد", actions = listOf("copy_otp", "no_such_action", "copy_body"))
        rules(p.ctx, r)
        val hit = AddonRules.matching(p.ctx, ev(body = "کد 42"), 0L).single()
        try {
            AddonRules.executeAll(p.ctx, hit, ev(body = "کد 42", otp = "1234"), dryRun = true)
        } catch (t: Throwable) {
            throw AssertionError("unknown action escaped executeAll: ${t.javaClass.name}", t)
        }
        // code-read: the loop is per-action try/catch, so the third action still runs after the second throws.
        assertEquals(3, hit.actions.size)
        assertEquals("copy_body", hit.actions.last())
    }

    @Test
    fun s3EmptyActionsListFallsBackToPrimaryAction() {
        val p = prefs(null)
        rules(p.ctx, AddonRule(id = "e", keywordContains = "کد", action = "copy_otp", actions = emptyList()))
        val hit = AddonRules.matching(p.ctx, ev(body = "کد 42"), 0L).single()
        assertEquals(emptyList<String>(), hit.actions)
        // executeAll on a dry run with an empty list must not throw and must cover `action`
        AddonRules.executeAll(p.ctx, hit, ev(body = "کد 42", otp = "1"), dryRun = true)
    }

    // ---------- S3.6 cost of 20 regex rules ----------

    @Test
    fun s3TwentyRegexRulesOnA200CharBodyStayUnder100ms() {
        val p = prefs(null)
        val patterns = listOf(
            """code\d{4}""", """ref\d{5}""", """order\d{2}""", """\d{2}:\d{2}""", """^09\d{9}$""",
            """invoice\d{6}""", """card\d{4}""", """otp\d{3}""", """bank\w+""", """payment\w{2}""",
            """discount\w{3}""", """verify\d{4}""", """token\w{4}""", """login\w{2}""", """\w+@\w+""",
            """https?://\S+""", """\d{4}-\d{2}""", """کد\w{2}""", """تخفیف\w{2}""", """سفارش\w{2}"""
        )
        assertEquals(20, patterns.size)
        val list = patterns.mapIndexed { i, pat ->
            AddonRule(id = "r$i", priority = i, keywords = listOf(pat), keywordContains = pat, keywordRegex = true)
        }
        AddonRules.save(p.ctx, list)
        for (pat in patterns) assertTrue("pattern '$pat' must be usable", AddonRules.regexUsable(pat))

        val body = ("your order code 1234 ref 56789 at 12:34 card 4321 invoice 123456 bank melli " +
            "payment discount verify token login 09123456789 https://x.y/z کد 42 تخفیف ویژه سفارش شما ثبت شد ").padEnd(200, ' ')
        assertEquals(200, body.length)
        val e = ev(body = body)

        repeat(50) { AddonRules.matching(p.ctx, e, 0L) }   // warm JIT + classloading
        val runs = 100
        val t0 = System.nanoTime()
        var matched = 0
        repeat(runs) { matched = AddonRules.matching(p.ctx, e, 0L).size }
        val avgMs = (System.nanoTime() - t0) / runs / 1_000_000.0
        println("S3 cost: 20 regex rules on ${body.length}-char body -> ${"%.3f".format(avgMs)}ms per matching() call ($matched rules matched)")

        // budget: one SMS receive path, includes the JSON load() that dispatch pays on every SMS
        assertTrue("S3 FAIL: 20 regex rules cost ${"%.2f".format(avgMs)}ms, budget 100ms", avgMs < 100.0)

        // Residual risk probe (not a pass gate): the quantifier cap still admits 2-quantifier patterns
        // that are slow on a homogeneous body. 20 of those blow the same budget, so record the number.
        val worst = ("a".repeat(200))
        val worstRules = (0 until 20).map { i -> AddonRule(id = "w$i", priority = i, keywords = listOf("a*a*b"), keywordContains = "a*a*b", keywordRegex = true) }
        val w = prefs(null)
        AddonRules.save(w.ctx, worstRules)
        repeat(5) { AddonRules.matching(w.ctx, ev(body = worst), 0L) }
        val w0 = System.nanoTime()
        repeat(3) { AddonRules.matching(w.ctx, ev(body = worst), 0L) }
        println("S3 worst-case regression probe: 20x 'a*a*b' on 200 'a's -> ${"%.1f".format((System.nanoTime() - w0) / 3 / 1_000_000.0)}ms")
    }


    // ---------- S3.7 execution evidence (TEST team) ----------
    // The action bodies cannot run on the JVM: ThreadFlags/Sms read Uri/CONTENT_URI statics whose
    // stub <clinit> throws, and every service lookup is null. What is observable is markFired():
    // AddonBus.dispatch calls it once per executed rule in the same loop body, so the order and
    // count of addon_fired_* writes is the order and count of rule executions.

    private fun evT(body: String, threadId: Long = 7) = SmsEvent("123", body, 0L, 0, threadId, false, false, "1234", 0)

    private fun firedWrites(p: FakePrefs) = p.writes.filter { it.startsWith("addon_fired_") }

    /**
     * FakePrefs plus a JVM-built ClipboardManager: the clipboard actions can actually finish here,
     * which the default FakePrefs context (no services) can never do.
     */
    private inner class ClipPrefs {
        val prefs = FakePrefs()
        val ctx: Context = object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int) =
                prefs.ctx.getSharedPreferences(name, mode)

            // shortcut: android.jar only has the stub ClipboardManager() constructor, so reflection
            // is how execute() gets a real instance on the JVM; upgrade to Robolectric when a fake is needed.
            override fun getSystemService(name: String): Any? =
                if (name == Context.CLIPBOARD_SERVICE)
                    android.content.ClipboardManager::class.java.getDeclaredConstructor()
                        .apply { isAccessible = true }.newInstance() as android.content.ClipboardManager
                else null
        }
    }

    @Test
    fun s3DispatchExecutesEveryMatchingRuleExactlyOnceInPriorityOrder() {
        val p = ClipPrefs()
        rules(
            p.ctx,
            AddonRule(id = "C", priority = 30, keywordContains = "کد", cooldownSeconds = 60),
            AddonRule(id = "A", priority = 10, keywordContains = "کد", cooldownSeconds = 60),
            AddonRule(id = "off", priority = 5, keywordContains = "کد", cooldownSeconds = 60, enabled = false),
            AddonRule(id = "miss", priority = 15, keywordContains = "پرداخت", cooldownSeconds = 60),
            AddonRule(id = "B", priority = 20, keywordContains = "کد", cooldownSeconds = 60)
        )
        assertEquals(listOf("A", "B", "C"), AddonRules.matching(p.ctx, evT("کد 42"), 0L).map { it.id })
        p.prefs.writes.clear()
        AddonBus.dispatch(p.ctx, evT("کد 42"))
        assertEquals(
            "each matched rule must run once, in priority order",
            listOf("addon_fired_A", "addon_fired_B", "addon_fired_C"),
            firedWrites(p.prefs)
        )
    }

    @Test
    fun s3CoolingRuleIsSkippedButLaterRulesStillRun() {
        val p = ClipPrefs()
        val hot = AddonRule(id = "hot", priority = 1, keywordContains = "کد", cooldownSeconds = 3600)
        rules(p.ctx, hot, AddonRule(id = "later", priority = 2, keywordContains = "کد", cooldownSeconds = 60))
        val stamped = System.currentTimeMillis()
        AddonRules.markFired(p.ctx, hot, stamped)
        p.prefs.writes.clear()
        AddonBus.dispatch(p.ctx, evT("کد 42"))
        assertEquals("cooling rule must not execute, the next one must", listOf("addon_fired_later"), firedWrites(p.prefs))
        assertEquals("cooling rule keeps its original timestamp", stamped, p.prefs.values["addon_fired_hot"])
    }

    // ================= S4: dispatch placement + cooldown armed only on success =================
    // The default FakePrefs context has no services, so copy_otp can never succeed there. ClipPrefs
    // makes copy_body/copy_otp real successes, so these fixtures can tell "ran" from "did nothing".

    @Test
    fun s4PartialSuccessArmsTheCooldown() {
        val p = ClipPrefs()
        // copy_body really copies; copy_otp has no code, so it is a silent no-op that must not count
        val r = AddonRule(id = "part", keywordContains = "کد", cooldownSeconds = 60,
            actions = listOf("copy_body", "copy_otp"))
        rules(p.ctx, r)
        val e = SmsEvent("123", "کد 42", 0L, 0, 7L, false, false, null, 0)
        assertEquals("only the action that ran counts", 1, AddonRules.executeAll(p.ctx, r, e))
        p.prefs.writes.clear()
        AddonBus.dispatch(p.ctx, e)
        assertTrue("a partly successful rule must arm", p.prefs.values.containsKey("addon_fired_part"))
        assertFalse("armed means blocked inside the window", AddonRules.allowedNow(p.ctx, r, System.currentTimeMillis()))
    }

    @Test
    fun s4AllFailedActionsKeepTheRetryWindowOpen() {
        val p = prefs(null)
        // no clipboard service and no code: copy_otp cannot do anything here
        val r = AddonRule(id = "dead", keywordContains = "کد", cooldownSeconds = 60,
            actions = listOf("copy_otp"))
        rules(p.ctx, r)
        val e = SmsEvent("123", "کد 42", 0L, 0, 7L, false, true, "1234", 2001)
        assertEquals(0, AddonRules.executeAll(p.ctx, r, e))
        AddonBus.dispatch(p.ctx, e)
        assertFalse("a total failure must not arm", p.values.containsKey("addon_fired_dead"))
        assertTrue("the next SMS must get a retry", AddonRules.allowedNow(p.ctx, r, System.currentTimeMillis()))
    }

    @Test
    fun s4CoolingRuleStaysBlockedAfterAFailedSweep() {
        val p = prefs(null)
        val r = AddonRule(id = "cool", keywordContains = "کد", cooldownSeconds = 3600,
            actions = listOf("copy_otp"))
        rules(p.ctx, r)
        val t0 = System.currentTimeMillis() - 1_000L
        AddonRules.markFired(p.ctx, r, t0)
        val e = SmsEvent("123", "کد 42", 0L, 0, 7L, false, true, "1234", 2001)
        AddonBus.dispatch(p.ctx, e)
        assertEquals("a failed sweep must not refresh or clear the window", t0, p.values["addon_fired_cool"])
        assertFalse("still cooling", AddonRules.allowedNow(p.ctx, r, System.currentTimeMillis()))
        assertTrue("window still ends when it should", AddonRules.allowedNow(p.ctx, r, t0 + 3_600_000L))
    }

    /**
     * FIXED in S4: executeAll counts real effects, not "did not throw". An action whose own guard is
     * false (copy_body on spam, unknown action name) leaves the cooldown unarmed, so the rule still
     * fires on the next SMS instead of blocking itself for the whole window.
     */
    @Test
    fun s4NoOpActionDoesNotCountAsSuccessAndDoesNotArm() {
        val p = ClipPrefs()
        val r = AddonRule(id = "noop", keywordContains = "کد", cooldownSeconds = 60,
            actions = listOf("copy_body", "not_a_real_action"))
        rules(p.ctx, r)
        val e = SmsEvent("123", "کد 42", 0L, 0, 7L, true, false, null, 0) // isSpam -> copy_body is a no-op
        assertEquals("neither action did anything", 0, AddonRules.executeAll(p.ctx, r, e))
        AddonBus.dispatch(p.ctx, e)
        assertFalse("a rule that did nothing must not arm", p.prefs.values.containsKey("addon_fired_noop"))
        assertTrue("the next SMS must retry", AddonRules.allowedNow(p.ctx, r, System.currentTimeMillis()))
    }

    @Test
    fun s4FailedRuleDoesNotStopTheNextRuleFromArming() {
        val p = ClipPrefs()
        rules(
            p.ctx,
            // no code on the event: copy_otp is a silent no-op, and the rule around it must not arm
            AddonRule(id = "bad", priority = 1, keywordContains = "کد", cooldownSeconds = 60, actions = listOf("copy_otp")),
            AddonRule(id = "good", priority = 2, keywordContains = "کد", cooldownSeconds = 60, actions = listOf("copy_body"))
        )
        AddonBus.dispatch(p.ctx, SmsEvent("123", "کد 42", 0L, 0, 0L, false, false, null, 0))
        assertFalse("failed rule stays unarmed", p.prefs.values.containsKey("addon_fired_bad"))
        assertTrue("the rule after it still fires", p.prefs.values.containsKey("addon_fired_good"))
    }

    // ================= S5: decision trace, clamp, notification id space, honest send =================

    /** FakePrefs with one injected fault, so the trace's "never into the receive path" claim is tested. */
    private class FaultPrefs(private val fail: String) {
        private val backing = FakePrefs()
        val values get() = backing.values
        val ctx: Context = object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                if (fail == "open") throw IllegalStateException("prefs unavailable")
                val real = backing.ctx.getSharedPreferences(name, mode)
                return Proxy.newProxyInstance(
                    FaultPrefs::class.java.classLoader,
                    arrayOf(SharedPreferences::class.java)
                ) { _, m, args ->
                    when (m.name) {
                        // read fault: every read throws, so even load()/matching() blow up
                        "getString" -> if (fail == "read") throw IllegalStateException("read fail")
                        else real.getString(args[0] as String, args.getOrNull(1) as? String)
                        "edit" -> if (fail != "write") real.edit() else {
                            val ed = real.edit()
                            Proxy.newProxyInstance(
                                FaultPrefs::class.java.classLoader,
                                arrayOf(SharedPreferences.Editor::class.java)
                            ) { _, m2, a2 ->
                                // only the trace key fails to persist; rules/cooldown still write
                                if (m2.name == "putString" && a2[0] == "addon_decisions") throw IllegalStateException("disk full")
                                if (a2 == null) m2.invoke(ed) else m2.invoke(ed, *a2)
                            } as SharedPreferences.Editor
                        }
                        else -> if (args == null) m.invoke(real) else m.invoke(real, *args)
                    }
                } as SharedPreferences
            }
        }
    }

    private fun decisions(p: FakePrefs) = AddonRules.recentDecisions(p.ctx)

    // ---------- S5.1 trace shape ----------

    @Test
    fun s5TraceCapsAtExactlyTwentyNewestFirst() {
        val p = FakePrefs()
        repeat(20) { AddonRules.recordDecision(p.ctx, "l$it") }
        assertEquals("20 entries is the cap, reached without loss", 20, decisions(p).size)
        assertEquals("l19", decisions(p).first())
        assertEquals("l0", decisions(p).last())

        repeat(5) { AddonRules.recordDecision(p.ctx, "n$it") }
        val after = decisions(p)
        assertEquals("never 21", AddonRules.DEC_MAX, after.size)
        assertEquals("newest first", "n4", after.first())
        assertEquals("oldest five dropped", "l5", after.last())
        assertFalse("dropped entries are really gone", after.contains("l4"))
    }

    @Test
    fun s5OneEntryPerSmsEvenWhenThreeRulesMatch() {
        val p = ClipPrefs()
        rules(
            p.ctx,
            AddonRule(id = "a", name = "Alpha", priority = 1, keywordContains = "کد", actions = listOf("copy_body")),
            AddonRule(id = "b", name = "Beta", priority = 2, keywordContains = "کد", actions = listOf("copy_body")),
            AddonRule(id = "c", name = "Gamma", priority = 3, keywordContains = "کد", actions = listOf("copy_body"))
        )
        AddonBus.dispatch(p.ctx, evT("کد 42"))
        val lines = decisions(p.prefs)
        assertEquals("3 rules, still ONE line", 1, lines.size)
        val line = lines.single()
        for (name in listOf("Alpha", "Beta", "Gamma")) assertTrue("names $name: $line", line.contains(name))

        // 21 SMS with 3 matching rules each -> still capped at 20 lines total
        repeat(20) { AddonBus.dispatch(p.ctx, evT("کد 42")) }
        assertEquals(AddonRules.DEC_MAX, decisions(p.prefs).size)
    }

    @Test
    fun s5MissNamesTheConditionThatMissed() {
        // keyword
        var p = prefs(null)
        rules(p.ctx, AddonRule(id = "k", name = "Kod", keywordContains = "پرداخت"))
        AddonBus.dispatch(p.ctx, evT("کد 42"))
        assertTrue("keyword label: ${decisions(p).single()}", decisions(p).single().contains("no rule (keyword)"))

        // sender
        p = prefs(null)
        rules(p.ctx, AddonRule(id = "s", senderContains = "9999"))
        AddonBus.dispatch(p.ctx, evT("anything"))
        assertTrue("sender label", decisions(p).single().contains("(sender)"))

        // length
        p = prefs(null)
        rules(p.ctx, AddonRule(id = "l", minLength = 999))
        AddonBus.dispatch(p.ctx, evT("short"))
        assertTrue("length label", decisions(p).single().contains("(length)"))

        // otp
        p = prefs(null)
        rules(p.ctx, AddonRule(id = "o", otpRequired = true))
        AddonBus.dispatch(p.ctx, SmsEvent("123", "hello", 0L, 0, 7L, false, false, null, 0))
        assertTrue("otp label", decisions(p).single().contains("(otp)"))

        // disabled / inert rules have their own labels
        p = prefs(null)
        rules(p.ctx, AddonRule(id = "off", keywordContains = "کد", enabled = false))
        AddonBus.dispatch(p.ctx, evT("کد 42"))
        assertTrue("disabled label", decisions(p).single().contains("(disabled)"))

        p = prefs(null)
        rules(p.ctx, AddonRule(id = "inert"))
        AddonBus.dispatch(p.ctx, evT("anything"))
        assertTrue("inert label: ${decisions(p).single()}", decisions(p).single().contains("(no conditions)"))
    }

    @Test
    fun s5CooldownBlockedAndActionFailedAreLabelled() {
        // cooldown
        var p = ClipPrefs()
        val cool = AddonRule(id = "cool", name = "Cool", keywordContains = "کد", actions = listOf("copy_body"), cooldownSeconds = 3600)
        rules(p.ctx, cool)
        AddonRules.markFired(p.ctx, cool, System.currentTimeMillis())
        AddonBus.dispatch(p.ctx, evT("کد 42"))
        assertTrue("cooldown label: ${decisions(p.prefs).single()}", decisions(p.prefs).single().contains("cooldown"))
        assertFalse("a blocked rule must not have run", decisions(p.prefs).single().contains("copy_body"))

        // action failed: no clipboard service, so copy_otp cannot do anything
        p = ClipPrefs()
        val dead = AddonRule(id = "dead", name = "Dead", keywordContains = "کد", actions = listOf("copy_otp"), cooldownSeconds = 60)
        rules(p.ctx, dead)
        AddonBus.dispatch(p.ctx, SmsEvent("123", "کد 42", 0L, 0, 7L, false, false, null, 0))
        val line = decisions(p.prefs).single()
        assertTrue("action failed label: $line", line.contains("nothing (action failed)"))
        assertFalse(p.prefs.values.containsKey("addon_fired_dead"))
    }

    /**
     * Documented gap (not gating): when one rule fires and another is cooling, the line reports only
     * what ran - the "cooldown" reason is dropped because `ran` is non-empty. Evidence for TEST-S5.md.
     */
    @Test
    fun s5MixedFireAndCooldownLineReportsOnlyWhatRan() {
        val p = ClipPrefs()
        val cool = AddonRule(id = "cool", name = "Cool", priority = 1, keywordContains = "کد", actions = listOf("copy_body"), cooldownSeconds = 3600)
        val hot = AddonRule(id = "hot", name = "Hot", priority = 2, keywordContains = "کد", actions = listOf("copy_body"), cooldownSeconds = 0)
        rules(p.ctx, cool, hot)
        AddonRules.markFired(p.ctx, cool, System.currentTimeMillis())
        AddonBus.dispatch(p.ctx, evT("کد 42"))
        val line = decisions(p.prefs).single()
        println("S5 mixed line: $line")
        assertTrue("names both rules: $line", line.contains("Cool") && line.contains("Hot"))
        assertTrue("reports the action that ran", line.contains("copy_body"))
        assertFalse("cooldown of the other rule is not mentioned", line.contains("cooldown"))
    }

    @Test
    fun s5ClearEmptiesTheTraceAndTheNextSmsStartsFresh() {
        val p = ClipPrefs()
        rules(p.ctx, AddonRule(id = "r", keywordContains = "کد", actions = listOf("copy_body")))
        AddonBus.dispatch(p.ctx, evT("کد 42"))
        assertFalse(decisions(p.prefs).isEmpty())
        AddonRules.clearDecisions(p.ctx)
        assertEquals(emptyList<String>(), decisions(p.prefs))
        assertFalse("key is removed, not blanked", p.prefs.values.containsKey("addon_decisions"))
        AddonBus.dispatch(p.ctx, evT("کد 43"))
        assertEquals("trace keeps working after Clear", 1, decisions(p.prefs).size)
    }

    // ---------- S5.2 truncation / unicode ----------

    @Test
    fun s5BodyPrefixIsFortyCharsForPersianAndNeverThrows() {
        val persian = "کد تخفیف ویژه فروشگاه برای شما " + "سلام ".repeat(20)
        val line = AddonRules.decisionLine(0L, ev(body = persian), emptyList(), emptyList(), "keyword")
        val prefix = line.substringAfter("· 123 · ").substringBefore(" → ")
        assertEquals("40 code units is the documented cap", 40, prefix.length)
        assertEquals(persian.take(40), prefix)
        // survives the JSON round trip that the real prefs do
        val p = FakePrefs()
        AddonRules.recordDecision(p.ctx, line)
        assertEquals(line, decisions(p).single())

        // newline in the body must not break the one-line-per-SMS layout
        val multi = AddonRules.decisionLine(0L, ev(body = "a\nb\nc"), emptyList(), emptyList(), "keyword")
        assertFalse("newlines are flattened: $multi", multi.contains('\n'))
    }

    /**
     * DEFECT (cosmetic, no crash): the 40-char cut is by UTF-16 unit, so an emoji straddling index 40
     * leaves a lone high surrogate in the pref and in the Features list. Asserting the real behaviour
     * so the suite stays honest; the fix is one line
     * (`take(40).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }`).
     */
    @Test
    fun s5EmojiAtTheCutLeavesALoneHighSurrogate() {
        val body = "a".repeat(39) + "😀" + "tail"
        val line = AddonRules.decisionLine(0L, ev(body = body), emptyList(), emptyList(), "keyword")
        val prefix = line.substringAfter("· 123 · ").substringBefore(" → ")
        println("S5 truncation: prefix len=${prefix.length} last=U+%04X".format(prefix.last().code))
        assertFalse("whole pair fits only if the cut moves", prefix.contains("😀"))
        assertTrue("split happened, not a crash", prefix.last().isHighSurrogate())
        // still persists and is readable afterwards
        val p = FakePrefs()
        AddonRules.recordDecision(p.ctx, line)
        assertEquals(1, decisions(p).size)
    }

    // ---------- S5.3 the trace must never throw into the receive path ----------

    @Test
    fun s5DispatchSurvivesPrefsThatCannotOpen() {
        withSentinel { sentinel ->
            val ctx = FaultPrefs("open").ctx
            try {
                AddonBus.dispatch(ctx, ev(body = "کد 42", otp = "1234"))
            } catch (t: Throwable) {
                throw AssertionError("dispatch threw into the receive path: ${t.javaClass.name}", t)
            }
            assertEquals("fail closed: no action might run when the rules cannot be read", 0, sentinel.calls)
        }
    }

    @Test
    fun s5DispatchSurvivesARuleSetThatCannotBeRead() {
        withSentinel { sentinel ->
            val ctx = FaultPrefs("read").ctx
            try {
                AddonBus.dispatch(ctx, ev(body = "کد 42", otp = "1234"))
            } catch (t: Throwable) {
                throw AssertionError("dispatch threw into the receive path: ${t.javaClass.name}", t)
            }
            assertEquals("fail closed, consistently: legacy actions are not run when matching throws", 0, sentinel.calls)
            // and the failed trace write is contained too: nothing was recorded, nothing blew up
            assertEquals(emptyList<String>(), AddonRules.recentDecisions(ctx))
        }
    }

    @Test
    fun s5TraceWriteFailureDoesNotStopTheActions() {
        withSentinel { sentinel ->
            val f = FaultPrefs("write")
            // no rules -> legacy path; the trace write for this SMS cannot persist
            AddonBus.dispatch(f.ctx, ev(body = "hello", otp = "1"))
            assertEquals("the trace write failing must not eat the SMS handling", 1, sentinel.calls)
            assertEquals("nothing recorded, as expected", emptyList<String>(), AddonRules.recentDecisions(f.ctx))
            assertFalse("and the key is not half-written", f.values.containsKey("addon_decisions"))
        }
    }

    /** Documented weakness: a corrupted trace value is never repaired, so the trace stays dead until Clear. */
    @Test
    fun s5CorruptTraceValueDisablesTheTraceUntilClear() {
        val p = FakePrefs()
        p.values["addon_decisions"] = "[not json"
        assertEquals("reads as empty instead of throwing", emptyList<String>(), decisions(p))
        AddonRules.recordDecision(p.ctx, "next")
        println("S5 corrupt trace after one record: ${p.values["addon_decisions"]}")
        assertEquals("no self-heal: still unreadable", emptyList<String>(), decisions(p))
        AddonRules.clearDecisions(p.ctx)
        AddonRules.recordDecision(p.ctx, "next")
        assertEquals(listOf("next"), decisions(p))
    }

    // ---------- S5.4 clampCooldown ----------

    @Test
    fun s5ClampCooldownTable() {
        val table = listOf(
            "" to 0,
            "abc" to 0,
            "آ" to 0,
            "-5" to 5,                       // sign is dropped, never a negative window
            "0" to 0,                        // 0 keeps its "every time" meaning
            "30" to 30,
            "  30  " to 30,
            "86400" to 86_400,
            "86401" to 86_400,
            "999999999" to 86_400,
            "999999999999" to 86_400,
            "9007199254740993" to 86_400,    // 2^53+1
            "2147483648" to 86_400,
            "8 6 4 0 1" to 86_400,
            "۶۰" to 60,                      // Persian digits
            "۱۲۳۴" to 1234,
            "٩٩٩٩٩٩٩٩٩" to 86_400          // Arabic-Indic 9 digits
        )
        for ((input, want) in table) {
            val got = AddonRules.clampCooldown(input)
            println("S5 clamp '$input' -> $got")
            assertEquals("input '$input'", want, got)
            assertTrue("input '$input' must stay in 0..${AddonRules.MAX_COOLDOWN}", got in 0..AddonRules.MAX_COOLDOWN)
        }
    }

    // ---------- S5.5 notification id space ----------

    @Test
    fun s5AddonIdsOccupySixDigitsAboveTheSmsIds() {
        val e = evT("کد 42", threadId = 1L)
        // thread path sweeps the whole window, so the range claim is checked exhaustively
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        val ids = HashSet<Int>(600_000)
        for (t in 1L..499_999L) {
            val id = AddonRules.addonNotifyId(e.copy(threadId = t))
            ids += id
            if (id < min) min = id
            if (id > max) max = id
        }
        assertEquals("one id per thread inside the window", 499_999, ids.size)
        assertEquals("lowest thread-path id", 1_000_001, min)
        assertEquals("highest addon id", 1_499_999, max)
        assertEquals("hash path can reach the window floor", 1_000_000, AddonRules.addonNotifyId(e.copy(threadId = 0L, sender = "")))
        // tail collision: only an exact 500k offset or a hash tie can fold back into the window
        assertEquals(AddonRules.addonNotifyId(e.copy(threadId = 7L)), AddonRules.addonNotifyId(e.copy(threadId = 500_007L)))
        // sender fallback stays inside the same window
        for (s in listOf("", "1", "09121234567", "Bank", "😀😀")) {
            val id = AddonRules.addonNotifyId(e.copy(threadId = 0L, sender = s))
            assertTrue("hash path for '$s' out of window: $id", id in 1_000_000..1_499_999)
        }
        assertTrue("the app's filter notification id is nowhere near it", e.notifyId < 1_000_000)
    }

    /**
     * The exact residual case, with a live reproduction: an SMS notification id is
     * 2000 + address.hashCode(), so it lands in the addon window iff hashCode is in
     * [998 000, 1 497 999]. "09857893333".hashCode() == 1 283 250 sits inside it, and an addon
     * notification for thread 285 250 gets exactly 1 285 250.
     */
    @Test
    fun s5CollisionNeedsASenderHashInsideTheWindow() {
        val lo = 1_000_000 - 2000
        val hi = 1_499_999 - 2000
        fun smsId(sender: String) = 2000 + sender.hashCode()

        for (s in listOf("09121234567", "09351234567", "+989121234567", "1000", "Bank", "AD", "90001234", "123")) {
            assertTrue("'$s' hash ${s.hashCode()} unexpectedly inside [$lo,$hi]", s.hashCode() !in lo..hi)
            assertTrue(smsId(s) !in 1_000_000..1_499_999)
        }

        val collidingSender = "09857893333"
        assertEquals(1_283_250, collidingSender.hashCode())
        assertEquals(1_285 250, smsId(collidingSender))
        assertEquals(
            "concrete collision: hash in the window + thread id = hash-998000",
            1_285 250,
            AddonRules.addonNotifyId(evT("x").copy(threadId = 285_250L))
        )
        // probability, stated as arithmetic: 500 000 / 2^32 per address, times the thread aliasing
        assertTrue("window is 500k wide", hi - lo + 1 == 500_000)
    }

    // ---------- S5.6 honest send (autoreply / forward) ----------

    private fun withSend(stub: (Context, String, String) -> Boolean, block: () -> Unit) {
        val real = AddonRules.send
        AddonRules.send = stub
        try { block() } finally { AddonRules.send = real }
    }

    @Test
    fun s5FailedAutoreplyIsNotSuccessAndDoesNotArm() {
        val p = ClipPrefs()
        val r = AddonRule(id = "ar", name = "Reply", keywordContains = "کد", action = "autoreply",
            actionParam = "got it", cooldownSeconds = 60)
        rules(p.ctx, r)
        val e = SmsEvent("09121234567", "کد 42", 0L, 0, 7L, false, true, "1234", 0)

        var sent: Pair<String, String>? = null
        withSend({ _, a, b -> sent = a to b; false }) {
            assertFalse("execute() must report the refused send", AddonRules.execute(p.ctx, r, e))
            assertEquals(0, AddonRules.executeAll(p.ctx, r, e))
            AddonBus.dispatch(p.ctx, e)
            assertEquals("the template went to the sender", "09121234567" to "got it", sent)
            assertFalse("a refused send must not arm the cooldown", p.prefs.values.containsKey("addon_fired_ar"))
            assertTrue("so the next SMS retries", AddonRules.allowedNow(p.ctx, r, System.currentTimeMillis()))
            assertTrue("and the trace says why: ${decisions(p.prefs).single()}", decisions(p.prefs).single().contains("action failed"))
        }

        withSend({ _, _, _ -> true }) {
            assertTrue("a real send is success", AddonRules.execute(p.ctx, r, e))
            AddonBus.dispatch(p.ctx, e)
            assertTrue("success arms", p.prefs.values.containsKey("addon_fired_ar"))
            assertFalse("armed means blocked now", AddonRules.allowedNow(p.ctx, r, System.currentTimeMillis()))
            val line = decisions(p.prefs).first()
            assertTrue("trace names the action: $line", line.contains("autoreply"))
        }
    }

    @Test
    fun s5FailedForwardIsNotSuccessAndDoesNotArm() {
        val p = ClipPrefs()
        val r = AddonRule(id = "fw", name = "Fwd", keywordContains = "کد", action = "forward",
            actionParam = "09120000000", cooldownSeconds = 60)
        rules(p.ctx, r)
        val e = SmsEvent("09121234567", "کد 42", 0L, 0, 7L, false, false, null, 0)

        var sent: Pair<String, String>? = null
        withSend({ _, a, b -> sent = a to b; false }) {
            assertFalse(AddonRules.execute(p.ctx, r, e))
            AddonBus.dispatch(p.ctx, e)
            assertEquals("the whole body is forwarded", "09120000000" to "کد 42", sent)
            assertFalse("failed forward must not arm", p.prefs.values.containsKey("addon_fired_fw"))
        }
        withSend({ _, _, _ -> true }) {
            assertTrue(AddonRules.execute(p.ctx, r, e))
            AddonBus.dispatch(p.ctx, e)
            assertTrue(p.prefs.values.containsKey("addon_fired_fw"))
        }
    }

    @Test
    fun s5GuardThatNeverCallsSendIsNotAFailedSend() {
        val p = ClipPrefs()
        var called = 0
        withSend({ _, _, _ -> called++; true }) {
            // spam: autoreply never reaches the seam, so it must not claim success either
            assertFalse(AddonRules.execute(p.ctx, AddonRule(action = "autoreply", actionParam = "x"),
                SmsEvent("1", "b", 0L, 0, 1L, true, true, null, 0)))
            // no recipient: forward with a blank param cannot be sent
            assertFalse(AddonRules.execute(p.ctx, AddonRule(action = "forward", actionParam = " "),
                SmsEvent("1", "b", 0L, 0, 1L, false, false, null, 0)))
            assertEquals("guard-outs never call the seam", 0, called)
        }
    }
}
