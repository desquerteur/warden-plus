package com.warden.blocker.allow

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppCategoriesTest {

    @Suppress("DEPRECATION")
    private val isGameFlag = ApplicationInfo.FLAG_IS_GAME

    @Test fun categoryGameIsAGame() {
        assertEquals(AppCategory.GAME, AppCategories.detect(ApplicationInfo.CATEGORY_GAME, 0))
    }

    @Test fun olderIsGameFlagIsAGameToo() {
        assertEquals(AppCategory.GAME, AppCategories.detect(ApplicationInfo.CATEGORY_UNDEFINED, isGameFlag))
        // The flag wins even when the app claims another category.
        assertEquals(AppCategory.GAME, AppCategories.detect(ApplicationInfo.CATEGORY_SOCIAL, isGameFlag))
    }

    @Test fun mapsEachBlockableCategory() {
        assertEquals(AppCategory.SOCIAL, AppCategories.detect(ApplicationInfo.CATEGORY_SOCIAL, 0))
        assertEquals(AppCategory.VIDEO, AppCategories.detect(ApplicationInfo.CATEGORY_VIDEO, 0))
        assertEquals(AppCategory.AUDIO, AppCategories.detect(ApplicationInfo.CATEGORY_AUDIO, 0))
        assertEquals(AppCategory.NEWS, AppCategories.detect(ApplicationInfo.CATEGORY_NEWS, 0))
        assertEquals(AppCategory.IMAGE, AppCategories.detect(ApplicationInfo.CATEGORY_IMAGE, 0))
    }

    @Test fun unblockableCategoriesAreNone() {
        assertNull(AppCategories.detect(ApplicationInfo.CATEGORY_UNDEFINED, 0))
        assertNull(AppCategories.detect(ApplicationInfo.CATEGORY_MAPS, 0))
        assertNull(AppCategories.detect(ApplicationInfo.CATEGORY_PRODUCTIVITY, 0))
        assertNull(AppCategories.detect(42, ApplicationInfo.FLAG_SYSTEM))
    }

    @Test fun overrideWinsOverDetection() {
        val overrides = mapOf("a" to AppCategories.NONE, "b" to "GAME")
        assertNull(AppCategories.effective("a", AppCategory.GAME, overrides))
        assertEquals(AppCategory.GAME, AppCategories.effective("b", null, overrides))
        assertEquals(AppCategory.VIDEO, AppCategories.effective("c", AppCategory.VIDEO, overrides))
    }

    @Test fun overridesSurviveStorageAndBadEntriesAreDropped() {
        val overrides = mapOf("com.x.game" to "GAME", "com.y.chess" to AppCategories.NONE)
        assertEquals(overrides, AppCategories.decodeOverrides(AppCategories.encodeOverrides(overrides)))
        assertEquals(
            mapOf("ok" to "SOCIAL"),
            AppCategories.decodeOverrides(setOf("ok=SOCIAL", "=GAME", "nope", "bad=UNKNOWN")),
        )
    }
}
