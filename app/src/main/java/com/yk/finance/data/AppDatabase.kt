package com.yk.finance.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yk.finance.backup.Snapshot
import com.yk.finance.parser.Channel
import com.yk.finance.parser.Direction

class Converters {
    @TypeConverter fun directionToString(d: Direction): String = d.name
    @TypeConverter fun stringToDirection(s: String): Direction = Direction.valueOf(s)
    @TypeConverter fun channelToString(c: Channel): String = c.name
    @TypeConverter fun stringToChannel(s: String): Channel = Channel.valueOf(s)
    @TypeConverter fun kindToString(k: AccountKind): String = k.name
    @TypeConverter fun stringToKind(s: String): AccountKind = AccountKind.valueOf(s)
    @TypeConverter fun sourceToString(s: TxnSource): String = s.name
    @TypeConverter fun stringToSource(s: String): TxnSource = TxnSource.valueOf(s)
    @TypeConverter fun sharingToString(s: Sharing): String = s.name
    @TypeConverter fun stringToSharing(s: String): Sharing = Sharing.valueOf(s)
}

/**
 * Schema 2 groundwork for the importer.
 *
 * Every column and table the import needs lands in this one migration, including the
 * ones no feature uses yet, so that import day involves no schema change at all. A
 * second migration against a full database is exactly the risk this avoids.
 *
 * Note the declared defaults: Room compares an entity's `@ColumnInfo(defaultValue=)`
 * against the live database, so `ADD COLUMN ... NOT NULL DEFAULT 0` must be mirrored
 * in the entity or validation rejects the migrated file at open time.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `aliases` TEXT")
        db.execSQL("ALTER TABLE `categories` ADD COLUMN `isIncome` INTEGER NOT NULL DEFAULT 0")

        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `importBatchId` TEXT")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `fingerprint` TEXT")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `timeWasInferred` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `noSmsCounterpart` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_fingerprint` ON `transactions` (`fingerprint`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_importBatchId` ON `transactions` (`importBatchId`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `import_batches` (" +
                "`id` TEXT NOT NULL, `fileName` TEXT NOT NULL, `importedAt` INTEGER NOT NULL, " +
                "`profileName` TEXT NOT NULL, `rowsTotal` INTEGER NOT NULL, " +
                "`rowsImported` INTEGER NOT NULL, `rowsQueued` INTEGER NOT NULL, " +
                "`rowsRejected` INTEGER NOT NULL, `reportJson` TEXT NOT NULL, " +
                "`committed` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `imported_rows` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `batchId` TEXT NOT NULL, " +
                "`lineNumber` INTEGER NOT NULL, `rawLine` TEXT NOT NULL, `rawJson` TEXT NOT NULL, " +
                "`txnId` INTEGER, `outcome` TEXT NOT NULL, `reason` TEXT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_imported_rows_batchId` ON `imported_rows` (`batchId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_imported_rows_txnId` ON `imported_rows` (`txnId`)")
    }
}

/**
 * Schema 3: the v2.0 interface.
 *
 * Additive only - five nullable columns and one table. Nothing is dropped, nothing is
 * rewritten, so every existing row survives untouched and the icon/colour columns stay
 * null until Looks seeds them on next launch.
 *
 * cycle_history is the load-bearing part: it is what lets the period arrows walk back
 * through earlier salary cycles, which CycleState alone cannot answer.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SchemaV3.STATEMENTS.forEach(db::execSQL)
    }
}

/**
 * Schema 4: split bills and money owed to you.
 *
 * Three nullable-or-defaulted columns and one index. Nothing is dropped and nothing is
 * rewritten, so every existing row survives with sharing = NONE and no split group -
 * which is exactly what an unsplit transaction is.
 *
 * TxnSource.SETTLEMENT needs nothing here. The enum is persisted as its own name into
 * a plain TEXT column with no CHECK constraint, so a new value is readable the moment
 * the converter knows it.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SchemaV4.STATEMENTS.forEach(db::execSQL)
    }
}

/**
 * Schema 5: the category model's marker.
 *
 * One defaulted column. Every existing row comes through as `categoryWasInferred = 0`,
 * which is true of all of them - nothing in the ledger was guessed before this version
 * existed, so the default is a fact rather than a convenience.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SchemaV5.STATEMENTS.forEach(db::execSQL)
    }
}

/**
 * Schema 6: the trust store for researched bank patterns.
 *
 * One new table and one nullable column, nothing rewritten. Existing tray entries come
 * through with a null `patternId`, which is exactly what they are - messages no pattern
 * could read - so the default states a fact rather than papering over one.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SchemaV6.STATEMENTS.forEach(db::execSQL)
    }
}

@Database(
    entities = [
        Account::class, Txn::class, Category::class, CategoryRule::class,
        Budget::class, PendingReview::class, CycleState::class, BudgetAlert::class,
        ImportBatch::class, ImportedRow::class, CycleBoundary::class,
        ConfirmedPattern::class,
    ],
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): FinanceDao

    companion object {
        const val SCHEMA_VERSION = 6
        const val DB_NAME = "finance.db"

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): AppDatabase {
            snapshotIfMigrationPending(context)
            return Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                // No fallbackToDestructiveMigration. A missing migration must fail loudly;
                // silently wiping a ledger is the one outcome worse than a crash.
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6,
                )
                .build()
        }

        /**
         * Copies the database aside before Room gets a chance to migrate it.
         *
         * Opening the file with plain SQLiteDatabase reads its version without running
         * any migration, which is the only moment a pre-migration copy can be taken.
         * Failure here is deliberately non-fatal: not having a safety net is no reason
         * to refuse to start.
         */
        private fun snapshotIfMigrationPending(context: Context) {
            val file = context.getDatabasePath(DB_NAME)
            if (!file.exists()) return
            val existing = try {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    // Fold the write-ahead log back in, so the copy is the whole database.
                    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                    db.version
                }
            } catch (e: Exception) {
                return
            }
            if (existing in 1 until SCHEMA_VERSION) {
                runCatching { Snapshot.writeAutomatic(context, "pre-migration-v$existing") }
            }
        }

        /** Closes the singleton so the file underneath it can be replaced. */
        fun closeForRestore() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}
