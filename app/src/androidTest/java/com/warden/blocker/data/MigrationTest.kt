package com.warden.blocker.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs MIGRATION_2_3 on a real SQLite database and lets Room validate the result (needs a device). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WardenDatabase::class.java,
    )

    @Test fun migrate2To3KeepsRowsAndAddsEmptyTimes() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO blocked_items (id, type, value, label, enabled, createdAt, interceptMode, pauseSeconds, " +
                    "customPrompt, openLimitPerDay, dailyLimitMinutes, cooldownMinutes) " +
                    "VALUES (1, 'APP', 'com.example', 'Example', 1, 0, 'PAUSE', 15, NULL, 3, 0, 0)",
            )
            db.execSQL("INSERT INTO schedules (id, name, enabled, daysMask, startMinute, endMinute) VALUES (1, 'Work', 1, 31, 540, 1020)")
        }

        helper.runMigrationsAndValidate(DB, 3, true, WardenDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT label, openLimitPerDay, activeFrom, activeUntil FROM blocked_items").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Example", c.getString(0))
                assertEquals(3, c.getInt(1))
                assertTrue(c.isNull(2) && c.isNull(3))
            }
            db.query("SELECT name, daysMask, onceStartAt, onceEndAt, validFromDay, validUntilDay FROM schedules").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Work", c.getString(0))
                assertEquals(31, c.getInt(1))
                assertTrue((2..5).all(c::isNull))
            }
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
