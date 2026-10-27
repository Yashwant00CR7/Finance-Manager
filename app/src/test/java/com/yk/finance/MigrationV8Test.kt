package com.yk.finance

import com.yk.finance.data.SchemaV8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The v7 -> v8 migration: deleting and merging accounts.
 *
 * Checked against the schema Room exported, because Room validates a migrated database
 * against exactly that at open time - a column the migration forgot is a crash on launch
 * for every existing install, and the ledger unreachable until the app is fixed.
 */
class MigrationV8Test {

    private val schemaDir = File("schemas/com.yk.finance.data.AppDatabase")
    private val sql = SchemaV8.STATEMENTS.joinToString("\n")

    private fun createSqlByTable(version: Int): Map<String, String> {
        val file = File(schemaDir, "$version.json")
        assertTrue("missing exported schema $version.json - build the app first", file.exists())
        val entry = Regex(""""tableName"\s*:\s*"(\w+)"\s*,\s*"createSql"\s*:\s*"([^"]+)"""")
        return entry.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `v8 adds exactly the two account tables`() {
        val added = createSqlByTable(8).keys - createSqlByTable(7).keys
        assertEquals(setOf("account_aliases", "ignored_accounts"), added)
    }

    @Test
    fun `every table the migration creates is the table Room expects`() {
        val expected = createSqlByTable(8)
        listOf("account_aliases", "ignored_accounts").forEach { table ->
            val roomSql = expected.getValue(table).replace("\${TABLE_NAME}", table)
            assertTrue("migration does not create $table as Room declares it:\n$roomSql", sql.contains(roomSql))
        }
    }

    @Test
    fun `nothing that already exists is touched`() {
        assertTrue(SchemaV8.STATEMENTS.all { it.startsWith("CREATE TABLE IF NOT EXISTS") })
    }
}
