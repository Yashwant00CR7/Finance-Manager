package com.yk.finance

import com.yk.finance.data.SchemaV4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The v3 -> v4 migration, held against the schema Room itself exported.
 *
 * Room only validates a migration when it opens a real database, which on this app
 * means on the one phone holding the only copy of the ledger, and the failure mode is a
 * crash on launch. The classic bug - a column added to an entity and forgotten in the
 * migration - is catchable here on the desktop for free, which is the same bargain
 * [MigrationTest] makes for v2 -> v3.
 */
class MigrationV4Test {

    private val schemaDir = File("schemas/com.yk.finance.data.AppDatabase")
    private val sql = SchemaV4.STATEMENTS.joinToString("\n")

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
    fun `every column added in v4 is created by the migration`() {
        val before = columnsByTable(3)
        val after = columnsByTable(4)

        after.forEach { (table, columns) ->
            val existing = before[table] ?: return@forEach
            (columns - existing).forEach { added ->
                assertTrue(
                    "v4 adds $table.$added but the migration never creates it",
                    sql.contains("ALTER TABLE `$table` ADD COLUMN `$added`"),
                )
            }
        }
    }

    @Test
    fun `v4 adds exactly the three columns this release is about`() {
        val before = columnsByTable(3)
        val after = columnsByTable(4)
        val added = after.flatMap { (table, columns) ->
            (columns - (before[table] ?: emptySet())).map { "$table.$it" }
        }.toSet()
        assertEquals(
            setOf("categories.sharing", "transactions.splitGroupId", "transactions.owedBy"),
            added,
        )
    }

    @Test
    fun `v4 adds no tables at all`() {
        assertEquals(emptySet<String>(), columnsByTable(4).keys - columnsByTable(3).keys)
    }

    @Test
    fun `the migration creates nothing the schema does not declare`() {
        val v4 = columnsByTable(4)
        val v3 = columnsByTable(3)
        Regex("ALTER TABLE `(\\w+)` ADD COLUMN `(\\w+)`").findAll(sql).forEach { match ->
            val (table, column) = match.destructured
            assertTrue(
                "migration adds $table.$column, which v4 does not declare",
                v4[table]?.contains(column) == true,
            )
            assertFalse(
                "migration adds $table.$column, which v3 already had",
                v3[table]?.contains(column) == true,
            )
        }
    }

    @Test
    fun `the sharing default matches what Room will check for`() {
        // Room compares an entity's declared default against the live database and
        // rejects the file at open time when they disagree, so a mismatch here is a
        // crash on launch rather than a subtle wrongness.
        val exported = File(schemaDir, "4.json").readText()
        val declared = Regex(
            "\"columnName\":\\s*\"sharing\".*?\"defaultValue\":\\s*\"([^\"]+)\"",
            RegexOption.DOT_MATCHES_ALL,
        ).find(exported)?.groupValues?.get(1)
        assertEquals("'NONE'", declared)
        assertTrue(sql.contains("ADD COLUMN `sharing` TEXT NOT NULL DEFAULT 'NONE'"))
    }

    @Test
    fun `the split group is indexed, because every group read looks it up`() {
        assertTrue(File(schemaDir, "4.json").readText().contains("index_transactions_splitGroupId"))
        assertTrue(sql.contains("ON `transactions` (`splitGroupId`)"))
    }

    @Test
    fun `nothing in the migration destroys data`() {
        // The one outcome worse than a failed migration is a silently emptied ledger.
        listOf("DROP TABLE", "DROP COLUMN", "DELETE FROM", "TRUNCATE", "UPDATE ").forEach { verb ->
            assertFalse(
                "migration contains a destructive statement: $verb",
                sql.uppercase().contains(verb),
            )
        }
    }

    @Test
    fun `no existing row is reinterpreted on the day you install it`() {
        // Transactions already filed under "For Friend Return Later" were counted as
        // spending in months that have been read and closed. Converting them into debts
        // now would move those totals to assert a balance the app was never actually
        // tracking, so the migration deliberately touches no row.
        assertTrue(
            "the migration must be additive only",
            SchemaV4.STATEMENTS.all { it.startsWith("ALTER TABLE") || it.startsWith("CREATE INDEX") },
        )
    }
}
