package com.yk.finance

import com.yk.finance.data.SchemaV5
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The v4 -> v5 migration, held against the schema Room itself exported.
 *
 * Same bargain as [MigrationV4Test]: Room only validates a migration when it opens a
 * real database, which on this app is the one phone holding the only copy of the
 * ledger, and the failure mode there is a crash on launch.
 */
class MigrationV5Test {

    private val schemaDir = File("schemas/com.yk.finance.data.AppDatabase")
    private val sql = SchemaV5.STATEMENTS.joinToString("\n")

    /** See MigrationTest: org.json is an unimplemented stub in Android unit tests. */
    private fun columnsByTable(version: Int): Map<String, Set<String>> {
        val file = File(schemaDir, "$version.json")
        assertTrue("missing exported schema $version.json - build the app first", file.exists())
        val text = file.readText()

        val tableName = Regex("\"tableName\"\\s*:\\s*\"(\\w+)\"")
        val columnName = Regex("\"columnName\"\\s*:\\s*\"(\\w+)\"")

        val tables = tableName.findAll(text).toList()
        assertTrue("no tables found in $version.json", tables.isNotEmpty())

        return tables.mapIndexed { index, match ->
            val from = match.range.last
            val to = tables.getOrNull(index + 1)?.range?.first ?: text.length
            match.groupValues[1] to columnName.findAll(text.substring(from, to))
                .map { it.groupValues[1] }
                .toSet()
        }.toMap()
    }

    @Test
    fun `v5 adds exactly the one column this release is about`() {
        val before = columnsByTable(4)
        val after = columnsByTable(5)
        val added = after.flatMap { (table, columns) ->
            (columns - (before[table] ?: emptySet())).map { "$table.$it" }
        }.toSet()
        assertEquals(setOf("transactions.categoryWasInferred"), added)
    }

    @Test
    fun `v5 adds no tables at all`() {
        assertEquals(emptySet<String>(), columnsByTable(5).keys - columnsByTable(4).keys)
    }

    @Test
    fun `the migration creates nothing the schema does not declare`() {
        val v5 = columnsByTable(5)
        val v4 = columnsByTable(4)
        Regex("ALTER TABLE `(\\w+)` ADD COLUMN `(\\w+)`").findAll(sql).forEach { match ->
            val (table, column) = match.destructured
            assertTrue(
                "migration adds $table.$column, which v5 does not declare",
                v5[table]?.contains(column) == true,
            )
            assertFalse(
                "migration adds $table.$column, which v4 already had",
                v4[table]?.contains(column) == true,
            )
        }
    }

    @Test
    fun `the default matches what Room will check for`() {
        // Room compares the entity's declared default against the live database and
        // rejects the file at open time when they disagree - a crash on launch, not a
        // subtle wrongness.
        val exported = File(schemaDir, "5.json").readText()
        val declared = Regex(
            "\"columnName\":\\s*\"categoryWasInferred\".*?\"defaultValue\":\\s*\"([^\"]+)\"",
            RegexOption.DOT_MATCHES_ALL,
        ).find(exported)?.groupValues?.get(1)
        assertEquals("0", declared)
        assertTrue(sql.contains("ADD COLUMN `categoryWasInferred` INTEGER NOT NULL DEFAULT 0"))
    }

    @Test
    fun `nothing in the migration destroys data`() {
        listOf("DROP TABLE", "DROP COLUMN", "DELETE FROM", "TRUNCATE", "UPDATE ").forEach { verb ->
            assertFalse(
                "migration contains a destructive statement: $verb",
                sql.uppercase().contains(verb),
            )
        }
    }

    @Test
    fun `every existing row arrives as not-guessed, which is true of all of them`() {
        // The default is a fact rather than a convenience: nothing in the ledger was
        // ever guessed before this version existed, so the whole of history is
        // eligible to train on from the first launch.
        assertTrue(SchemaV5.STATEMENTS.all { it.startsWith("ALTER TABLE") })
        assertTrue(sql.contains("DEFAULT 0"))
    }
}
