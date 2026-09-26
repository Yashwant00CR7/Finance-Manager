package com.yk.finance.data

/**
 * The v5 -> v6 migration: remembering which researched patterns a person has accepted.
 *
 * Same shape and same reason as [SchemaV3], [SchemaV4] and [SchemaV5] - a plain list a JVM
 * test can hold against the schema Room exports.
 *
 * Two changes, both additive.
 *
 * `confirmed_patterns` is the trust store. Most of the bank patterns this app ships were
 * written from samples found in public sources rather than from messages that arrived on
 * the author's own phone, and no amount of care makes a stranger's two-year-old forum post
 * the same evidence as a live alert. Such a pattern parses into the review tray the first
 * time it fires, and books by itself only once the person holding the phone has agreed
 * that the figures are right. That agreement is a row here.
 *
 * It lives in the database rather than in SharedPreferences for one concrete reason: a
 * backup in this app is a copy of the database file, so anything outside it is lost on
 * restore. Trust is a property of the account and its bank, not of the handset, and having
 * to re-confirm every pattern after restoring a backup would be a bug.
 *
 * `pending_reviews.patternId` is what turns a tray entry from a question into a
 * confirmation. Null keeps its old meaning - nothing understood this message, type it in
 * yourself - while a value names the pattern that did understand it, so the tray can show
 * the figures and offer a single Confirm. Nothing else about the parse is stored: the raw
 * message is already there, and re-reading it with today's patterns is both cheaper than
 * nine more columns and safer, because what the user confirms is then always what the
 * current code actually produces.
 */
object SchemaV6 {

    val STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `confirmed_patterns` (" +
            "`patternId` TEXT NOT NULL, " +
            "`bank` TEXT NOT NULL, " +
            "`confirmedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`patternId`))",
        "ALTER TABLE `pending_reviews` ADD COLUMN `patternId` TEXT",
    )
}
