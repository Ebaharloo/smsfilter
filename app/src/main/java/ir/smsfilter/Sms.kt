package ir.smsfilter

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log

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
        val simId: Int = -1,          // subscription_id; -1 = unknown/single-SIM
        val status: Int = 0           // Telephony.Sms.STATUS_* for the sent copy
    )

    fun threads(ctx: Context, limit: Int = 300, maxRows: Int = 5000): List<Thread> {
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
                var scanned = 0
                while (c.moveToNext() && scanned < maxRows) {
                    scanned++
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
                    // the newest message of a thread becomes its snippet; older ones only count unread
                    if (!byThread.containsKey(tid) && byThread.size < limit) {
                        byThread[tid] = Thread(
                            id = tid, address = addr, snippet = body, date = date,
                            unread = 0, outgoing = type == Telephony.Sms.MESSAGE_TYPE_SENT,
                            simId = simId
                        )
                    } else if (simId > 0) {
                        // this app used to insert its own copies without a subscription id, so the
                        // newest message can carry 0 while older ones know the SIM: take the newest
                        // valid one so the row still shows which card it belongs to
                        val cur = byThread[tid]
                        if (cur != null && cur.simId <= 0) byThread[tid] = cur.copy(simId = simId)
                    }
                }
            }
        } catch (t: Throwable) {
        }
        return byThread.values.map { it.copy(unread = unread[it.id] ?: 0) }
            .sortedByDescending { it.date }
    }

    fun messages(ctx: Context, threadId: Long, window: Int = 800): List<Message> {
        val out = ArrayList<Message>()
        val simCol = detectSimColumn(ctx)
        val proj = (
            listOf(
                Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE,
                Telephony.Sms.STATUS
            ) + if (simCol.isNotEmpty()) listOf(simCol) else emptyList()
        ).toTypedArray()
        // newest first and stop at the window: a bank thread can hold thousands of messages and the
        // UI only ever shows the newest ~800, so reading the whole thread was pure latency
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, proj, "${Telephony.Sms.THREAD_ID}=?",
                arrayOf(threadId.toString()), "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC"
            )?.use { c ->
                while (out.size < window && c.moveToNext()) {
                    out += Message(
                        id = c.getLong(0),
                        threadId = c.getLong(1),
                        address = c.getString(2).orEmpty(),
                        body = c.getString(3).orEmpty(),
                        date = c.getLong(4),
                        incoming = c.getInt(5) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                        simId = if (simCol.isNotEmpty()) try { c.getInt(7) } catch (e: Throwable) { -1 } else -1,
                        status = try { c.getInt(6) } catch (e: Throwable) { 0 }
                    )
                }
            }
        } catch (t: Throwable) {
        }
        out.reverse()   // back to oldest-first, which is how bubbles are drawn
        return out
    }

    /**
     * thread id for a number, asked of the provider directly - no inbox-wide grouping.
     * The provider may hold 09121234567 while the address book hands us +98912****4567, so the
     * lookup asks for every spelling of the number in one indexed query instead of grouping the
     * inbox. A row-by-row tail match is the last resort for spellings we cannot enumerate
     * (separators, odd prefixes) and only runs when the indexed query missed.
     */
    fun threadIdFor(ctx: Context, address: String): Long? {
        if (address.isBlank()) return null
        // exact spelling first: the same number can live in two threads when it was written with a
        // trailing space or another prefix, and only this spelling's thread is the one the message
        // actually sits in (mute flags and restores must act on that one)
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID), "${Telephony.Sms.ADDRESS}=?",
                arrayOf(address), "${Telephony.Sms.DATE} DESC"
            )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        } catch (t: Throwable) {
        }
        val forms = numberForms(address)
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID),
                "${Telephony.Sms.ADDRESS} IN (${forms.joinToString(",") { "?" }})",
                forms.toTypedArray(), "${Telephony.Sms.DATE} DESC"
            )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        } catch (t: Throwable) {
        }
        if (Contacts.digits(address).isEmpty()) return null
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS),
                null, null, "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    if (ir.smsfilter.core.ComposeIntent.sameNumber(c.getString(1).orEmpty(), address)) {
                        return c.getLong(0)
                    }
                }
            }
        } catch (t: Throwable) {
        }
        return null
    }

    /** every way this number can be written in the provider, for one indexed lookup */
    private fun numberForms(address: String): List<String> {
        val key = ir.smsfilter.core.ComposeIntent.numberKey(address)
        return (ir.smsfilter.core.ComposeIntent.lookupVariants(address) +
            if (key.length == 10) listOf("98$key", "0098$key") else emptyList()).distinct()
    }

    /** the thread a specific provider row belongs to - exact, no number-guessing */
    fun threadIdOfMessage(ctx: Context, messageId: Long): Long? = try {
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID), "${Telephony.Sms._ID}=?",
            arrayOf(messageId.toString()), null
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    } catch (t: Throwable) {
        null
    }

    fun setThreadRead(ctx: Context, threadId: Long, read: Boolean) {
        try {
            val cv = ContentValues().apply { put(Telephony.Sms.READ, if (read) 1 else 0) }
            ctx.contentResolver.update(
                Telephony.Sms.CONTENT_URI, cv,
                "${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString())
            )
        } catch (t: Throwable) {
        }
    }

    fun markThreadRead(ctx: Context, threadId: Long) = setThreadRead(ctx, threadId, true)

    fun deleteMessage(ctx: Context, id: Long): Boolean = try {
        ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID}=?", arrayOf(id.toString())) > 0
    } catch (t: Throwable) {
        false
    }

    /**
     * One physical SIM as the user sees it: slot, carrier, number.
     * [label] is the slot, because that is what the tray shows - a subscription id means nothing
     * to a person, and on this ROM two different sub ids both resolved to slot 0.
     */
    data class Sim(
        val subId: Int,
        val slot: Int,
        val carrier: String? = null,
        val number: String? = null
    ) {
        val label: String get() = if (slot >= 0) "SIM ${slot + 1}" else "SIM $subId"
    }

    @Volatile
    private var simsCache: Pair<Long, List<Sim>>? = null

    /** active SIMs, cached - every row badge and both pickers ask for it */
    fun sims(ctx: Context): List<Sim> {
        val now = android.os.SystemClock.elapsedRealtime()
        simsCache?.let { if (now - it.first < 60_000) return it.second }
        val fresh = computeSims(ctx)
        simsCache = now to fresh
        return fresh
    }

    @Volatile
    private var multiSimCache: Pair<Long, Boolean>? = null

    /** true when the phone actually has more than one SIM; badge only makes sense then */
    fun multiSim(ctx: Context): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        multiSimCache?.let { if (now - it.first < 60_000) return it.second }
        val phoneCount = try {
            ctx.getSystemService(android.telephony.TelephonyManager::class.java)?.phoneCount ?: 0
        } catch (t: Throwable) {
            0
        }
        val v = sims(ctx).size > 1 || phoneCount > 1
        multiSimCache = now to v
        return v
    }

    /** the SIM as "SIM 1" / "SIM 2", or null when the id is not a real subscription */
    fun simLabel(ctx: Context, simId: Int): String? =
        if (!multiSim(ctx)) null else sims(ctx).firstOrNull { it.subId == simId }?.label
            ?: slotLabel(simId)?.let { "SIM ${it + 1}" }

    /** the slot number alone, for the small circular badge */
    fun simShortLabel(ctx: Context, simId: Int): String? {
        if (!multiSim(ctx)) return null
        val slot = sims(ctx).firstOrNull { it.subId == simId }?.slot ?: slotLabel(simId) ?: return null
        return if (slot >= 0) "${slot + 1}" else null
    }

    /** slot for a sub id, ignoring the ROM's 0/"empty" placeholder */
    private fun slotLabel(simId: Int): Int? {
        if (simId <= 0) return null
        return try {
            SubscriptionManager.getSlotIndex(simId).takeIf { it >= 0 }
        } catch (t: Throwable) {
            null
        }
    }

    private fun computeSims(ctx: Context): List<Sim> {
        // 1. the framework list, when this ROM deigns to return it
        val fromManager = try {
            ctx.getSystemService(android.telephony.SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList.orEmpty()
                .filter { it.subscriptionId > 0 }
                .map { info ->
                    Sim(info.subscriptionId, info.simSlotIndex,
                        info.carrierName?.toString(), info.number)
                }
        } catch (t: Throwable) {
            emptyList()
        }
        android.util.Log.i("SMSSim", "subs=" + fromManager.joinToString { "${it.subId}@slot${it.slot}" })
        if (fromManager.isNotEmpty()) return fromManager.sortedBy { it.slot }

        // 2. MIUI hands out an empty info list even to the default SMS app, but the subscription
        //    database behind SubscriptionManager still has the truth: sub id, slot, carrier, number.
        simInfoFromProvider(ctx)?.takeIf { it.isNotEmpty() }?.let { return it }

        // 3. last resort: the sub ids that actually carry messages. sub id 0 is the ROM's "unknown"
        //    placeholder and must never become a row - it used to beat the real SIM in this list and
        //    then both rows labelled themselves "SIM 1".
        val dbs = dbSubIds(ctx).filter { it > 0 }
        if (dbs.isEmpty()) {
            val def = try {
                SubscriptionManager.getDefaultSmsSubscriptionId()
            } catch (t: Throwable) {
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
            return if (def > 0) listOf(Sim(def, subscriptionSlot(def))) else emptyList()
        }
        // one row per physical slot: duplicate spellings of the same card are one SIM
        val resolved = dbs.mapNotNull { sub ->
            val slot = slotLabel(sub) ?: return@mapNotNull null
            // carrier comes from the telephony manager per subscription: the info list is empty on
            // this ROM, so this is the only name a person can recognise a card by
            Sim(sub, slot, carrierOf(ctx, sub))
        }.distinctBy { it.slot }.sortedBy { it.slot }
        android.util.Log.i("SMSSim", "db subs=" + dbs + " -> " + resolved.joinToString { "${it.subId}@slot${it.slot}(${it.carrier})" })
        return resolved
    }

    /** carrier name for a subscription, or null when the ROM will not say */
    private fun carrierOf(ctx: Context, subId: Int): String? = try {
        val tm = ctx.getSystemService(android.telephony.TelephonyManager::class.java)
            ?.createForSubscriptionId(subId)
        listOfNotNull(
            tm?.networkOperatorName,
            tm?.simOperatorName,
            tm?.simCarrierIdName?.toString()
        ).firstOrNull { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }

    /** subscriptions straight out of the telephony subscription database */
    private fun simInfoFromProvider(ctx: Context): List<Sim>? = try {
        ctx.contentResolver.query(
            android.net.Uri.parse("content://telephony/siminfo"),
            arrayOf("_id", "sim_slot_index", "display_name", "carrier_name", "number"),
            null, null, null
        )?.use { c ->
            val out = ArrayList<Sim>()
            while (c.moveToNext()) {
                val sub = c.getInt(0)
                if (sub <= 0) continue
                val slot = c.getInt(1).takeIf { it >= 0 } ?: continue
                val display = c.getString(2)?.trim()?.takeIf { it.isNotEmpty() }
                val carrier = c.getString(3)?.trim()?.takeIf { it.isNotEmpty() } ?: display
                out += Sim(sub, slot, carrier, c.getString(4))
            }
            out.distinctBy { it.slot }.sortedBy { it.slot }
        }
    } catch (t: Throwable) {
        // no READ_PHONE_STATE or a locked-down provider: try the next source
        android.util.Log.i("SMSSim", "siminfo query failed: ${t.javaClass.simpleName}")
        null
    }

    private fun subscriptionSlot(subId: Int): Int = try {
        SubscriptionManager.getSlotIndex(subId)
    } catch (t: Throwable) {
        -1
    }

    /** sub ids that carried at least one stored message, newest activity first */
    private fun dbSubIds(ctx: Context): List<Int> {
        val col = detectSimColumn(ctx)
        if (col.isEmpty()) return emptyList()
        val lastSeen = HashMap<Int, Long>()
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf(col, Telephony.Sms.DATE), null, null, null
            )?.use { c ->
                var rows = 0
                while (c.moveToNext() && rows < 2000) {
                    rows++
                    val v = c.getInt(0)
                    val ts = c.getLong(1)
                    if (v >= 0) lastSeen[v] = maxOf(lastSeen[v] ?: 0L, ts)
                }
            }
        } catch (t: Throwable) {
        }
        return lastSeen.entries.sortedByDescending { it.value }.map { it.key }
    }

    fun deleteThread(ctx: Context, threadId: Long) {
        try {
            val n = ctx.contentResolver.delete(
                Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString())
            )
            if (n <= 0) Log.w("SMSFilter", "delete of thread $threadId removed no rows")
        } catch (t: Throwable) {
            // never silent: a failed delete means the conversation is still in the inbox
            Log.e("SMSFilter", "delete of thread $threadId failed", t)
        }
    }

    /**
     * Write a message into the inbox.
     *
     * [subId] is required on purpose: a default let every caller forget it, and a row without a
     * subscription id has no SIM badge anywhere in the UI. Pass -1 explicitly when the SIM is
     * genuinely unknown (restoring an old ad), never "because it was optional".
     */
    fun insertInbox(ctx: Context, address: String, body: String, date: Long, subId: Int): Long = try {
        val simCol = detectSimColumn(ctx)
        val cv = ContentValues().apply {
            if (simCol.isNotEmpty() && subId > 0) put(simCol, subId)
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

    /**
     * Send, and report truthfully: the stored copy starts as STATUS_PENDING, the carrier's send
     * callback flips it to complete/failed, and the delivery callback marks it delivered.
     * [ok] is false only when the SmsManager call itself threw (missing permission, no radio).
     */
    data class SendResult(val ok: Boolean, val rowId: Long)

    fun send(ctx: Context, address: String, body: String, preferredSub: Int? = null): Boolean =
        sendTracked(ctx, address, body, preferredSub).ok

    fun sendTracked(
        ctx: Context,
        address: String,
        body: String,
        preferredSub: Int? = null
    ): SendResult {
        val subId = preferredSub ?: try {
            SubscriptionManager.getDefaultSmsSubscriptionId()
        } catch (t: Throwable) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        // store the copy first: its row id is what the send and delivery callbacks update
        val rowId = insertSent(ctx, address, body, subId, Telephony.Sms.STATUS_PENDING)
        return try {
            val sm: SmsManager = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
                android.os.Build.VERSION.SDK_INT >= 31
            ) ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            else SmsManager.getDefault()

            val sent = statusIntent(ctx, rowId, SendStatus.ACTION_SENT) ?: run {
                markStatus(ctx, rowId, Telephony.Sms.STATUS_FAILED)
                return SendResult(false, rowId)
            }
            val delivered = statusIntent(ctx, rowId, SendStatus.ACTION_DELIVERED)
            val parts = sm.divideMessage(body)
            if (parts.size > 1) {
                sm.sendMultipartTextMessage(
                    address, null, parts,
                    ArrayList(parts.indices.map { sent }),
                    delivered?.let { ArrayList(parts.indices.map { _ -> it }) }
                )
            } else {
                sm.sendTextMessage(address, null, body, sent, delivered)
            }
            SendResult(true, rowId)
        } catch (t: Throwable) {
            // never silent: a missing SEND_SMS grant used to look like "the app cannot send SMS at all"
            android.util.Log.e("SMSFilter", "send failed to=$address sub=$subId", t)
            markStatus(ctx, rowId, Telephony.Sms.STATUS_FAILED)
            SendResult(false, rowId)
        }
    }

    private fun statusIntent(ctx: Context, rowId: Long, action: String): android.app.PendingIntent? {
        if (rowId <= 0) return null
        val intent = android.content.Intent(ctx, SendStatusReceiver::class.java).apply {
            this.action = action
            putExtra("row", rowId)
        }
        return android.app.PendingIntent.getBroadcast(
            ctx, (rowId.toString() + action).hashCode(), intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun markStatus(ctx: Context, rowId: Long, status: Int) {
        if (rowId <= 0) return
        try {
            ctx.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply { put(Telephony.Sms.STATUS, status) },
                "${Telephony.Sms._ID}=?", arrayOf(rowId.toString())
            )
        } catch (t: Throwable) {
        }
    }

    /** the sent copy of one message; -1 when the provider refused it */
    private fun insertSent(ctx: Context, address: String, body: String, subId: Int, status: Int): Long =
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
                put(Telephony.Sms.STATUS, status)
            })?.lastPathSegment?.toLongOrNull() ?: -1L.also {
                android.util.Log.e("SMSFilter", "sent copy refused by provider (no row id)")
            }
        } catch (t: Throwable) {
            // the SMS can still leave the phone; a failed archive copy is not a send failure
            android.util.Log.e("SMSFilter", "sent copy insert failed", t)
            -1L
        }
}
