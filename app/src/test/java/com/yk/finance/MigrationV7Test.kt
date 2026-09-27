package com.yk.finance

import com.yk.finance.data.SchemaV7
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The v6 -> v7 migration: auto-learned message pattern templates.
 */
class MigrationV7Test {

    private val schemaDir = File("schemas/com.yk.finance.data.AppDatabase")
    private val sql = SchemaV7.STATEMENTS.joinToString("\n")

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
    fun `v7 adds exactly the learned_patterns table`() {
        val before = columnsByTable(6)
        val after = columnsByTable(7)
        val newTables = after.keys - before.keys
        assertEquals(setOf("learned_patterns"), newTables)
    }

    @Test
    fun `learned_patterns table declares all expected columns`() {
        val v7 = columnsByTable(7)
        val columns = v7["learned_patterns"]
        assertEquals(
            setOf("id", "bank", "regex", "direction", "accountToken", "isCard", "createdAt"),
            columns,
        )
    }

    @Test
    fun `the migration statement creates the learned_patterns table`() {
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `learned_patterns`"))
    }
}
