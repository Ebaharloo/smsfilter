package ir.smsfilter

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Msg(
    val id: Long,
    val ts: Long,
    val sender: String,
    val text: String,
    val label: Label,
    val score: Int,
    val why: String
)

class Store(ctx: Context) : SQLiteOpenHelper(ctx, "smsfilter.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE msg(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, sender TEXT, " +
            "text TEXT NOT NULL, label TEXT NOT NULL, score INTEGER NOT NULL, why TEXT)"
    )

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit

    fun add(sender: String, text: String, v: Verdict): Long = writableDatabase.insert("msg", null, ContentValues().apply {
        put("ts", System.currentTimeMillis())
        put("sender", sender)
        put("text", text)
        put("label", v.label.name)
        put("score", v.score)
        put("why", v.why.joinToString(", "))
    })

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

    fun relabel(id: Long, label: Label) = writableDatabase
        .execSQL("UPDATE msg SET label=? WHERE id=?", arrayOf(label.name, id))

    fun counts(): Map<Label, Int> = Label.entries.associateWith { l ->
        readableDatabase.rawQuery("SELECT COUNT(*) FROM msg WHERE label=?", arrayOf(l.name)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
}
