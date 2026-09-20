package com.yk.finance.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.yk.finance.data.AppDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Whole-database backup and restore.
 *
 * A raw copy of the SQLite file rather than a hand-written serialisation: fidelity is
 * guaranteed by construction, so no column can be forgotten here and silently lost on
 * restore. The cost is that the file is opaque - which is why the CSV export exists
 * alongside it, for when you want to read your data rather than reinstate it.
 */
object Snapshot {

    private const val KEEP_AUTOMATIC = 5

    /** Magic string every SQLite file starts with, minus its terminating NUL. */
    private const val SQLITE_MAGIC = "SQLite format 3"

    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.of("Asia/Kolkata"))

    sealed interface RestoreResult {
        data class Ok(val replacedBytes: Long) : RestoreResult
        data class Rejected(val reason: String) : RestoreResult
    }

    fun automaticDir(context: Context): File =
        File(context.filesDir, "backups").apply { mkdirs() }

    fun suggestedFileName(now: Long = System.currentTimeMillis()): String =
        "finance-backup-" + STAMP.format(Instant.ofEpochMilli(now)) + ".db"

    /** Automatic snapshots, newest first. */
    fun automaticSnapshots(context: Context): List<File> =
        automaticDir(context).listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    /**
     * Folds the write-ahead log back into the main file. Without this a copy can be
     * missing the most recent transactions, which is the classic way a backup turns
     * out to be stale exactly when it is needed.
     */
    fun checkpoint(context: Context) {
        runCatching {
            AppDatabase.get(context).openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }
    }

    /** User-triggered export: checkpoint first, then stream the database out. */
    fun export(context: Context, out: OutputStream): Long {
        checkpoint(context)
        return copyDatabaseTo(context, out)
    }

    /**
     * File copy with no database access at all - safe to call before Room has opened,
     * which is the only point at which a pre-migration snapshot can be taken.
     */
    fun copyDatabaseTo(context: Context, out: OutputStream): Long {
        val file = context.getDatabasePath(AppDatabase.DB_NAME)
        if (!file.exists()) return 0
        return file.inputStream().use { it.copyTo(out) }
    }

    fun writeAutomatic(context: Context, label: String): File {
        val target = File(automaticDir(context), label + "-" + STAMP.format(Instant.now()) + ".db")
        target.outputStream().use { copyDatabaseTo(context, it) }
        prune(context)
        return target
    }

    private fun prune(context: Context) {
        automaticSnapshots(context).drop(KEEP_AUTOMATIC).forEach { it.delete() }
    }

    /**
     * Replaces the live database with [input].
     *
     * Validated into a staging file first: a truncated copy, or the wrong file
     * entirely, must not be able to destroy a working ledger. The caller is expected
     * to restart the process afterwards, since every open handle then points at a file
     * that no longer exists.
     */
    fun restore(context: Context, input: InputStream): RestoreResult {
        val staging = File(context.cacheDir, "restore-candidate.db")
        staging.delete()
        val bytes = staging.outputStream().use { input.copyTo(it) }
        if (bytes == 0L) {
            staging.delete()
            return RestoreResult.Rejected("the file is empty")
        }

        validate(staging)?.let { problem ->
            staging.delete()
            return RestoreResult.Rejected(problem)
        }

        AppDatabase.closeForRestore()
        val live = context.getDatabasePath(AppDatabase.DB_NAME)
        // A stale -wal or -shm beside a replaced database would be read as part of it.
        File(live.path + "-wal").delete()
        File(live.path + "-shm").delete()
        live.parentFile?.mkdirs()
        staging.inputStream().use { source -> live.outputStream().use { source.copyTo(it) } }
        staging.delete()
        return RestoreResult.Ok(bytes)
    }

    /** Null when the file is a database this app can actually open. */
    private fun validate(candidate: File): String? {
        val header = ByteArray(16)
        candidate.inputStream().use {
            if (it.read(header) < header.size) return "the file is too small to be a backup"
        }
        if (String(header, Charsets.US_ASCII).take(SQLITE_MAGIC.length) != SQLITE_MAGIC) {
            return "that is not a SQLite database"
        }
        return try {
            SQLiteDatabase.openDatabase(candidate.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val tables = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table'",
                    null,
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                when {
                    "transactions" !in tables || "accounts" !in tables ->
                        "that database did not come from this app"
                    db.version > AppDatabase.SCHEMA_VERSION ->
                        "that backup is from a newer version of the app (schema " + db.version + ")"
                    db.version < 1 -> "that database has no schema version"
                    else -> null
                }
            }
        } catch (e: Exception) {
            "the file could not be opened: " + e.message
        }
    }
}
