package com.yk.finance.data

/**
 * The v6 -> v7 migration: auto-learned pattern rules.
 *
 * When an unparsed message is manually recorded from the review tray, a learned pattern
 * is generated from the message structure so that future similar alerts from that bank
 * parse and book automatically without needing human attention.
 */
object SchemaV7 {

    val STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `learned_patterns` (" +
            "`id` TEXT NOT NULL, " +
            "`bank` TEXT NOT NULL, " +
            "`regex` TEXT NOT NULL, " +
            "`direction` TEXT NOT NULL, " +
            "`accountToken` TEXT, " +
            "`isCard` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))",
    )
}
