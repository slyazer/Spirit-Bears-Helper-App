package com.macromonitor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroCommandsTest {

    @Test
    fun `every allowlisted command normalizes to itself`() {
        for (command in MacroCommands.ALLOWED) {
            assertEquals(command, MacroCommands.normalize(command))
            assertEquals(command, MacroCommands.normalize("?$command"))
            assertEquals(command, MacroCommands.normalize("  $command  "))
            assertEquals(command, MacroCommands.normalize(command.uppercase()))
        }
    }

    @Test
    fun `the quick action bar only offers allowed commands`() {
        for (entry in MacroCommands.QUICK) {
            val normalized = MacroCommands.normalize(entry)
            assertNotNull("quick action '$entry' must be sendable", normalized)
            assertTrue(normalized in MacroCommands.ALLOWED)
        }
    }

    @Test
    fun `a leading question mark is optional`() {
        assertEquals("start", MacroCommands.normalize("start"))
        assertEquals("start", MacroCommands.normalize("?start"))
    }

    @Test
    fun `surrounding whitespace and casing are ignored`() {
        assertEquals("pause", MacroCommands.normalize("  PaUsE  "))
    }

    @Test
    fun `unknown commands are rejected`() {
        assertNull(MacroCommands.normalize("hack"))
        assertNull(MacroCommands.normalize("?hack"))
        assertNull(MacroCommands.normalize("st@rt"))
        assertNull(MacroCommands.normalize("start; rm -rf /"))
        assertNull(MacroCommands.normalize("start stop"))
    }

    @Test
    fun `blank input is rejected`() {
        assertNull(MacroCommands.normalize(""))
        assertNull(MacroCommands.normalize("   "))
        assertNull(MacroCommands.normalize("?"))
    }
}
