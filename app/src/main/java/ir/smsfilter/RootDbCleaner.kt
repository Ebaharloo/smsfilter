package ir.smsfilter

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Root-only inbox cleanup: delete chosen rows from the REAL SMS database so MIUI/Google Messages
 * stop showing them, while our own quarantine copy stays behind.
 *
 * Why pipes instead of copying files: on this device Magisk root can read the provider database and
 * can write into its directory, but `cp`/`chown` are refused by SELinux, and /sdcard/Android/data/<pkg>
 * belongs to the app alone - so no filesystem path is shared by root and app. Streaming bytes through
 * the root process works both ways (verified byte-identical on the real phone):
 *   read  : su -c "cat <db>"           -> app keeps a local copy + a backup
 *   write : su -c "dd of=<db>"  (stdin) -> in-place overwrite, so owner/mode stay radio:radio 660
 * AOSP also refuses provider writes from anyone but the default SMS app (com.android.shell has
 * WRITE_SMS granted and is still rejected), which is why the database file is edited directly.
 */
object RootDbCleaner {

    private const val TAG = "SMSFilterRoot"

    private const val DB_DIR = "/data/data/com.android.providers.telephony/databases"
    private const val DB = "mmssms.db"
    private val LIVE = "$DB_DIR/$DB"

    data class Result(val ok: Boolean, val deleted: Int, val backup: String?, val message: String)

    /**
     * Apps live in a per-app mount namespace where other packages' /data/data entries are hidden,
     * so a plain `su -c` cannot even see the provider database. Magisk's -mm (mount master) runs the
     * command in the global namespace; we try that first and fall back to plain su.
     */
    private fun su(cmd: String, stdin: ByteArray? = null, timeoutSec: Long = 90, mountMaster: Boolean = true): ByteArray? = try {
        val argv = if (mountMaster) arrayOf("su", "-mm", "-c", cmd) else arrayOf("su", "-c", cmd)
        val p = ProcessBuilder(*argv).start()
        if (stdin != null) p.outputStream.use { it.write(stdin) } else p.outputStream.close()
        val out = p.inputStream.readBytes()
        val done = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) { p.destroy(); null } else out
    } catch (t: Throwable) {
        null
    }

    private fun suText(cmd: String) = su("$cmd 2>/dev/null")?.toString(Charsets.UTF_8)?.trim() ?: ""

    fun available(): Boolean = suText("id").contains("uid=0")

    fun deleteRows(ctx: Context, ids: List<Long>): Result {
        if (ids.isEmpty()) return Result(false, 0, null, "nothing to delete")
        if (!available()) return Result(false, 0, null, "no root access")

        val work = File(ctx.filesDir, "dbwork").apply { mkdirs() }
        val backupDir = File(ctx.getExternalFilesDir(null), "backups").apply { mkdirs() }
        val local = File(work, DB)
        val before = suText("content query --uri content://sms --projection _id | wc -l").toIntOrNull()

        // 1. pull the live database through root, and keep a restorable copy first
        var raw = su("cat $LIVE")
        if (raw == null || raw.size < 4096) raw = su("cat $LIVE", mountMaster = false)
        if (raw == null || raw.size < 4096) {
            // diagnostic: can a tiny read through the same channel work at all?
            val probe = suText("head -c 32 $LIVE | wc -c")
            val probe2 = suText("head -c 32 $LIVE 2>&1 | wc -c")
            android.util.Log.i(TAG, "read failed full=${raw?.size ?: 0} probe='$probe' probe2='$probe2'")
            return Result(false, 0, null,
                "could not read the SMS database (full=${raw?.size ?: 0} probe=$probe probe2=$probe2)")
        }
        local.writeBytes(raw)
        android.util.Log.i(TAG, "read ${raw.size} bytes")
        val backup = File(backupDir, "mmssms-backup-${System.currentTimeMillis()}.db")
        try {
            backup.writeBytes(raw)
        } catch (t: Throwable) {
            return Result(false, 0, null, "backup failed: ${t.message}")
        }

        // 2. edit the local copy
        var deleted = 0
        try {
            SQLiteDatabase.openDatabase(local.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                val rows = countIn(d)
                ids.chunked(400).forEach { chunk ->
                    d.execSQL("DELETE FROM sms WHERE _id IN (${chunk.joinToString(",")})")
                }
                deleted = rows - countIn(d)
            }
        } catch (t: Throwable) {
            return Result(false, 0, backup.absolutePath, "edit failed: ${t.message}")
        }
        android.util.Log.i(TAG, "deleted=$deleted")
        if (deleted <= 0) return Result(false, 0, backup.absolutePath, "no rows matched")

        // 3. write it back through the root pipe (in place, so ownership never changes)
        val edited = local.readBytes()
        suText("am force-stop com.android.providers.telephony")
        var wrote = su("dd of=$LIVE 2>/dev/null; rm -f $DB_DIR/$DB-journal; echo WROTE", edited)
            ?.toString(Charsets.UTF_8) ?: ""
        android.util.Log.i(TAG, "write-back output='$wrote'")
        if (!wrote.contains("WROTE")) {
            wrote = su("dd of=$LIVE 2>/dev/null; rm -f $DB_DIR/$DB-journal; echo WROTE", edited, mountMaster = false)
                ?.toString(Charsets.UTF_8) ?: ""
        }

        android.util.Log.i(TAG, "write-back output='$wrote'")
        if (!wrote.contains("WROTE")) {
            su("dd of=$LIVE 2>/dev/null; echo RESTORED", raw)      // put the original back
            return Result(false, 0, backup.absolutePath, "write-back failed, original restored")
        }

        // 4. verify with the provider itself
        val after = suText("content query --uri content://sms --projection _id | wc -l").toIntOrNull()
        val text = if (after != null && before != null) {
            "deleted $deleted ads · inbox $before → $after"
        } else {
            "deleted $deleted ads"
        }
        return Result(true, deleted, backup.absolutePath, text)
    }

    private fun countIn(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COUNT(*) FROM sms", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
