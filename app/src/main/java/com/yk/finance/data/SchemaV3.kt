package com.yk.finance.data

/**
 * The v2 -> v3 migration, as data rather than code.
 *
 * Kept as a plain list of statements so a JVM test can check it against the schema Room
 * exports, without an emulator. The classic migration bug is a field added to an entity
 * and forgotten here - Room only catches that at open time, on a real database, which
 * in this app means on the one phone holding the only copy of the ledger.
 *
 * Additive only. Nothing in this list drops, renames or rewrites anything.
 */
object SchemaV3 {

    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE `categories` ADD COLUMN `iconKey` TEXT",
        "ALTER TABLE `categories` ADD COLUMN `colourHex` TEXT",
        "ALTER TABLE `accounts` ADD COLUMN `iconKey` TEXT",
        "ALTER TABLE `accounts` ADD COLUMN `colourHex` TEXT",
        // Null on every existing row, which is exactly right: they become standing
        // limits that apply to every cycle, which is what they have always meant.
        "ALTER TABLE `budgets` ADD COLUMN `periodStart` INTEGER",
        "CREATE TABLE IF NOT EXISTS `cycle_history` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`startMillis` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
            "`inferred` INTEGER NOT NULL, `txnId` INTEGER)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_cycle_history_startMillis` " +
            "ON `cycle_history` (`startMillis`)",
    )
}
