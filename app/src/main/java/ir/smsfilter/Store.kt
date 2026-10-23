package ir.smsfilter

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

data class Msg(
    val id: Long,
    val ts: Long,
    val sender: String,
    val text: String,
    val label: Label,
    val score: Int,
    val why: String
)

class Store private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "smsfilter.db", null, 5) {

    companion object {
        @Volatile private var inst: Store? = null

        /** one helper (and one connection) per process: the receiver used to open three per SMS */
        fun get(ctx: Context): Store = inst ?: synchronized(this) {
            inst ?: Store(ctx.applicationContext).also { inst = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE msg(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, sender TEXT, " +
                "text TEXT NOT NULL, label TEXT NOT NULL, score INTEGER NOT NULL, why TEXT, " +
                "manual INTEGER NOT NULL DEFAULT 0, UNIQUE(ts, text))"
        )
        createLabels(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // 3 -> 4 only adds the append-only label table. Older versions dropped `msg` here, which
        // threw away every stored ad and every user decision on a schema bump - never again.
        if (old < 3) {
            db.execSQL("DROP TABLE IF EXISTS msg")
            db.execSQL(
                "CREATE TABLE msg(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, sender TEXT, " +
                    "text TEXT NOT NULL, label TEXT NOT NULL, score INTEGER NOT NULL, why TEXT, " +
                    "manual INTEGER NOT NULL DEFAULT 0, UNIQUE(ts, text))"
            )
        }
        createLabels(db)
        if (old < 5) {
            // v4 keyed on (text,label), so correcting a verdict added a second row instead of
            // replacing it. One verdict per message.
            db.execSQL("CREATE TABLE IF NOT EXISTS labels_v5(text TEXT NOT NULL PRIMARY KEY, "
                + "sender TEXT, label TEXT NOT NULL, ts INTEGER NOT NULL)")
            db.execSQL("INSERT OR REPLACE INTO labels_v5(text, sender, label, ts) "
                + "SELECT text, sender, label, ts FROM labels ORDER BY ts")
            db.execSQL("DROP TABLE IF EXISTS labels")
            db.execSQL("ALTER TABLE labels_v5 RENAME TO labels")
        }
    }

    /**
     * The user's own decisions, append-only.
     *
     * Deleting an ad from the Ads page used to delete its only copy, so the training signal the user
     * had just produced disappeared - and "re-check with current filter" could overwrite a verdict he
     * made by hand. Every tap is recorded here once and never rewritten: triage is done once.
     */
    private fun createLabels(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE IF NOT EXISTS labels(text TEXT NOT NULL PRIMARY KEY, sender TEXT, " +
            "label TEXT NOT NULL, ts INTEGER NOT NULL)"
    )

    fun add(sender: String, text: String, v: Verdict, ts: Long = System.currentTimeMillis()): Long =
        writableDatabase.insertWithOnConflict("msg", null, ContentValues().apply {
        put("ts", ts)
        put("sender", sender)
        put("text", text)
        put("label", v.label.name)
        put("score", v.score)
        put("why", v.why.joinToString(", "))
    }, SQLiteDatabase.CONFLICT_IGNORE)

    /** record a verdict the user made himself (ad / not an ad); survives deletes and re-checks */
    fun recordLabel(sender: String, text: String, label: Label, ts: Long = System.currentTimeMillis()) {
        if (text.isBlank()) return
        writableDatabase.insertWithOnConflict("labels", null, ContentValues().apply {
            put("text", text)
            put("sender", sender)
            put("label", label.name)
            put("ts", ts)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun userLabelFor(text: String): String? =
        readableDatabase.rawQuery("SELECT label FROM labels WHERE text=?", arrayOf(text)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    /** does a copy of this exact message exist? the gate before any provider delete */
    fun hasCopy(sender: String, text: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM msg WHERE sender=? AND text=? LIMIT 1", arrayOf(sender, text)
        ).use { it.moveToFirst() }

    /** every user decision, for training and for "did the new model improve?" */
    fun labels(): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        readableDatabase.rawQuery("SELECT text, sender, label FROM labels ORDER BY ts DESC", null).use { c ->
            while (c.moveToNext()) out += Triple(c.getString(0), c.getString(1) ?: "", c.getString(2))
        }
        return out
    }

    fun labelCounts(): Map<String, Int> = labels().groupingBy { it.third }.eachCount()

    /** record one verdict for every message of a conversation: triage per thread, not per message */
    fun recordLabels(sender: String, texts: List<String>, label: Label, ts: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            texts.filter { it.isNotBlank() }.forEach { text ->
                db.insertWithOnConflict("labels", null, ContentValues().apply {
                    put("text", text)
                    put("sender", sender)
                    put("label", label.name)
                    put("ts", ts)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** JSONL the trainer reads; one object per line, no escaping surprises */
    fun labelsJsonl(): String = buildString {
        labels().forEach { (text, sender, label) ->
            append(JSONObject().apply {
                put("text", text)
                put("sender", sender)
                put("label", label)
            }.toString())
            append('\n')
        }
    }

    fun list(label: Label? = null, limit: Int = 300): List<Msg> {
        val where = if (label != null) "WHERE label=?" else ""
        val args = if (label != null) arrayOf(label.name) else null
        val out = mutableListOf<Msg>()
        readableDatabase.rawQuery(
            "SELECT id, ts, sender, text, label, score, why FROM msg $where ORDER BY ts DESC LIMIT $limit", args
        ).use { c ->
            while (c.moveToNext()) out += Msg(
                c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3),
                Label.valueOf(c.getString(4)), c.getInt(5), c.getString(6) ?: ""
            )
        }
        return out
    }

    /** the user's own verdict for a row: manual=1 means a rescan never overwrites it */
    fun relabel(id: Long, label: Label) = writableDatabase
        .execSQL("UPDATE msg SET label=?, manual=1 WHERE id=?", arrayOf(label.name, id))

    /**
     * Relabel every stored copy of one message. The store keeps a row per arrival, and the same
     * text can sit there as SPAM more than once - relabelling only the tapped row left the other
     * copies on the Ads page, so the message looked like it came back.
     */
    fun relabelCopies(sender: String, text: String, label: Label) = writableDatabase
        .execSQL(
            "UPDATE msg SET label=?, manual=1 WHERE sender=? AND text=?",
            arrayOf(label.name, sender, text)
        )

    fun delete(id: Long) =
        writableDatabase.delete("msg", "id=?", arrayOf(id.toString()))

    fun deleteAll(label: Label) =
        writableDatabase.delete("msg", "label=?", arrayOf(label.name))

    fun counts(): Map<Label, Int> = Label.entries.associateWith { l ->
        readableDatabase.rawQuery("SELECT COUNT(*) FROM msg WHERE label=?", arrayOf(l.name)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
}
