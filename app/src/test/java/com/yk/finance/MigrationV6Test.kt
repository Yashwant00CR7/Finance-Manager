package com.yk.finance

import com.yk.finance.data.SchemaV6
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The v5 -> v6 migration, held against the schema Room itself exported.
 *
 * Same bargain as [MigrationV5Test]: Room only validates a migration when it opens a
 * real database, which on this app is the one phone holding the only copy of the
 * ledger, and the failure mode there is a crash on launch.
 */
class MigrationV6Test {

    private val schemaDir = File("schemas/com.yk.finance.data.AppDatabase")
    private val sql = SchemaV6.STATEMENTS.joinToString("\n")

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
    fun `v6 adds exactly the two tables this release is about`() {
        assertEquals(
            setOf("sender_registry", "gate_state"),
            columnsByTable(6).keys - columnsByTable(5).keys,
        )
    }

    @Test
    fun `v6 adds exactly one column to an existing table`() {
        val before = columnsByTable(5)
        val after = columnsByTable(6)
        val added = after.filterKeys { it in before.keys }.flatMap { (table, columns) ->
            (columns - (before[table] ?: emptySet())).map { "$table.$it" }
        }.toSet()
        assertEquals(setOf("transactions.sender"), added)
    }

    @Test
    fun `the migration creates every table v6 declares, with every column`() {
        // The classic bug: a field added to an entity and forgotten here. Room only
        // notices on a real device, at which point it refuses to open the ledger.
        val v6 = columnsByTable(6)
        listOf("sender_registry", "gate_state").forEach { table ->
            val create = SchemaV6.STATEMENTS.firstOrNull { it.contains("CREATE TABLE IF NOT EXISTS `$table`") }
            assertTrue("migration never creates $table", create != null)
            v6.getValue(table).forEach { column ->
                assertTrue(
                    "migration creates $table without $column",
                    create!!.contains("`$column`"),
                )
            }
        }
    }

    @Test
    fun `the added column is nullable, because the history genuinely has no sender`() {
        // Every row written before this version was ingested without the app recording
        // where it came from. Inferring one from the account would put a guess in the
        // ledger wearing the same clothes as a fact.
        assertTrue(sql.contains("ALTER TABLE `transactions` ADD COLUMN `sender` TEXT"))
        assertFalse("sender must not be NOT NULL", sql.contains("`sender` TEXT NOT NULL"))
    }

    @Test
    fun `the migration enrols nothing`() {
        // Seeding lives in SenderEnrollment so it also covers fresh installs, stays
        // idempotent, and never re-enrols a sender that was removed on purpose.
        assertFalse(sql.uppercase().contains("INSERT"))
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
}
