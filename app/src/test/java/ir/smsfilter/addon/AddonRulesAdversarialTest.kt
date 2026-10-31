package ir.smsfilter.addon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial coverage for S1 (Persian normalization in AddonRules.matches).
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
        // Plan S3 makes zero-condition rules inert; until then this stays true.
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
}
