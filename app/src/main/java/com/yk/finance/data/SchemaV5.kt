package com.yk.finance.data

/**
 * The v4 -> v5 migration, as data rather than code.
 *
 * Same shape and same reason as [SchemaV3] and [SchemaV4]: a plain list a JVM test can
 * hold against the schema Room exports, because the classic migration bug is a field
 * added to an entity and forgotten here - and Room only catches that when it opens a
 * real database, which in this app is the one phone holding the only copy of the
 * ledger.
 *
 * One defaulted column, nothing dropped and nothing rewritten. Every existing row
 * arrives as not-inferred, which is accurate rather than merely convenient: nothing in
 * the ledger was ever guessed before this version shipped, so the whole of history is
 * eligible to train on from the first launch.
 */
object SchemaV5 {

    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE `transactions` ADD COLUMN `categoryWasInferred` INTEGER NOT NULL DEFAULT 0",
    )
}
