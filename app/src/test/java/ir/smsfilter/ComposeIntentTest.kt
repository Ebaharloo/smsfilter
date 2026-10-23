package ir.smsfilter

import ir.smsfilter.core.ComposeIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address book, the dialer and browser links open the app with an `sms:` URI. Getting this
 * wrong meant "message this contact" landed on the inbox instead of a composer (or opened a second
 * conversation for a number we already had).
 */
class ComposeIntentTest {

    @Test
    fun parsesEveryMessageScheme() {
        assertEquals("+989121234567" to null, ComposeIntent.parse("smsto:+989121234567"))
        assertEquals("+989121234567" to null, ComposeIntent.parse("sms:+989121234567"))
        assertEquals("+989121234567" to null, ComposeIntent.parse("mms:+989121234567"))
        assertEquals("+989121234567" to null, ComposeIntent.parse("mmsto:+989121234567"))
        // the address book sometimes appends a semicolon
        assertEquals("+989121234567" to null, ComposeIntent.parse("smsto:+989121234567;"))
    }

    @Test
    fun readsPrefilledBodyAndDecodesIt() {
        assertEquals("+989121234567" to "hello", ComposeIntent.parse("smsto:+989121234567?body=hello"))
        assertEquals("+989121234567" to "سلام دنیا", ComposeIntent.parse("sms:+989121234567?body=%D8%B3%D9%84%D8%A7%D9%85%20%D8%AF%D9%86%DB%8C%D8%A7"))
        assertEquals("1234" to "a b", ComposeIntent.parse("smsto:1234?body=a%20b&subject=x"))
        assertEquals("1234" to "a b", ComposeIntent.parse("smsto:1234?body=a+b&subject=x"))
    }

    @Test
    fun ignoresLinksThatAreNotMessages() {
        assertNull(ComposeIntent.parse("https://example.com/x"))
        assertNull(ComposeIntent.parse("tel:+989121234567"))
        assertNull(ComposeIntent.parse(""))
    }

    @Test
    fun numberKeyCollapsesAllIranianFormsToTheSameConversation() {
        val national = "9121234567"
        assertEquals(national, ComposeIntent.numberKey("+989121234567"))
        assertEquals(national, ComposeIntent.numberKey("989121234567"))
        assertEquals(national, ComposeIntent.numberKey("00989121234567"))
        assertEquals(national, ComposeIntent.numberKey("09121234567"))
        assertEquals(national, ComposeIntent.numberKey("0912 123 4567"))
        assertTrue(ComposeIntent.sameNumber("+989121234567", "09121234567"))
        assertTrue(ComposeIntent.sameNumber("09121234567", "+989121234567"))
    }

    @Test
    fun shortCodesAreComparedAsWritten() {
        assertEquals("300017", ComposeIntent.numberKey("300017"))
        assertTrue(ComposeIntent.sameNumber("300017", "300017"))
        assertFalse(ComposeIntent.sameNumber("300017", "300018"))
    }

    /** a contact saved as 0912… must still be named when the address book hands us +98912… */
    @Test
    fun lookupVariantsCoverTheFormsTheAddressBookMightUse() {
        val v = ComposeIntent.lookupVariants("+989121234567")
        assertTrue("09121234567" in v)
        assertTrue("+989121234567" in v)
        assertEquals(v.size, v.distinct().size)
        // short codes are looked up exactly as written
        assertEquals(listOf("300017"), ComposeIntent.lookupVariants("300017"))
    }
}
