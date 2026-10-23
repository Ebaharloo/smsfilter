package ir.smsfilter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony

/**
 * Per-conversation flags.
 *
 * Pinned and muted live in our own prefs; archived uses the platform column
 * (Telephony.Threads.ARCHIVED) so MIUI Messages agrees with us and an archived ad
 * stays hidden even if the user opens the stock app.
 */
object ThreadFlags {
    private const val PINNED = "pinned_threads"
    private const val MUTED = "muted_threads"
    private const val PREFS = "thread_flags"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, 0)

    private fun ids(ctx: Context, key: String): Set<Long> =
        prefs(ctx).getStringSet(key, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    private fun store(ctx: Context, key: String, value: Set<Long>) {
        prefs(ctx).edit().putStringSet(key, value.map { it.toString() }.toSet()).apply()
    }

    fun pinned(ctx: Context): Set<Long> = ids(ctx, PINNED)
    fun isPinned(ctx: Context, threadId: Long) = threadId in pinned(ctx)
    fun togglePin(ctx: Context, threadId: Long) {
        val now = pinned(ctx)
        store(ctx, PINNED, if (threadId in now) now - threadId else now + threadId)
    }

    fun muted(ctx: Context): Set<Long> = ids(ctx, MUTED)
    fun isMuted(ctx: Context, threadId: Long) = threadId in muted(ctx)
    fun toggleMute(ctx: Context, threadId: Long) {
        val now = muted(ctx)
        store(ctx, MUTED, if (threadId in now) now - threadId else now + threadId)
    }

    // The union view (content://mms-sms/conversations) has no `archived` column on MIUI and throws;
    // the plain threads table behind ?simple=true does, and accepts updates.
    private val CONVERSATIONS: Uri = Uri.parse("content://mms-sms/conversations?simple=true")

    fun archived(ctx: Context): Set<Long> = try {
        val out = HashSet<Long>()
        ctx.contentResolver.query(CONVERSATIONS, arrayOf("_id", "archived"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(1) == 1) out += c.getLong(0)
            }
        }
        out
    } catch (t: Throwable) {
        emptySet()
    }

    fun setArchived(ctx: Context, threadId: Long, archived: Boolean): Boolean {
        // Always keep the local marker as well: MIUI often reports the update as applied and still
        // shows the thread again, which looked like "archive bounces straight back to the inbox".
        store(ctx, "archived_local", if (archived) ids(ctx, "archived_local") + threadId
        else ids(ctx, "archived_local") - threadId)
        return providerArchive(ctx, threadId, archived)
    }

    /** same thing for a whole set: one prefs write, then only the provider updates */
    fun setArchived(ctx: Context, threadIds: Set<Long>, archived: Boolean) {
        if (threadIds.isEmpty()) return
        val now = ids(ctx, "archived_local")
        store(ctx, "archived_local", if (archived) now + threadIds else now - threadIds)
        threadIds.forEach { providerArchive(ctx, it, archived) }
    }

    private fun providerArchive(ctx: Context, threadId: Long, archived: Boolean): Boolean = try {
        val cv = ContentValues().apply { put(Telephony.Threads.ARCHIVED, if (archived) 1 else 0) }
        ctx.contentResolver.update(
            Uri.withAppendedPath(CONVERSATIONS, threadId.toString()), cv, null, null
        ) > 0
    } catch (t: Throwable) {
        false
    }

    fun locallyArchived(ctx: Context): Set<Long> = ids(ctx, "archived_local")

    /** conversations moved to the Ads page: hidden from the inbox AND from Archive */
    fun spamThreads(ctx: Context): Set<Long> = ids(ctx, "spam_threads")

    fun markSpam(ctx: Context, threadId: Long) {
        val now = ids(ctx, "spam_threads")
        if (threadId !in now) store(ctx, "spam_threads", now + threadId)
    }

    fun markSpam(ctx: Context, threadIds: Set<Long>) {
        val now = ids(ctx, "spam_threads")
        val next = now + threadIds.filter { it > 0 }
        if (next != now) store(ctx, "spam_threads", next)
    }

    fun unmarkSpam(ctx: Context, threadId: Long) {
        val now = ids(ctx, "spam_threads")
        if (threadId in now) store(ctx, "spam_threads", now - threadId)
    }

    /** an ad conversation lives on the Ads page only: not in the inbox and not in Archive */
    fun quarantine(ctx: Context, threadId: Long) {
        if (threadId <= 0) return
        markSpam(ctx, threadId)
        setArchived(ctx, threadId, true)
        Sms.markThreadRead(ctx, threadId)
    }

    /** same as [restore] for a whole batch */
    fun restore(ctx: Context, threadIds: Set<Long>) {
        threadIds.forEach { restore(ctx, it) }
    }

    /** "not an ad": clear both marks - un-archiving alone returned it to Archive, not the inbox */
    fun restore(ctx: Context, threadId: Long) {
        if (threadId <= 0) return
        unmarkSpam(ctx, threadId)
        setArchived(ctx, threadId, false)
    }
}
