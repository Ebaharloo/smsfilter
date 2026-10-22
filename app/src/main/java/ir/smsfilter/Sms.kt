package ir.smsfilter

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/**
 * Threads and messages from the system SMS provider.
 *
 * Conversations are derived from the `sms` table itself instead of `content://mms-sms/conversations`:
 * that union query rejects columns like unread_count on MIUI and returns nothing, while grouping the
 * message table works on every ROM and keeps the unread count accurate.
 */
object Sms {

    /** which SIM column this provider actually has: "subscription_id", "sim_id", or "" (none) */
    @Volatile
    private var simColumn: String? = null

    private fun detectSimColumn(ctx: android.content.Context): String {
        simColumn?.let { return it }
        for (candidate in listOf(Telephony.Sms.SUBSCRIPTION_ID, "sim_id")) {
            val ok = try {
                // no sortOrder/LIMIT: provider sort orders are not SQL and often reject LIMIT
                ctx.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms._ID, candidate),
                    null, null, null
                )?.use { it.moveToFirst(); true } ?: false
            } catch (e: Throwable) {
                false
            }
            android.util.Log.i("SMSSim", "probe $candidate -> $ok")
            if (ok) {
                simColumn = candidate
                return candidate
            }
        }
        simColumn = ""
        return ""
    }

    data class Thread(
        val id: Long,
        val address: String,
        val snippet: String,
        val date: Long,
        val unread: Int,
        val outgoing: Boolean,
        val simId: Int = -1        // SIM of the newest message in the thread
    )

    data class Message(
        val id: Long,
        val threadId: Long,
        val address: String,
        val body: String,
        val date: Long,
        val incoming: Boolean,
        val simId: Int = -1          // subscription_id; -1 = unknown/single-SIM
    )

    fun threads(ctx: Context, limit: Int = 300): List<Thread> {
        val simCol = detectSimColumn(ctx)
        val proj = (
            listOf(
                Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE
            ) + if (simCol.isNotEmpty()) listOf(simCol) else emptyList()
        ).toTypedArray()
        val byThread = LinkedHashMap<Long, Thread>()
        val unread = HashMap<Long, Int>()
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, proj, null, null, "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val tid = c.getLong(0)
                    val addr = c.getString(1).orEmpty()
                    val body = c.getString(2).orEmpty()
                    val date = c.getLong(3)
                    val read = c.getInt(4) == 1
                    val type = c.getInt(5)
                    val simId = if (simCol.isNotEmpty()) try { c.getInt(6) } catch (e: Throwable) { -1 } else -1
                    if (type == Telephony.Sms.MESSAGE_TYPE_INBOX && !read) {
                        unread[tid] = (unread[tid] ?: 0) + 1
                    }
                    if (byThread.containsKey(tid)) {
                        // older message of a thread we already have: unread was counted above
                    } else if (byThread.size < limit) {
                        byThread[tid] = Thread(
                            id = tid, address = addr, snippet = body, date = date,
                            unread = 0, outgoing = type == Telephony.Sms.MESSAGE_TYPE_SENT,
                            simId = simId
                        )
                    }
                }
            }
        } catch (t: Throwable) {
        }
        return byThread.values.map { it.copy(unread = unread[it.id] ?: 0) }
            .sortedByDescending { it.date }
    }

    fun messages(ctx: Context, threadId: Long): List<Message> {
        val out = ArrayList<Message>()
        val simCol = detectSimColumn(ctx)
        val proj = (
            listOf(
                Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE
            ) + if (simCol.isNotEmpty()) listOf(simCol) else emptyList()
        ).toTypedArray()
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, proj, "${Telephony.Sms.THREAD_ID}=?",
                arrayOf(threadId.toString()), "${Telephony.Sms.DATE} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Message(
                        id = c.getLong(0),
                        threadId = c.getLong(1),
                        address = c.getString(2).orEmpty(),
                        body = c.getString(3).orEmpty(),
                        date = c.getLong(4),
                        incoming = c.getInt(5) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                        simId = if (simCol.isNotEmpty()) try { c.getInt(6) } catch (e: Throwable) { -1 } else -1
                    )
                }
            }
            android.util.Log.i("SMSSim", "thread $threadId rows=${out.size} sims=${out.map { it.simId }.distinct()}")
        } catch (t: Throwable) {
        }
        return out
    }

    fun threadIdFor(ctx: Context, address: String): Long? {
        val digits = Contacts.digits(address).takeLast(9)
        return threads(ctx).firstOrNull {
            it.address == address || (digits.isNotEmpty() && Contacts.digits(it.address).takeLast(9) == digits)
        }?.id
    }

    fun markThreadRead(ctx: Context, threadId: Long) {
        try {
            val cv = ContentValues().apply { put(Telephony.Sms.READ, 1) }
            ctx.contentResolver.update(
                Telephony.Sms.CONTENT_URI, cv,
                "${Telephony.Sms.THREAD_ID}=? AND ${Telephony.Sms.READ}=0", arrayOf(threadId.toString())
            )
        } catch (t: Throwable) {
        }
    }

    fun deleteMessage(ctx: Context, id: Long): Boolean = try {
        ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID}=?", arrayOf(id.toString())) > 0
    } catch (t: Throwable) {
        false
    }

    /** active SIMs as (subscriptionId, label) */
    fun sims(ctx: Context): List<Pair<Int, String>> = try {
        val sm = ctx.getSystemService(android.telephony.SubscriptionManager::class.java)
        val list = sm.activeSubscriptionInfoList
        android.util.Log.i("SMSSim", "active subs=${list?.size ?: -1}")
        list.orEmpty().map { info ->
            info.subscriptionId to (info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}")
        }
    } catch (t: Throwable) {
        emptyList()
    }

    fun deleteThread(ctx: Context, threadId: Long) {
        try {
            ctx.contentResolver.delete(
                Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString())
            )
        } catch (t: Throwable) {
        }
    }

    fun insertInbox(ctx: Context, address: String, body: String, date: Long): Long = try {
        val cv = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.DATE_SENT, date)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, cv)?.lastPathSegment?.toLongOrNull() ?: -1
    } catch (t: Throwable) {
        -1
    }

    fun send(ctx: Context, address: String, body: String, preferredSub: Int? = null): Boolean = try {
        val subId = preferredSub ?: SubscriptionManager.getDefaultSmsSubscriptionId()
        val sm: SmsManager = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
            android.os.Build.VERSION.SDK_INT >= 31
        ) ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
        else SmsManager.getDefault()

        val parts = sm.divideMessage(body)
        if (parts.size > 1) sm.sendMultipartTextMessage(address, null, parts, null, null)
        else sm.sendTextMessage(address, null, body, null, null)

        try {
            val simCol = detectSimColumn(ctx)
            ctx.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, ContentValues().apply {
                if (simCol.isNotEmpty() && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    put(simCol, subId)
                }
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
            })
        } catch (e: Throwable) {
            // the SMS already left the phone; a failed archive copy is not a send failure
        }
        true
    } catch (t: Throwable) {
        false
    }
}
