package com.yk.finance.data

/**
 * The v8 -> v9 migration: catch-up after power saving.
 *
 * One new table, nothing altered. seen_messages records every SMS the app has processed so
 * that the inbox scan, which re-reads messages the live receiver may already have handled,
 * can never ingest one twice. See [SeenMessage].
 */
object SchemaV9 {

    val STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `seen_messages` (" +
            "`bodyKey` TEXT NOT NULL, " +
            "`sentAt` INTEGER NOT NULL, " +
            "`receivedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`bodyKey`, `sentAt`))",
        "CREATE INDEX IF NOT EXISTS `index_seen_messages_receivedAt` ON `seen_messages` (`receivedAt`)",
    )
}
