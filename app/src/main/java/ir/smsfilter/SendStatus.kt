package ir.smsfilter

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Send + delivery state for messages this app sends.
 *
 * Android has no "delivered" column for SMS: the carrier's delivery report is a PendingIntent
 * callback, not a provider row. So a sent copy is written with STATUS_PENDING, flipped to
 * STATUS_COMPLETE (or STATUS_FAILED) by the send callback, and marked delivered in our own prefs by
 * the delivery callback. Without this a send looked either invisible or always-successful.
 */
object SendStatus {
    const val ACTION_SENT = "ir.smsfilter.SENT"
    const val ACTION_DELIVERED = "ir.smsfilter.DELIVERED"
    private const val PREFS = "send_status"
    private const val KEY_DELIVERED = "delivered"

    fun markDelivered(ctx: Context, rowId: Long) {
        if (rowId <= 0) return
        val p = ctx.getSharedPreferences(PREFS, 0)
        val now = p.getStringSet(KEY_DELIVERED, emptySet()).orEmpty()
        if (rowId.toString() in now) return
        // bounded: reports for messages the user already deleted are noise
        val next = (now + rowId.toString()).let { if (it.size > 2000) it.drop(it.size - 2000).toSet() else it }
        p.edit().putStringSet(KEY_DELIVERED, next).apply()
    }

    fun delivered(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, 0).getStringSet(KEY_DELIVERED, emptySet()).orEmpty()
}

class SendStatusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val row = intent.getLongExtra("row", -1L)
        if (row <= 0) return
        when (intent.action) {
            SendStatus.ACTION_SENT -> {
                val ok = resultCode == Activity.RESULT_OK
                try {
                    val cv = ContentValues().apply {
                        put(Telephony.Sms.STATUS,
                            if (ok) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_FAILED)
                    }
                    ctx.contentResolver.update(
                        Telephony.Sms.CONTENT_URI, cv,
                        "${Telephony.Sms._ID}=?", arrayOf(row.toString())
                    )
                } catch (t: Throwable) {
                    android.util.Log.e("SMSFilter", "sent status update failed", t)
                }
            }
            SendStatus.ACTION_DELIVERED -> {
                if (resultCode == Activity.RESULT_OK) {
                    SendStatus.markDelivered(ctx, row)
                    // the provider update is what wakes this app's content observer, so the ✓✓
                    // appears without the user leaving the conversation
                    try {
                        ctx.contentResolver.update(
                            Telephony.Sms.CONTENT_URI,
                            ContentValues().apply { put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE) },
                            "${Telephony.Sms._ID}=?", arrayOf(row.toString())
                        )
                    } catch (t: Throwable) {
                    }
                }
            }
        }
    }
}
