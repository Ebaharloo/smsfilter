package ir.smsfilter

import ir.smsfilter.core.InboxPolicy
import ir.smsfilter.core.Notify
import ir.smsfilter.core.Tab
import ir.smsfilter.core.ThreadRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression net for the inbox rules. Every new messenger feature must keep these green:
 * they encode the promises the filter makes (ads hidden, important shown, nothing lost).
 */
class InboxPolicyTest {

    private fun row(id: Long, address: String, snippet: String, date: Long, unread: Int = 0) =
        ThreadRow(id, address, snippet, date, unread, outgoing = false)

    private val bank = row(1, "300017", "بانک ملی: واریز 12,000,000 ریال مانده 60,000,000", 5_000)
    private val ad = row(2, "9830009999", "تخفیف ویژه حراج کد تخفیف OFF50", 9_000)
    private val friend = row(3, "+989121234567", "سلام، فردا میبینمت", 7_000, unread = 2)

    @Test
    fun tabsSeparateCodesTransactionsAndPromotions() {
        // codes win over money words, so an OTP that quotes an amount is never filed as a transaction
        assertEquals(Tab.OTP, InboxPolicy.categoryOf("رمز پویا: 447711 مبلغ 3,100,000 ریال"))
        assertEquals(Tab.TRANSACTIONS, InboxPolicy.categoryOf("بانک ملی: واریز 12,000,000 ریال مانده 60,000,000"))
        assertEquals(Tab.PROMOTIONS, InboxPolicy.categoryOf("تخفیف ویژه حراج کد تخفیف OFF50"))
        // «کد تخفیف» must not be mistaken for a verification code
        assertEquals(Tab.PROMOTIONS, InboxPolicy.categoryOf("کد تخفیف OFF50 را وارد کنید"))
        assertEquals(Tab.ALL, InboxPolicy.categoryOf("سلام، فردا میبینمت"))
    }

    @Test
    fun tabFilterAndCountsAgree() {
        val rows = listOf(bank, ad, friend)
        assertEquals(listOf(1L), rows.filter { InboxPolicy.inTab(it, Tab.TRANSACTIONS) }.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), rows.filter { InboxPolicy.inTab(it, Tab.ALL) }.map { it.id })
        assertEquals(3, rows.count { InboxPolicy.inTab(it, Tab.ALL) })
        assertEquals(3, InboxPolicy.counts(rows)[Tab.ALL])
        assertEquals(0, InboxPolicy.counts(rows)[Tab.OTP] ?: 0)
    }

    @Test
    fun archivedConversationsLeaveTheInbox() {
        val inbox = InboxPolicy.visible(listOf(bank, ad, friend), archivedIds = setOf(2), archivedMode = false)
        assertEquals(listOf(1L, 3L), inbox.map { it.id })

        val archive = InboxPolicy.visible(listOf(bank, ad, friend), archivedIds = setOf(2), archivedMode = true)
        assertEquals(listOf(2L), archive.map { it.id })
    }

    @Test
    fun searchMatchesNumberBodyAndContactName() {
        val rows = listOf(bank, ad, friend)
        val names = mapOf("+989121234567" to "Ali")

        assertEquals(listOf(1L), InboxPolicy.visible(rows, emptySet(), false, "300017").map { it.id })
        assertEquals(listOf(2L), InboxPolicy.visible(rows, emptySet(), false, "OFF50").map { it.id })
        assertEquals(listOf(3L), InboxPolicy.visible(rows, emptySet(), false, "ali", names).map { it.id })
        assertTrue(InboxPolicy.visible(rows, emptySet(), false, "zzz").isEmpty())
    }

    @Test
    fun pinnedConversationsSortFirstAndRestStayNewestFirst() {
        val ordered = InboxPolicy.order(listOf(bank, ad, friend), pinnedIds = setOf(1))
        assertEquals(listOf(1L, 2L, 3L), ordered.map { it.id })
    }

    @Test
    fun spamNeverNotifiesEvenWhenConversationIsMuted() {
        assertEquals(Notify.DIGEST, InboxPolicy.notifyFor(muted = true, spam = true))
        assertEquals(Notify.DIGEST, InboxPolicy.notifyFor(muted = false, spam = true))
        assertEquals(Notify.SILENT, InboxPolicy.notifyFor(muted = true, spam = false))
        assertEquals(Notify.NORMAL, InboxPolicy.notifyFor(muted = false, spam = false))
    }

    @Test
    fun otpIsFoundOnlyWhenMessageTalksAboutACode() {
        assertEquals("927809", InboxPolicy.otp("رمز 927809"))
        assertEquals("447711", InboxPolicy.otp("رمز پویا: 447711 مبلغ 3,100,000 ریال دیجی کالا"))
        assertEquals("7419", InboxPolicy.otp("کد تایید : 7419"))
        assertNull(InboxPolicy.otp("برداشت 1,250,000 ریال از حساب"))
        assertNull(InboxPolicy.otp("سلام، کدوم ساعت میای؟"))
        assertNull(InboxPolicy.otp("کد تخفیف OFF50 را وارد کنید"))
    }

    @Test
    fun pinnedBannerShowsNewestPinStillPresent() {
        // pins 7 and 9, but 9 was deleted: banner falls back to 7
        assertEquals(7L, InboxPolicy.pinnedBanner(setOf(7, 9), listOf(3, 5, 7)))
        // both alive: the newest one wins
        assertEquals(9L, InboxPolicy.pinnedBanner(setOf(7, 9), listOf(3, 7, 9)))
        // nothing pinned or everything deleted: no banner
        assertNull(InboxPolicy.pinnedBanner(emptySet(), listOf(3, 7)))
        assertNull(InboxPolicy.pinnedBanner(setOf(4), listOf(3, 7)))
    }

    @Test
    fun displayTextStripsSenderBidiMarksAndIsolatesLtrRuns() {
        // the real Saman Bank body: it embeds LRE/PDF around the card number
        val raw = "بانك سامان\nبرداشت مبلغ 1,234,567 انتقال وجه\nاز \u202A1111-111-1111111-1\u202C"
        val out = InboxPolicy.forDisplay(raw)

        assertFalse(out.any { it == '\u061C' || it in '\u200E'..'\u200F' || it in '\u202A'..'\u202E' })
        assertTrue(out.contains("\u20661,234,567\u2069"))
        assertTrue(out.contains("\u20661111-111-1111111-1\u2069"))
        // pure latin text is left alone
        assertEquals("plain 123", InboxPolicy.forDisplay("plain 123"))
    }

    @Test
    fun digestTitleCountsFilteredAds() {
        assertEquals("5 تبلیغ فیلتر شد", InboxPolicy.digestTitle(5))
    }

    @Test
    fun linkAndPhoneDetectionIgnorePlainText() {
        assertTrue(InboxPolicy.URL.containsMatchIn("کلیک کنید https://b2n.ir/abc"))
        assertTrue(InboxPolicy.URL.containsMatchIn("t.me/xyz"))
        assertTrue(!InboxPolicy.URL.containsMatchIn("بانک سامان برداشت 1,000,000"))
        assertTrue(InboxPolicy.PHONE.containsMatchIn("تماس با 09121234567"))
        assertTrue(!InboxPolicy.PHONE.containsMatchIn("مانده 200,000 ریال"))
    }
}
