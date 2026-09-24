package com.yk.finance.data

/**
 * The v5 -> v6 migration: per-conversation SMS permissions.
 *
 * Same shape and same reason as [SchemaV3], [SchemaV4] and [SchemaV5] - a plain list a
 * JVM test can hold against the schema Room exports, because the classic migration bug
 * is a field added to an entity and forgotten here, and Room only catches that when it
 * opens a real database, which in this app is the one phone holding the only copy of
 * the ledger.
 *
 * Two new tables and one nullable column. Nothing is dropped, nothing is rewritten and
 * nothing is seeded here: the two known bank headers are enrolled by
 * [com.yk.finance.domain.SenderEnrollment] on launch instead, for the same reason
 * CategorySync is not a migration - it must also run on a fresh install, it must be
 * idempotent, and it must not quietly re-enrol a sender you deliberately removed.
 *
 * Every existing transaction arrives with a null sender, which is accurate rather than
 * convenient: the app genuinely did not record where those rows came from, and
 * inferring it from the account afterwards would put a guess in the ledger wearing the
 * same clothes as a fact.
 */
object SchemaV6 {

    val STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `sender_registry` (" +
            "`header` TEXT NOT NULL, `state` TEXT NOT NULL, `bankKey` TEXT, " +
            "`firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, " +
            "`messageCount` INTEGER NOT NULL, `transactionalCount` INTEGER NOT NULL, " +
            "PRIMARY KEY(`header`))",
        "CREATE TABLE IF NOT EXISTS `gate_state` (" +
            "`id` INTEGER NOT NULL, `mode` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "ALTER TABLE `transactions` ADD COLUMN `sender` TEXT",
    )
}
