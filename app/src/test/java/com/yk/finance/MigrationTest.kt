package com.yk.finance

import com.yk.finance.data.SchemaV3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The v2 -> v3 migration, checked against the schema Room itself exported.
 *
 * Room only validates a migration when it opens a real database, which on this app
 * means on the one phone holding the only copy of the ledger - and the failure mode is
 * a crash on launch, or worse, a mismatch nobody notices. Comparing the exported v2 and
 * v3 schemas here catches the classic bug on the desktop: a field added to an entity
 * and forgotten in the migration.
 *
 * This is not a substitute for running the migration against a real file, which needs a
 * device. It is the half that can be automated.
 */
class MigrationTest {

    private val schemaDir = File("schemas/com.yk.finance.data.AppDatabase")

    /**
     * Reads the exported schema without a JSON parser.
     *
     * org.json ships as an unimplemented stub in Android unit tests - every method
     * throws - and pulling in a real parser to read two build artefacts would be a
     * dependency for one test. The file is machine-generated with a fixed shape, so
     * splitting on table boundaries and collecting column names is enough.
     */
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

    private val sql = SchemaV3.STATEMENTS.joinToString("\n")

    @Test
    fun `every column added in v3 is created by the migration`() {
        val before = columnsByTable(2)
        val after = columnsByTable(3)

        after.forEach { (table, columns) ->
            val existing = before[table] ?: return@forEach // new tables checked separately
            (columns - existing).forEach { added ->
                assertTrue(
                    "v3 adds $table.$added but the migration never creates it",
                    sql.contains("ALTER TABLE `$table` ADD COLUMN `$added`"),
                )
            }
        }
    }

    @Test
    fun `every table added in v3 is created by the migration`() {
        val added = columnsByTable(3).keys - columnsByTable(2).keys
        assertEquals(setOf("cycle_history"), added)
        added.forEach { table ->
            assertTrue(
                "v3 adds table $table but the migration never creates it",
                sql.contains("CREATE TABLE IF NOT EXISTS `$table`"),
            )
        }
    }

    @Test
    fun `the new table is created with every column the entity declares`() {
        val columns = columnsByTable(3).getValue("cycle_history")
        columns.forEach { column ->
            assertTrue(
                "cycle_history.$column is declared but never created",
                sql.contains("`$column`"),
            )
        }
        assertEquals(setOf("id", "startMillis", "source", "inferred", "txnId"), columns)
    }

    @Test
    fun `the migration creates nothing the schema does not declare`() {
        val v3 = columnsByTable(3)
        val v2 = columnsByTable(2)
        Regex("ALTER TABLE `(\\w+)` ADD COLUMN `(\\w+)`").findAll(sql).forEach { match ->
            val (table, column) = match.destructured
            assertTrue(
                "migration adds $table.$column, which v3 does not declare",
                v3[table]?.contains(column) == true,
            )
            assertFalse(
                "migration adds $table.$column, which v2 already had",
                v2[table]?.contains(column) == true,
            )
        }
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
    fun `existing budgets migrate to standing limits`() {
        // Nullable, with no default: every pre-v3 budget row comes through with a null
        // periodStart, which BudgetResolver reads as "applies to every cycle".
        val statement = SchemaV3.STATEMENTS.single { it.contains("budgets") }
        assertEquals("ALTER TABLE `budgets` ADD COLUMN `periodStart` INTEGER", statement)
        assertFalse("a NOT NULL default would rewrite existing rows", statement.contains("NOT NULL"))
    }

    @Test
    fun `the icon and colour columns are nullable so existing rows are untouched`() {
        listOf("categories", "accounts").forEach { table ->
            listOf("iconKey", "colourHex").forEach { column ->
                val statement = SchemaV3.STATEMENTS.single {
                    it.contains("`$table`") && it.contains("`$column`")
                }
                assertTrue(statement.endsWith("TEXT"))
                assertFalse(statement.contains("NOT NULL"))
            }
        }
    }

    @Test
    fun `the cycle history index is unique so a boundary cannot be recorded twice`() {
        assertTrue(
            sql.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_cycle_history_startMillis`"),
        )
    }
}
