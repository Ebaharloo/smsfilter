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

    /** "not an ad": back into the inbox, tap tally cleared, no longer archived or spam-marked */
    fun restore(ctx: Context, store: Store, learned: Learned, m: Msg) {
        store.relabel(m.id, Label.IMPORTANT)
        learned.markImportant(m.sender)
        Sms.insertInbox(ctx, m.sender, m.text, m.ts)
        Sms.threadIdFor(ctx, m.sender)?.let { ThreadFlags.restore(ctx, it) }
    }
}
