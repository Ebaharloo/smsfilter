package ir.smsfilter

import ir.smsfilter.core.InboxPolicy
import ir.smsfilter.core.Notify
import ir.smsfilter.core.SwipeAction
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
    fun suspectedAdConversationStaysVisible() {
        val rows = listOf(bank, ad, friend)
        // archived by the user: only in Archive
        assertEquals(listOf(2L), InboxPolicy.visible(rows, setOf(2), true).map { it.id })
        // a spam mark must never make a conversation invisible: the user has to be able to see it
        // and decide. The ad copy lives on the Ads page; the conversation stays in the inbox.
        assertEquals(listOf(1L, 2L, 3L), InboxPolicy.visible(rows, emptySet(), false, spamIds = setOf(2)).map { it.id })
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

    /** the tab names are the user's, not ours: renaming them once got the whole pass rejected */
    @Test
    fun tabLabelsAreFixed() {
        assertEquals(
            listOf("All", "Transactions", "Codes", "Promotions"),
            Tab.entries.map { it.label }
        )
    }

    /** a contact who writes «تخفیف» belongs to no category tab, but All still counts everyone */
    @Test
    fun countsSkipContactsInCategoriesButNotInAll() {
        val contact = row(4, "+989****8888", "تخفیف گرفتم برایت", 6_000)
        val contacts = setOf(InboxPolicy.suffix(contact.address))
        val counts = InboxPolicy.counts(listOf(bank, ad, friend, contact), contacts)

        assertEquals(4, counts[Tab.ALL])
        assertEquals(1, counts[Tab.TRANSACTIONS])
        assertEquals(1, counts[Tab.PROMOTIONS])   // the ad only: the contact's «تخفیف» is not counted
        assertEquals(0, counts[Tab.OTP] ?: 0)
        assertFalse(InboxPolicy.inTab(contact, Tab.PROMOTIONS, isContact = true))
        assertTrue(InboxPolicy.inTab(contact, Tab.ALL, isContact = true))
    }

    /** the swipe contract: 72dp arms the normal action, 60% of the row width arms the heavy one */
    @Test
    fun swipeThresholdsAreOrderedAndSymmetric() {
        val width = 1000f
        val trigger = 72f

        assertEquals(SwipeAction.NONE, InboxPolicy.swipeAction(0f, width, trigger))
        assertEquals(SwipeAction.NONE, InboxPolicy.swipeAction(-71f, width, trigger))
        assertEquals(SwipeAction.LEFT, InboxPolicy.swipeAction(-72f, width, trigger))
        assertEquals(SwipeAction.LEFT, InboxPolicy.swipeAction(-599f, width, trigger))
        assertEquals(SwipeAction.HEAVY, InboxPolicy.swipeAction(-600f, width, trigger))
        assertEquals(SwipeAction.HEAVY, InboxPolicy.swipeAction(-900f, width, trigger))
        assertEquals(SwipeAction.RIGHT, InboxPolicy.swipeAction(72f, width, trigger))
        // a wide row cannot make the heavy band narrower than the plain one
        assertEquals(SwipeAction.HEAVY, InboxPolicy.swipeAction(-500f, 500f, trigger))
    }

    @Test
    fun pinnedSortKeepsNewestFirstInsideEachGroup() {
        val older = row(10, "111", "a", 1_000)
        val newer = row(11, "222", "b", 3_000)
        val pinnedOld = row(12, "333", "c", 500)

        val ordered = InboxPolicy.order(listOf(older, newer, pinnedOld), pinnedIds = setOf(12))
        assertEquals(listOf(12L, 11L, 10L), ordered.map { it.id })
    }

    /** search must never resurrect an archived conversation, whatever the query says */
    @Test
    fun searchStaysInsideTheVisibleList() {
        val rows = listOf(bank, ad, friend)
        // an archived conversation is not searchable from the inbox...
        assertTrue(InboxPolicy.visible(rows, setOf(2), false, "OFF50").isEmpty())
        // ...but nothing else may hide it: a suspected ad stays findable in the inbox
        assertEquals(
            listOf(2L),
            InboxPolicy.visible(rows, emptySet(), false, "OFF50", spamIds = setOf(2)).map { it.id }
        )
        // the same query still finds the bank message where it is actually visible
        assertEquals(
            listOf(1L),
            InboxPolicy.visible(rows, emptySet(), false, "واریز").map { it.id }
        )
    }

    /**
     * The Ads page shipped left-to-right Persian previews while the inbox was right: every body in
     * the app must pick its paragraph direction from the same rule, so pin the rule down here.
     */
    @Test
    fun persianBodiesAreDetectedForRtlRendering() {
        assertTrue(isPersian("برگ‌ریزون قیمت‌های دیجی‌پی نزدیکه"))
        assertFalse(isPersian("OFF50 code 1234"))
        // latin-only bodies are left exactly as they are
        assertEquals("OFF50 20% off", InboxPolicy.forDisplay("OFF50 20% off"))
        // a Persian body keeps its amounts and links in one piece
        val ad = "تخفیف ویژه 50% کلیک کنید t.me/xyz"
        val out = InboxPolicy.forDisplay(ad)
        assertTrue(out.contains("\u206650\u2069"))
        assertTrue(out.contains("\u2066t.me/xyz\u2069"))
    }
}
