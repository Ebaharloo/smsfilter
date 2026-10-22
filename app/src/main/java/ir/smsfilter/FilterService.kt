package ir.smsfilter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Layer 1 capture: read the SMS notification, classify, cancel the notification if SPAM.
 * The message itself is never deleted - the quarantine copy is in our DB. Fail-open by design:
 * any exception => notification passes through untouched.
 */
class FilterService : NotificationListenerService() {

    private lateinit var clf: Classifier
    private lateinit var store: Store
    private var filtered = 0
    private var lastSmsDate = 0L
    private val contacts by lazy { Contacts.suffixes(this) }
    private val storedContacts = mutableSetOf<String>()   // provider fallback dedupe: one SMS, many notification updates

    override fun onListenerConnected() {
        super.onListenerConnected()
        android.util.Log.i("SMSFilter", "listener CONNECTED")
    }

    override fun onListenerDisconnected() {
        android.util.Log.i("SMSFilter", "listener DISCONNECTED")
        super.onListenerDisconnected()
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("SMSFilter", "service onCreate")
        clf = Classifier(RuleSet.load(this), Model.fromAssets(this), Learned(this))
        store = Store.get(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Filtered ads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in SMS_PACKAGES) {
            val e = sbn.notification.extras
            android.util.Log.i("SMSFilter", "sms notif from ${sbn.packageName} cat=${sbn.notification.category} " +
                "title=${e.getCharSequence(Notification.EXTRA_TITLE)}")
        }
        if (sbn.packageName !in SMS_PACKAGES) return
        if (sbn.isOngoing) return                                  // progress/foreground noise, not a message
        if (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        try {
            val n = sbn.notification
            val ex = n.extras
            // Google Messages does not put the sender in EXTRA_TITLE; the real fields live in
            // the MessagingStyle (person + text). Fall back to plain extras for other apps.
            // platform Notification.MessagingStyle has no public extractor; androidx does
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            val last = style?.messages?.lastOrNull()
            var smsId = 0L
            var sender = (last?.person?.name?.toString()
                ?: style?.conversationTitle?.toString()
                ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()).orEmpty()
            var text = (last?.text?.toString()
                ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()).orEmpty()
            val notifSender = sender
            // Android 15 redaction: the notification body arrives as "Sensitive notification
            // content hidden". Take the real text from the SMS provider instead.
            if (text.isBlank() || text.contains("Sensitive notification", ignoreCase = true) || notifSender.isBlank()) {
                val since = maxOf(lastSmsDate + 1, System.currentTimeMillis() - 20_000)
                val sms = SmsReader.latest(this, since) ?: return
                lastSmsDate = sms.date
                smsId = sms.id
                text = sms.body
                if (notifSender.isBlank()) sender = sms.address
            }
            if (text.isBlank() || sender.isBlank()) return
            if (n.category != null && n.category != Notification.CATEGORY_MESSAGE) return

            val v = clf.classify(text, sender, contacts)
            store.add(sender, text, v)

            val enabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("filter_on", true)
            if (v.label == Label.SPAM && enabled) {
                suppress(sbn.key)
                filtered++
                digest()
            }
        } catch (t: Throwable) {
            // fail open: never swallow a message because we broke
        }
    }

    /**
     * Cancel the spam conversation notification, then once more shortly after (the messenger
     * reposts on every conversation update). Never touch the group summary: it represents every
     * conversation, so cancelling it also hides important messages in the same group.
     */
    private fun suppress(key: String) {
        cancelNotification(key)
        android.os.Handler(mainLooper).postDelayed({ cancelNotification(key) }, 1200)
    }

    private fun digest() {
        if (filtered == 0) return
        val nm = getSystemService(NotificationManager::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("$filtered پیام تبلیغاتی فیلتر شد")
            .setContentText("برای مشاهده و بازگردانی ضربه بزنید")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(DIGEST_ID, n)
    }

    companion object {
        const val CHANNEL = "filtered"
        const val DIGEST_ID = 1001
        const val PREFS = "smsfilter"
        val SMS_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "com.textra"
        )
    }
}
