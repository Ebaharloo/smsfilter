package ir.smsfilter

import android.content.Context

/**
 * The two gestures that teach the filter, kept in one place because five UI paths use them.
 *
 * Neither ever destroys a message: "this is an ad" keeps a copy in our own database and moves the
 * conversation to the Ads page; "not an ad" writes the message back into the inbox and clears both
 * the spam mark and the archive flag (clearing only one left the conversation in Archive).
 */
object AdActions {

    /** keep the copy and teach the words; the conversation is never hidden by a flag */
    fun markAd(
        ctx: Context,
        store: Store?,
        learned: Learned?,
        sender: String,
        body: String,
        ts: Long = System.currentTimeMillis(),
        threadId: Long = 0L
    ) {
        if (body.isNotBlank()) learned?.markAd(sender, body)
        store?.add(sender, body, Verdict(Label.SPAM, 99, listOf("user:ad"), body), ts)
        // the user's decision is the training data: record it where a delete or a re-check cannot
        // erase it
        store?.recordLabel(sender, body, Label.SPAM, ts)
        val tid = if (threadId > 0) threadId else Sms.threadIdFor(ctx, sender) ?: 0L
        // an earlier version flagged the thread so it vanished from Inbox AND Archive - a hidden
        // middle place the user could not find. Ads are recorded on the Ads page instead.
        if (tid > 0) ThreadFlags.unmarkSpam(ctx, tid)
    }

    /**
     * "send this conversation to the Ads page": every message is copied to our database *before*
     * the conversation leaves the inbox.
     *
     * Keeping only the newest message (what this used to do) was data loss: the provider rows were
     * deleted a line later, so every other message in the conversation was gone before it could be
     * labelled or classified. The copy is the only surviving record, therefore it is the whole
     * conversation, and every message becomes a training label.
     */
    fun moveConversationToAds(ctx: Context, store: Store?, learned: Learned?, threadId: Long): Int {
        if (threadId <= 0) return 0
        val msgs = Sms.messages(ctx, threadId, window = Int.MAX_VALUE)
        if (msgs.isEmpty()) return 0
        val sender = msgs.lastOrNull { it.incoming }?.address?.takeIf { it.isNotBlank() }
            ?: msgs.first().address
        var kept = 0
        msgs.forEach { m ->
            if (m.body.isBlank()) return@forEach
            store?.add(sender, m.body, Verdict(Label.SPAM, 99, listOf("user:ad"), m.body), m.date)
            store?.recordLabel(sender, m.body, Label.SPAM, m.date)
            kept++
        }
        learned?.markAd(sender, msgs.lastOrNull { it.incoming }?.body.orEmpty())
        ThreadFlags.unmarkSpam(ctx, threadId)
        Sms.deleteThread(ctx, threadId)
        return kept
    }

    /**
     * "not an ad": back into the inbox for good.
     *
     * Three things have to happen or the message returns to the Ads page: every stored copy is
     * relabelled (not just the row that was tapped), the sender is put on the user allowlist so the
     * classifier never filters them again, and the thread is resolved *after* the insert because a
     * sender with no conversation yet only gets a thread when the message is written.
     */
    fun restore(ctx: Context, store: Store, learned: Learned, m: Msg) {
        store.relabelCopies(m.sender, m.text, Label.IMPORTANT)
        store.recordLabel(m.sender, m.text, Label.IMPORTANT, m.ts)
        learned.markImportant(m.sender)
        learned.allow(m.sender)
        // -1 = unknown: a restored old ad carries no reliable subscription id, and the thread picks
        // the newest known SIM instead of inventing one
        val rowId = Sms.insertInbox(ctx, m.sender, m.text, m.ts, -1)
        // the row we just wrote knows its own thread; resolving by number can land on a sibling
        // thread when the same number exists under two spellings, and then the restored message
        // stays archived in the thread it actually went to
        (Sms.threadIdOfMessage(ctx, rowId) ?: Sms.threadIdFor(ctx, m.sender))
            ?.let { ThreadFlags.restore(ctx, it) }
    }
}
