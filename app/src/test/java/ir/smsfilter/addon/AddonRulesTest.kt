package ir.smsfilter.addon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

}
