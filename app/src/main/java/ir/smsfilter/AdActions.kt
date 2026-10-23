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

    /** keep the copy, teach the words, and move the conversation (threadId 0 = look it up) */
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
        val tid = if (threadId > 0) threadId else Sms.threadIdFor(ctx, sender) ?: 0L
        if (tid > 0) ThreadFlags.quarantine(ctx, tid)
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
        learned.markImportant(m.sender)
        learned.allow(m.sender)
        val rowId = Sms.insertInbox(ctx, m.sender, m.text, m.ts)
        // the row we just wrote knows its own thread; resolving by number can land on a sibling
        // thread when the same number exists under two spellings, and then the restored message
        // stays archived in the thread it actually went to
        (Sms.threadIdOfMessage(ctx, rowId) ?: Sms.threadIdFor(ctx, m.sender))
            ?.let { ThreadFlags.restore(ctx, it) }
    }
}
