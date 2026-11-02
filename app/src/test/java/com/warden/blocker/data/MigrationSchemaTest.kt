package com.warden.blocker.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks MIGRATION_2_3 against the schemas Room exported (app/schemas): every column v3 adds
 * is added by exactly one ALTER TABLE with the same type, nullability and default, and nothing
 * else changes — so a v2 database opens as a valid v3 one and keeps all its rows. (The full
 * on-device check is androidTest/MigrationTest, which needs a phone or emulator.)
 */
class MigrationSchemaTest {

    private data class Column(val affinity: String, val notNull: Boolean, val default: String?)

    private fun schema(version: Int): Map<String, Map<String, Column>> {
        val dir = listOf("schemas", "app/schemas").map { File(it, "com.warden.blocker.data.WardenDatabase") }
            .first { it.isDirectory }
        val db = JSONObject(File(dir, "$version.json").readText()).getJSONObject("database")
        val entities = db.getJSONArray("entities")
        return (0 until entities.length()).associate { i ->
            val e = entities.getJSONObject(i)
            val fields = e.getJSONArray("fields")
            e.getString("tableName") to (0 until fields.length()).associate { j ->
                val f = fields.getJSONObject(j)
                f.getString("columnName") to Column(
                    f.getString("affinity"),
                    f.optBoolean("notNull", false),
                    if (f.has("defaultValue")) f.getString("defaultValue") else null,
                )
            }
        }
    }

    private val alter = Regex("""ALTER TABLE (\w+) ADD COLUMN (\w+) (\w+)( NOT NULL)?(?: DEFAULT (\S+))?""")

    @Test fun migration2to3AddsExactlyTheNewColumns() {
        val v2 = schema(2)
        val v3 = schema(3)
        assertEquals("no table added or removed", v2.keys, v3.keys)

        val expected = v3.flatMap { (table, cols) ->
            cols.filterKeys { it !in v2.getValue(table) }.map { (name, col) -> Triple(table, name, col) }
        }.toSet()
        val migrated = WardenDatabase.MIGRATION_2_3_SQL.map { sql ->
            val m = alter.matchEntire(sql) ?: error("Unexpected statement: $sql")
            val (table, name, type, notNull, default) = m.destructured
            Triple(table, name, Column(type, notNull.isNotEmpty(), default.ifEmpty { null }?.trim('\'')))
        }.toSet()
        assertEquals(expected, migrated)
        assertEquals("one statement per column", WardenDatabase.MIGRATION_2_3_SQL.size, migrated.size)

        // Existing columns are untouched.
        v2.forEach { (table, cols) -> cols.forEach { (name, col) -> assertEquals("$table.$name", col, v3.getValue(table)[name]) } }
    }

    @Test fun newColumnsAreOptionalSoOldRowsStayValid() {
        WardenDatabase.MIGRATION_2_3_SQL.forEach { assertTrue(it, !it.contains("NOT NULL")) }
    }
}
