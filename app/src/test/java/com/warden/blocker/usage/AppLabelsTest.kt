package com.warden.blocker.usage

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLabelsTest {

    @Test fun lastSegmentCapitalized() {
        assertEquals("Music", fallbackAppLabel("com.spotify.music"))
        assertEquals("Katana", fallbackAppLabel("com.facebook.katana"))
    }

    @Test fun skipsGenericTail() {
        assertEquals("Instagram", fallbackAppLabel("com.instagram.android"))
        assertEquals("Example", fallbackAppLabel("org.example.mobile.app"))
    }

    @Test fun knownSystemApps() {
        assertEquals("Google", fallbackAppLabel("com.google.android.googlequicksearchbox"))
        assertEquals("Play Store", fallbackAppLabel("com.android.vending"))
    }

    @Test fun oddInput() {
        assertEquals("My app", fallbackAppLabel("com.example.my_app"))
        assertEquals("Android", fallbackAppLabel("android"))
        assertEquals("", fallbackAppLabel(""))
    }
}
