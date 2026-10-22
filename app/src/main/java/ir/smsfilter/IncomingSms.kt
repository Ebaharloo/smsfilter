package ir.smsfilter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.app.NotificationCompat

/**
 * Receives SMS because we are the default SMS app. As default, the system no longer writes incoming
 * messages for us - this receiver must persist them, so every failure path still inserts the message
 * before doing anything else (fail-safe, not fail-open).
 *
 * Ads are the one exception: they are kept in our own quarantine store and NOT written to the inbox,
 * which is what makes the messenger list clean.
 */
class IncomingSms : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REPLY -> { handleReply(ctx, intent); return }
            ACTION_MARK_READ -> {
                Sms.markThreadRead(ctx, intent.getLongExtra("thread", -1L))
                ctx.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("id", 0))
                return
            }
            ACTION_SCAN -> {
                // manual/scripted sweep of what is already in the inbox (adb: am broadcast -a ir.smsfilter.SCAN)
                val pending = goAsync()
                Thread {
                    try {
                        val res = InboxScan.run(
                            ctx,
                            Classifier(RuleSet.load(ctx), Model.fromAssets(ctx), Learned(ctx)),
                            Store(ctx)
                        )
                        android.util.Log.i("SMSFilter", "scan done $res")
                    } catch (t: Throwable) {
                        android.util.Log.e("SMSFilter", "scan failed", t)
                    } finally {
                        pending.finish()
                    }
                }.start()
                return
            }
            Telephony.Sms.Intents.SMS_DELIVER_ACTION -> Unit
            else -> return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val address = messages.firstOrNull()?.originatingAddress?.trim().orEmpty()
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val date = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        if (body.isBlank()) return

        val prefs = ctx.getSharedPreferences(FilterService.PREFS, Context.MODE_PRIVATE)
        val filtering = prefs.getBoolean("filter_on", true)

        var verdict: Verdict? = null
        try {
            val contacts = Contacts.suffixes(ctx)
            val clf = Classifier(RuleSet.load(ctx), Model.fromAssets(ctx), Learned(ctx))
            verdict = clf.classify(body, address, contacts)
        } catch (t: Throwable) {
            verdict = null
        }

        // blocked senders and blocked keywords are spam regardless of content
        val blocked = Blocked.isBlocked(ctx, address) || Blocked.matchesKeyword(ctx, body)
        if (blocked && verdict?.label != Label.SPAM) {
            // record it as spam so the Ads page can explain why it disappeared
            verdict = Verdict(Label.SPAM, 99, listOf("blocked"), verdict?.norm ?: body)
        }
        val isSpam = filtering && (blocked || verdict?.label == Label.SPAM)

        var stored = -1L
        if (!isSpam) {
            stored = Sms.insertInbox(ctx, address, body, date)
        }
        if (!isSpam && stored == -1L) {
            // provider refused (role lost mid-flight) - keep our own copy so nothing vanishes
            try {
                Store(ctx).add(address, body, verdict ?: Verdict(Label.IMPORTANT, 0, listOf("no-classifier"), body), date)
            } catch (t: Throwable) {
            }
        }
        try {
            if (verdict != null) Store(ctx).add(address, body, verdict, date)
        } catch (t: Throwable) {
        }

        val notifyIdThread = Sms.threadIdFor(ctx, address) ?: -1L
        val muted = ThreadFlags.isMuted(ctx, notifyIdThread)
        // spam/mute policy is pure Kotlin so it is covered by unit tests
        val decision = ir.smsfilter.core.InboxPolicy.notifyFor(muted, filtering && (blocked || verdict?.label == Label.SPAM))

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(ADS_CHANNEL, "Filtered ads", NotificationManager.IMPORTANCE_LOW))

        if (decision == ir.smsfilter.core.Notify.DIGEST) {
            // one low-priority digest, updated in place - 50 ads/hour must not mean 50 notifications
            val digest = ctx.getSharedPreferences("digest", Context.MODE_PRIVATE)
            val count = digest.getInt("count", 0) + 1
            digest.edit().putInt("count", count).putString("last", body).apply()
            nm.notify(ADS_ID, NotificationCompat.Builder(ctx, ADS_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(ir.smsfilter.core.InboxPolicy.digestTitle(count))
                .setContentText(body.take(60))
                .setContentIntent(open(ctx, null))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build())
            return
        }

        if (decision == ir.smsfilter.core.Notify.SILENT) {
            // conversation muted by the user: message is stored, notification suppressed
            return
        }

        val notifyId = NOTIF_ID + address.hashCode()
        val threadId = Sms.threadIdFor(ctx, address) ?: -1L
        val reply = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
        val replyIntent = PendingIntent.getBroadcast(
            ctx, 10, Intent(ctx, IncomingSms::class.java).apply {
                action = ACTION_REPLY
                putExtra("address", address)
                putExtra("id", notifyId)
            }, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val readIntent = PendingIntent.getBroadcast(
            ctx, 11, Intent(ctx, IncomingSms::class.java).apply {
                action = ACTION_MARK_READ
                putExtra("thread", threadId)
                putExtra("id", notifyId)
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (address.isBlank()) "پیام جدید" else address)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open(ctx, address))
            .setAutoCancel(true)
            .addAction(0, "Reply", replyIntent)
            .addAction(0, "Mark read", readIntent)

        // OTP codes are the one thing worth a one-tap action
        otpOf(body)?.let { code ->
            val copyIntent = PendingIntent.getBroadcast(
                ctx, 12, Intent(ctx, IncomingSms::class.java).apply {
                    action = ACTION_COPY_OTP
                    putExtra("code", code)
                }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "Copy code $code", copyIntent)
        }

        nm.notify(notifyId, builder.build())
    }

    private fun handleReply(ctx: Context, intent: Intent) {
        val address = intent.getStringExtra("address") ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString() ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(intent.getIntExtra("id", 0))
        if (text.isNotBlank()) Sms.send(ctx, address, text)
    }

    private fun open(ctx: Context, address: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (address != null) putExtra("address", address)
        }
        return PendingIntent.getActivity(
            ctx, address?.hashCode() ?: 0, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        const val CHANNEL = "messages"
        const val ADS_CHANNEL = "filtered"
        const val NOTIF_ID = 2000
        const val ADS_ID = 2001
        const val KEY_REPLY = "reply_text"
        const val ACTION_SCAN = "ir.smsfilter.SCAN"
        const val ACTION_REPLY = "ir.smsfilter.REPLY"
        const val ACTION_MARK_READ = "ir.smsfilter.MARK_READ"
        const val ACTION_COPY_OTP = "ir.smsfilter.COPY_OTP"

        fun otpOf(body: String): String? = ir.smsfilter.core.InboxPolicy.otp(body)
    }
}

/** copy-code action from the notification */
class OtpCopyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != IncomingSms.ACTION_COPY_OTP) return
        val code = intent.getStringExtra("code") ?: return
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("code", code))
        android.widget.Toast.makeText(ctx, "کد $code کپی شد", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** sender block list, stored locally; blocked senders bypass the classifier entirely */
object Blocked {
    private const val FILE = "blocked"

    fun list(ctx: Context): Set<String> =
        ctx.getSharedPreferences(FILE, 0).getStringSet("numbers", emptySet())?.toSet().orEmpty()

    /** keyword blocking: any message containing one of these is dropped like a blocked sender */
    fun keywords(ctx: Context): Set<String> =
        ctx.getSharedPreferences(FILE, 0).getStringSet("keywords", emptySet())?.toSet().orEmpty()

    fun matchesKeyword(ctx: Context, body: String): Boolean {
        val text = body.lowercase()
        return keywords(ctx).any { it.isNotBlank() && text.contains(it.lowercase()) }
    }

    fun addKeyword(ctx: Context, keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        ctx.getSharedPreferences(FILE, 0).edit().putStringSet("keywords", keywords(ctx) + k).apply()
    }

    fun removeKeyword(ctx: Context, keyword: String) {
        ctx.getSharedPreferences(FILE, 0).edit().putStringSet("keywords", keywords(ctx) - keyword).apply()
    }

    fun isBlocked(ctx: Context, address: String): Boolean {
        val digits = Contacts.digits(address)
        if (digits.isEmpty()) return false
        return list(ctx).any { it == address || (Contacts.digits(it).takeLast(9) == digits.takeLast(9)) }
    }

    fun add(ctx: Context, address: String) {
        val prefs = ctx.getSharedPreferences(FILE, 0)
        prefs.edit().putStringSet("numbers", list(ctx) + address).apply()
    }

    fun remove(ctx: Context, address: String) {
        val prefs = ctx.getSharedPreferences(FILE, 0)
        prefs.edit().putStringSet("numbers", list(ctx) - address).apply()
    }
}
