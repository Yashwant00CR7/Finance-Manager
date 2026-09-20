package com.yk.finance.data

/**
 * The v3 -> v4 migration, as data rather than code.
 *
 * Same shape and same reason as [SchemaV3]: kept as a plain list so a JVM test can hold
 * it against the schema Room exports. The classic migration bug is a field added to an
 * entity and forgotten here, which Room only catches when it opens a real database - in
 * this app, the one phone holding the only copy of the ledger.
 *
 * Additive only. Every existing row comes through with `sharing = 'NONE'` and no split
 * group, which is precisely what an unsplit transaction in an unshared category is, so
 * nothing needs rewriting and no figure moves on the day you install it.
 *
 * Deliberately absent: anything that touches transactions already filed under "For
 * Friend Return Later". Those were counted as spending when you read those months, and
 * converting them into debts now would move totals you have already closed to assert a
 * balance the app was never actually tracking.
 */
object SchemaV4 {

    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE `categories` ADD COLUMN `sharing` TEXT NOT NULL DEFAULT 'NONE'",
        "ALTER TABLE `transactions` ADD COLUMN `splitGroupId` TEXT",
        "ALTER TABLE `transactions` ADD COLUMN `owedBy` TEXT",
        "CREATE INDEX IF NOT EXISTS `index_transactions_splitGroupId` " +
            "ON `transactions` (`splitGroupId`)",
    )
}
