package ir.smsfilter

import android.content.Context
import android.provider.Telephony

/**
 * Retroactive pass over the SMS provider.
 *
 * Everything that arrived before this app held the SMS role - or that the model scored below its
 * spam threshold - is still sitting in the inbox. This walks the newest messages, classifies them
 * with the same pipeline the receive path uses, keeps a copy of every ad in our own database and
 * archives the conversations they belong to.
 *
 * Nothing is deleted: archiving is reversible, and the ad copies stay on the Ads page.
 */
object InboxScan {

    data class Result(val scanned: Int, val spam: Int, val threads: Int)

    fun run(
        ctx: Context,
        clf: Classifier,
        store: Store,
        limit: Int = 4000,
        onProgress: (Int) -> Unit = {}
    ): Result {
        val contacts = Contacts.suffixes(ctx)
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
                    if (scanned % 200 == 0) onProgress(scanned)
                }
            }
        } catch (t: Throwable) {
            // no permission / provider trouble: report what was done instead of throwing
        }

        threads.forEach { tid ->
            ThreadFlags.setArchived(ctx, tid, true)
            Sms.markThreadRead(ctx, tid)
        }
        return Result(scanned = scanned, spam = spam, threads = threads.size)
    }
}
