package com.warden.blocker.ui.times

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Test

/** The clock and calendar shrink to fit narrow dialogs (fold cover display) and never grow. */
class PickerFitTest {

    @Test fun fitsAtNormalSize() {
        assertEquals(1f, shrinkScale(available = 360, design = 360))
        assertEquals(1f, shrinkScale(available = 500, design = 360))
        assertEquals(1f, shrinkScale(available = Constraints.Infinity, design = 360))
    }

    @Test fun shrinksOnNarrowScreens() {
        // 344dp cover display minus 2 × 8dp margin = 328dp for the 360dp calendar.
        assertEquals(328f / 360f, shrinkScale(available = 328, design = 360), 0.0001f)
        // 320dp screen: 304dp of room.
        assertEquals(304f / 360f, shrinkScale(available = 304, design = 360), 0.0001f)
    }

    @Test fun noDesignWidthMeansNoScaling() {
        assertEquals(1f, shrinkScale(available = 100, design = 0))
    }
}
