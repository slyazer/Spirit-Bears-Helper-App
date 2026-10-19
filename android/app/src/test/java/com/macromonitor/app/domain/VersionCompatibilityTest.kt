package com.macromonitor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VersionCompatibilityTest {

    @Test
    fun `matching api version is compatible and needs no warning`() {
        val result = VersionCompatibility.evaluate(VersionCompatibility.SUPPORTED_API_VERSION)

        assertEquals(VersionCompatibility.Result.Compatible, result)
        assertNull(VersionCompatibility.message(result, VersionCompatibility.SUPPORTED_API_VERSION, "0.2.0"))
    }

    @Test
    fun `matching api version with surrounding whitespace is compatible`() {
        val result = VersionCompatibility.evaluate(" ${VersionCompatibility.SUPPORTED_API_VERSION} ")

        assertEquals(VersionCompatibility.Result.Compatible, result)
    }

    @Test
    fun `different api version is incompatible with an explanatory message`() {
        val result = VersionCompatibility.evaluate("v2")

        assertEquals(VersionCompatibility.Result.Incompatible, result)
        val message = VersionCompatibility.message(result, "v2", "0.9.0")
        assertNotNull("an incompatible bridge must always be explained", message)
        // The message must identify both sides so the user knows what to update.
        assert(message!!.contains(VersionCompatibility.SUPPORTED_API_VERSION))
        assert(message.contains("v2"))
        assert(message.contains("0.9.0"))
    }

    @Test
    fun `incompatible message never null even when versions are unknown`() {
        val message = VersionCompatibility.message(VersionCompatibility.Result.Incompatible, null, null)

        assertNotNull(message)
    }

    @Test
    fun `missing api version is unknown, non-blocking, and explained`() {
        val result = VersionCompatibility.evaluate(null)

        assertEquals(VersionCompatibility.Result.Unknown, result)
        val message = VersionCompatibility.message(result, null, "0.1.0")
        assertNotNull("an unknown bridge version should still be surfaced", message)
        assert(message!!.contains("0.1.0"))
    }

    @Test
    fun `blank api version is unknown`() {
        assertEquals(VersionCompatibility.Result.Unknown, VersionCompatibility.evaluate(""))
        assertEquals(VersionCompatibility.Result.Unknown, VersionCompatibility.evaluate("   "))
    }

    @Test
    fun `supported api version stays pinned to the bridge contract`() {
        // The bridge publishes api_version in GET /api/v1/info (bridge/app/models.py).
        // Changing this constant is a breaking contract change: bump both sides.
        assertEquals("v1", VersionCompatibility.SUPPORTED_API_VERSION)
    }
}
