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
        // the whole provider by default: a window of a few thousand rows left the oldest messages
        // unclassified for ever, so ads the user had already seen kept sitting in the inbox
        limit: Int = Int.MAX_VALUE
    ): Result {
        val contacts = Contacts.suffixes(ctx)
        // senders the user restored by hand are never re-filtered, not even by a manual scan
        val allowed = Learned(ctx).allowed()
        val threads = HashSet<Long>()
        val doomed = ArrayList<Long>()
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
                    // a message the user already judged not-an-ad is never moved off the inbox
                    if (store.userLabelFor(body) == Label.IMPORTANT.name) continue

                    val verdict = try {
                        clf.classify(body, address, contacts)
                    } catch (t: Throwable) {
                        null
                    } ?: continue

                    if (verdict.label == Label.SPAM) {
                        spam++
                        if (threadId > 0) threads += threadId
                        // the copy is in our database, so the message can leave the inbox the same
                        // way a caught-on-arrival ad never enters it. insertWithOnConflict returns
                        // -1 instead of throwing, so the returned id proves nothing on its own -
                        // the row is read back before anything is deleted. Collected and deleted
                        // after the cursor closes: deleting mid-iteration breaks it.
                        val rowId = try {
                            store.add(address, body, verdict, date)
                        } catch (t: Throwable) {
                            -1L
                        }
                        if (rowId > 0 || store.hasCopy(address, body)) doomed += c.getLong(0)
                    }
                }
            }
        } catch (t: Throwable) {
            // no permission / provider trouble: report what was done instead of throwing
        }

        // Only rows whose copy definitely landed in our store are removed; anything that failed to
        // copy stays in the inbox. Restorable from the Ads page, so this is reversible, not lossy.
        doomed.forEach { id ->
            try {
                ctx.contentResolver.delete(
                    Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID}=?", arrayOf(id.toString())
                )
            } catch (t: Throwable) {
            }
        }

        // Spam rows leave the inbox only after their copy is stored, and the Ads page can put any of
        // them back. UNSURE never moves: it stays exactly where it is, for the user to judge.
        return Result(scanned = scanned, spam = spam, threads = threads.size)
    }

    /**
     * Re-judge everything already on the Ads page with the current rules and model.
     *
     * Rules improve, and a message filtered last week can be an order confirmation the filter no
     * longer hides. Instead of restoring hundreds of rows by hand, this re-runs the classifier over
     * the stored copies and moves the ones that are no longer spam back to the inbox.
     */
    fun recheck(ctx: Context, clf: Classifier, store: Store, limit: Int = Int.MAX_VALUE): Recheck {
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
            // a verdict the user made by hand is ground truth: re-check must never overwrite it
            if (store.userLabelFor(m.text) != null) return@forEach
            if (v.label == Label.SPAM) return@forEach
            store.relabelCopies(m.sender, m.text, Label.IMPORTANT)
            Sms.insertInbox(ctx, m.sender, m.text, m.ts, -1)
            Sms.threadIdFor(ctx, m.sender)?.let { tid -> threads += tid }
            restored++
        }
        ThreadFlags.restore(ctx, threads)
        return Recheck(rows.size, restored, threads.size)
    }

    /**
     * Recover labels from the conversations the user quarantined in earlier builds.
     *
     * Those taps only set a thread flag - no per-message verdict was stored - but the messages still
     * exist in the provider. This walks the flagged threads, records a SPAM label for every message
     * and reports how much triage it recovered. It runs only when the user asks for it: those marks
     * are his, not the current classifier's.
     */
    fun importOldMarksAsLabels(ctx: Context, store: Store): Int {
        // Senders that are never ads: the civil registry, the missed-call service and the shop's
        // order-status line. The user's correction stands - those messages are IMPORTANT, so they are
        // imported as positive examples instead of being skipped (a skip teaches nothing).
        val importantSenders = setOf("CRA.ir", "MissedCalls", "9820002505")
        var spam = 0
        var important = 0
        ThreadFlags.spamThreads(ctx).forEach { tid ->
            val msgs = Sms.messages(ctx, tid, window = 2000)
            if (msgs.isEmpty()) return@forEach
            val sender = msgs.last().address
            val label = if (sender in importantSenders) Label.IMPORTANT else Label.SPAM
            store.recordLabels(sender, msgs.map { it.body }, label)
            if (label == Label.SPAM) spam += msgs.size else important += msgs.size
        }
        return spam + important
    }
}
