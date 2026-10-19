package com.macromonitor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PopulationStatusTest {

    @Test
    fun `formats a plain playing over max`() {
        assertEquals("3/6", PopulationStatus.label(3, 6))
        assertEquals("0/6", PopulationStatus.label(0, 6))
        assertEquals("6/6", PopulationStatus.label(6, 6))
    }

    @Test
    fun `missing values produce no label`() {
        assertNull(PopulationStatus.label(null, 6))
        assertNull(PopulationStatus.label(3, null))
        assertNull(PopulationStatus.label(null, null))
    }

    @Test
    fun `out of range values are rejected, not rendered`() {
        assertNull(PopulationStatus.label(-1, 6)) // negative
        assertNull(PopulationStatus.label(7, 6)) // playing > max
        assertNull(PopulationStatus.label(3, 0)) // max below 1
        assertNull(PopulationStatus.label(1, -5)) // nonsense
    }

    @Test
    fun `isLive requires available flag and a valid pair`() {
        assertTrue(PopulationStatus.isLive(available = true, playing = 3, max = 6))
        assertFalse(PopulationStatus.isLive(available = false, playing = 3, max = 6))
        assertFalse(PopulationStatus.isLive(available = true, playing = null, max = 6))
        assertFalse(PopulationStatus.isLive(available = true, playing = 9, max = 6))
    }
}
