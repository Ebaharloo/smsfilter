package ir.smsfilter

import android.content.Context
import android.provider.Telephony

/** Latest SMS from the provider. Needed because Android 15 hides notification text from us. */
object SmsReader {

    data class Sms(val address: String, val body: String, val date: Long, val id: Long = 0)

    /** Full inbox, newest first - used to test the filter against history. */
    fun scan(ctx: Context, limit: Int = 1000): List<Sms> {
        val out = ArrayList<Sms>(limit)
        val proj = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, proj, null, null, "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out += Sms(c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3), c.getLong(0))
            }
        }
        return out
    }

    fun latest(ctx: Context, sinceMs: Long = System.currentTimeMillis() - 20_000): Sms? {
        val proj = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, proj,
            "${Telephony.Sms.DATE} >= ?", arrayOf(sinceMs.toString()),
            "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            if (c.moveToFirst()) {
                return Sms(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getLong(2))
            }
        }
        return null
    }
}
