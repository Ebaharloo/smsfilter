package ir.smsfilter

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Root-only inbox cleanup: delete chosen rows from the REAL SMS database, so MIUI/Google Messages
 * stop showing them, while our own quarantine copy stays behind.
 *
 * Validated mechanics (dry run on a real device): stop provider -> copy db -> edit copy -> copy back
 * -> chown 1001:1001 -> chmod 660 -> verify count. The provider is only stopped for the duration of
 * the copy-back, and a timestamped backup is written before anything is modified.
 *
 * AOSP only lets the default SMS app write through the provider (com.android.shell has WRITE_SMS
 * granted and is still refused), which is why this edits the database file directly instead.
 */
object RootDbCleaner {

    private const val DB_DIR = "/data/data/com.android.providers.telephony/databases"
    private const val DB = "mmssms.db"
    private const val OWNER = "1001:1001"   // radio

    data class Result(
        val ok: Boolean,
        val deleted: Int,
        val backup: String?,
        val message: String
    )

    private fun sh(cmd: String): Pair<Int, String> = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val done = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) p.destroy()
        (if (done) p.exitValue() else -1) to out
    } catch (t: Throwable) {
        0 to (t.message ?: "")
    }

    fun available(): Boolean = sh("id").second.contains("uid=0")

    /**
     * @param ids provider _id values to delete. Only rows whose text we already stored should be
     *            passed - the caller keeps the copy first.
     */
    fun deleteRows(ctx: Context, ids: List<Long>): Result {
        if (ids.isEmpty()) return Result(false, 0, null, "nothing to delete")
        if (!available()) return Result(false, 0, null, "no root access")

        val work = File(ctx.getExternalFilesDir(null), "dbwork").apply { mkdirs() }
        val live = "$DB_DIR/$DB"
        val backupDir = File("/sdcard/Download/smsfilter-backup").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val backup = File(backupDir, "mmssms-backup-$stamp.db")

        // 1. a restorable copy exists BEFORE anything is touched
        sh("mkdir -p ${backupDir.absolutePath}")
        val (rcB, outB) = sh("cp -f $live ${backup.absolutePath} && chmod 644 ${backup.absolutePath}")
        if (rcB != 0 || !backup.exists()) {
            return Result(false, 0, null, "backup failed: $outB")
        }

        // 2. copy the live db out, edit the copy (provider keeps serving reads meanwhile)
        val local = File(work, DB)
        sh("cp -f $live ${local.absolutePath} && chmod 666 ${local.absolutePath}")
        if (!local.exists() || local.length() == 0L) {
            return Result(false, 0, backup.absolutePath, "copy out failed")
        }
        val countBefore = countRows(local)

        var deleted = 0
        try {
            val db = SQLiteDatabase.openDatabase(local.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            db.use { d ->
                ids.chunked(400).forEach { chunk ->
                    d.execSQL("DELETE FROM sms WHERE _id IN (${chunk.joinToString(",")})")
                }
                deleted = countBefore - countRows(d)
            }
        } catch (t: Throwable) {
            return Result(false, 0, backup.absolutePath, "edit failed: ${t.message}")
        }
        if (deleted <= 0) return Result(false, 0, backup.absolutePath, "no rows matched")

        // 3. swap it in: provider stopped only for the copy-back window
        sh("am force-stop com.android.providers.telephony")
        val (rcC, outC) = sh(
            "cp -f ${local.absolutePath} $live && chown $OWNER $live && chmod 660 $live"
        )
        if (rcC != 0) {
            sh("cp -f ${backup.absolutePath} $live && chown $OWNER $live && chmod 660 $live")
            return Result(false, 0, backup.absolutePath, "copy back failed, restored: $outC")
        }

        // 4. verify the provider agrees with what we intended
        val after = sh("content query --uri content://sms --projection _id | wc -l").second
            .trim().toIntOrNull()
        val sanity = if (after != null && after == countBefore - deleted) "verified" else "count=$after (expected ${countBefore - deleted})"
        return Result(true, deleted, backup.absolutePath, "deleted $deleted rows, $sanity")
    }

    private fun countRows(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COUNT(*) FROM sms", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun countRows(f: File): Int =
        SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { countRows(it) }
}
