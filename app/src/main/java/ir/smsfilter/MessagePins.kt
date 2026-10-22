package ir.smsfilter

import android.content.Context

/** Pinned messages, per conversation, kept locally (they are a view setting, not provider data). */
object MessagePins {
    private const val PREFS = "message_pins"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, 0)

    fun list(ctx: Context, threadId: Long): Set<Long> =
        prefs(ctx).getStringSet(threadId.toString(), emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    private fun store(ctx: Context, threadId: Long, ids: Set<Long>) {
        prefs(ctx).edit().putStringSet(threadId.toString(), ids.map { it.toString() }.toSet()).apply()
    }

    fun toggle(ctx: Context, threadId: Long, messageId: Long): Boolean {
        val now = list(ctx, threadId)
        val pinned = messageId !in now
        store(ctx, threadId, if (pinned) now + messageId else now - messageId)
        return pinned
    }

    /** drop pins for messages that no longer exist */
    fun prune(ctx: Context, threadId: Long, alive: Set<Long>) {
        val now = list(ctx, threadId)
        val kept = now.filter { it in alive }.toSet()
        if (kept != now) store(ctx, threadId, kept)
    }
}
