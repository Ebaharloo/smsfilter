package ir.smsfilter.addon

import android.content.Context
import android.util.Log
import java.util.Calendar

object FeatureSelfTest {
    private const val TAG = "AddonSelfTest"

    fun run(ctx: Context): String {
        val out = mutableListOf<String>()
        val now = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun check(name: String, ok: Boolean) {
            val line = if (ok) "PASS $name" else "FAIL $name"
            out += line
            Log.d(TAG, line)
        }

        // Condition matrix
        check("empty conditions", AddonRules.matches(AddonRule(enabled = true), ev(), now))
        check("disabled", !AddonRules.matches(AddonRule(enabled = false), ev(), now))
        check("sender contains", AddonRules.matches(AddonRule(senderContains = "123"), ev(sender = "x123y"), now))
        check("sender miss", !AddonRules.matches(AddonRule(senderContains = "123"), ev(sender = "999"), now))
        check("keyword contains", AddonRules.matches(AddonRule(keywordContains = "otp"), ev(body = "your otp code"), now))
        check("keyword miss", !AddonRules.matches(AddonRule(keywordContains = "otp"), ev(body = "hello"), now))
        check("contact only true", AddonRules.matches(AddonRule(onlyFromContacts = true), ev(fromContact = true), now))
        check("contact only false", !AddonRules.matches(AddonRule(onlyFromContacts = true), ev(fromContact = false), now))
        check("spam only true", AddonRules.matches(AddonRule(onlySpam = true), ev(isSpam = true), now))
        check("spam only false", !AddonRules.matches(AddonRule(onlySpam = true), ev(isSpam = false), now))
        check("not spam only true", AddonRules.matches(AddonRule(onlyNotSpam = true), ev(isSpam = false), now))
        check("not spam only false", !AddonRules.matches(AddonRule(onlyNotSpam = true), ev(isSpam = true), now))
        check("otp required true", AddonRules.matches(AddonRule(otpRequired = true), ev(otp = "42"), now))
        check("otp required false", !AddonRules.matches(AddonRule(otpRequired = true), ev(otp = null), now))
        check("no otp true", AddonRules.matches(AddonRule(otpRequired = false), ev(otp = null), now))
        check("no otp false", !AddonRules.matches(AddonRule(otpRequired = false), ev(otp = "42"), now))
        check("min length true", AddonRules.matches(AddonRule(minLength = 5), ev(body = "12345"), now))
        check("min length false", !AddonRules.matches(AddonRule(minLength = 5), ev(body = "12"), now))
        check("max length true", AddonRules.matches(AddonRule(maxLength = 5), ev(body = "12345"), now))
        check("max length false", !AddonRules.matches(AddonRule(maxLength = 5), ev(body = "123456"), now))
        check("match all", AddonRules.matches(AddonRule(senderContains = "123", onlyFromContacts = true), ev(sender = "123", fromContact = true), now))

        // Action dry-run (no side effects)
        val e = ev(sender = "123", body = "code 42", fromContact = true, otp = "42")
        val actions = listOf(
            "copy_otp", "copy_body", "autoreply", "forward", "mark_read", "mute", "unmute",
            "pin", "unpin", "archive", "unarchive", "quarantine", "restore", "block_sender",
            "dismiss", "modify_text", "alert", "email", "reply_blank"
        )
        for (a in actions) {
            try {
                AddonRules.execute(ctx, AddonRule(action = a, actionParam = "x"), e, dryRun = true)
                check("action $a", true)
            } catch (t: Throwable) {
                check("action $a", false)
                Log.e(TAG, "action $a failed", t)
            }
        }

        val failed = out.count { it.startsWith("FAIL") }
        out += if (failed == 0) "ALL TESTS PASSED (${out.size})" else "$failed FAILED"
        return out.joinToString("\n")
    }

    private fun ev(
        sender: String = "123",
        body: String = "hello",
        fromContact: Boolean = false,
        isSpam: Boolean = false,
        otp: String? = null
    ) = SmsEvent(sender, body, 0L, 0, 1L, isSpam, fromContact, otp, 0)
}