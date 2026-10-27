package com.yk.finance.data

/**
 * The v7 -> v8 migration: deleting and merging accounts.
 *
 * Two new tables, nothing altered. account_aliases lets one account answer to more than
 * one set of digits, which is what a merge has to leave behind for the merged account's
 * messages to keep arriving. ignored_accounts is what stops a deleted account being
 * re-created by the next message about it.
 */
object SchemaV8 {

    val STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `account_aliases` (" +
            "`bank` TEXT NOT NULL, " +
            "`accountToken` TEXT NOT NULL, " +
            "`accountId` INTEGER NOT NULL, " +
            "PRIMARY KEY(`bank`, `accountToken`))",
        "CREATE TABLE IF NOT EXISTS `ignored_accounts` (" +
            "`bank` TEXT NOT NULL, " +
            "`accountToken` TEXT NOT NULL, " +
            "`displayName` TEXT NOT NULL, " +
            "`ignoredAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`bank`, `accountToken`))",
    )
}
