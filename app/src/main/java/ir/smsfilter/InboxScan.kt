package ir.smsfilter

import android.content.Context
import android.provider.Telephony

/**
 * Retroactive pass over the SMS provider.
 *
 * Everything that arrived before this app held the SMS role - or that the model scored below its
 * spam threshold - is still sitting in the inbox. This walks the newest messages, classifies them
 * with the same pipeline the receive path uses, keeps a copy of every ad in our own database and
 * files the conversations they belong to under Ads.
 *
 * Nothing is deleted: the flags are reversible, and the ad copies stay on the Ads page.
 */
object InboxScan {

    data class Result(val scanned: Int, val spam: Int, val threads: Int)

    data class Recheck(val scanned: Int, val restored: Int, val threads: Int)

    fun run(
        ctx: Context,
        clf: Classifier,
        store: Store,
        limit: Int = 4000
    ): Result {
        val contacts = Contacts.suffixes(ctx)
        // senders the user restored by hand are never re-filtered, not even by a manual scan
        val allowed = Learned(ctx).allowed()
        val threads = HashSet<Long>()
        var scanned = 0
        var spam = 0

        val proj = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY, Telephony.Sms.DATE
        )
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, proj, null, null, "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && scanned < limit) {
                    scanned++
                    val threadId = c.getLong(1)
                    val address = c.getString(2).orEmpty()
                    val body = c.getString(3).orEmpty()
                    val date = c.getLong(4)
                    if (body.isBlank()) continue

                    val suffix = Contacts.digits(address).takeLast(9)
                    // a saved contact is protected from content filtering, same as the live path
                    if (suffix.length == 9 && suffix in contacts) continue
                    if (address in allowed) continue

                    val verdict = try {
                        clf.classify(body, address, contacts)
                    } catch (t: Throwable) {
                        null
                    } ?: continue

                    if (verdict.label == Label.SPAM) {
                        spam++
                        if (threadId > 0) threads += threadId
                        try {
                            store.add(address, body, verdict, date)
                        } catch (t: Throwable) {
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // no permission / provider trouble: report what was done instead of throwing
        }

        // Copies only: the scan never hides or removes a conversation. The user decides on the Ads
        // page; anything the filter was unsure about stays exactly where it is, in the inbox.
        return Result(scanned = scanned, spam = spam, threads = threads.size)
    }

    /**
     * Re-judge everything already on the Ads page with the current rules and model.
     *
     * Rules improve, and a message filtered last week can be an order confirmation the filter no
     * longer hides. Instead of restoring hundreds of rows by hand, this re-runs the classifier over
     * the stored copies and moves the ones that are no longer spam back to the inbox.
     */
    fun recheck(ctx: Context, clf: Classifier, store: Store, limit: Int = 3000): Recheck {
        val contacts = Contacts.suffixes(ctx)
        val rows = try {
            store.list(Label.SPAM, limit)
        } catch (t: Throwable) {
            return Recheck(0, 0, 0)
        }
        val threads = HashSet<Long>()
        var restored = 0
        rows.forEach { m ->
            val v = try {
                clf.classify(m.text, m.sender, contacts)
            } catch (t: Throwable) {
                null
            } ?: return@forEach
            if (v.label == Label.SPAM) return@forEach
            store.relabelCopies(m.sender, m.text, Label.IMPORTANT)
            Sms.insertInbox(ctx, m.sender, m.text, m.ts, -1)
            Sms.threadIdFor(ctx, m.sender)?.let { tid -> threads += tid }
            restored++
        }
        ThreadFlags.restore(ctx, threads)
        return Recheck(rows.size, restored, threads.size)
    }
}
