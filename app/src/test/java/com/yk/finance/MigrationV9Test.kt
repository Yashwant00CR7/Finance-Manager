package com.yk.finance

import com.yk.finance.data.SchemaV9
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The v8 -> v9 migration: the seen-messages table behind the inbox catch-up.
 *
 * Checked against the schema Room exported, because Room validates a migrated database
 * against exactly that at open time.
 */
class MigrationV9Test {

    private val schemaDir = File("schemas/com.yk.finance.data.AppDatabase")
    private val sql = SchemaV9.STATEMENTS.joinToString("\n")

    private fun schema(version: Int): String {
        val file = File(schemaDir, "$version.json")
        assertTrue("missing exported schema $version.json - build the app first", file.exists())
        return file.readText()
    }

    private fun createSqlByTable(version: Int): Map<String, String> {
        val entry = Regex(""""tableName"\s*:\s*"(\w+)"\s*,\s*"createSql"\s*:\s*"([^"]+)"""")
        return entry.findAll(schema(version)).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `v9 adds exactly the seen-messages table`() {
        val added = createSqlByTable(9).keys - createSqlByTable(8).keys
        assertEquals(setOf("seen_messages"), added)
    }

    @Test
    fun `the table the migration creates is the table Room expects`() {
        val roomSql = createSqlByTable(9).getValue("seen_messages").replace("\${TABLE_NAME}", "seen_messages")
        assertTrue("migration does not create seen_messages as Room declares it:\n$roomSql", sql.contains(roomSql))
    }

    @Test
    fun `the index the migration creates is the index Room expects`() {
        val index = Regex(""""createSql"\s*:\s*"(CREATE INDEX[^"]+seen_messages[^"]+)"""")
            .find(schema(9))!!.groupValues[1]
            .replace("\${TABLE_NAME}", "seen_messages")
        assertTrue("migration does not create the index Room declares:\n$index", sql.contains(index))
    }

    @Test
    fun `nothing that already exists is touched`() {
        assertTrue(SchemaV9.STATEMENTS.all { it.startsWith("CREATE TABLE IF NOT EXISTS") || it.startsWith("CREATE INDEX IF NOT EXISTS") })
    }
}
