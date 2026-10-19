package com.macromonitor.app.data.api

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the JSON contract of `GET /api/v1/roblox/population`. */
class PopulationDtoGsonTest {

    private val gson = Gson()

    @Test
    fun `parses a populated response`() {
        val json = """
            {
              "available": true,
              "playing": 3,
              "max_players": 6,
              "captured_at": "2026-10-18T12:00:00+00:00",
              "received_at": "2026-10-18T12:00:01+00:00",
              "age_seconds": 12,
              "stale": false,
              "source": "roblox-web-screenshot",
              "detail": null
            }
        """.trimIndent()

        val dto = gson.fromJson(json, PopulationDto::class.java)

        assertTrue(dto.available)
        assertEquals(3, dto.playing)
        assertEquals(6, dto.maxPlayers)
        assertEquals(12, dto.ageSeconds)
        assertEquals(false, dto.stale)
        assertEquals("roblox-web-screenshot", dto.source)
        assertNull(dto.detail)
    }

    @Test
    fun `parses an empty (not yet captured) response`() {
        val json = """
            {
              "available": false,
              "playing": null,
              "max_players": null,
              "stale": false,
              "detail": "No population sample has been captured yet."
            }
        """.trimIndent()

        val dto = gson.fromJson(json, PopulationDto::class.java)

        assertEquals(false, dto.available)
        assertNull(dto.playing)
        assertNull(dto.maxPlayers)
        assertEquals("No population sample has been captured yet.", dto.detail)
    }

    @Test
    fun `a stale last-known sample keeps its counts`() {
        val json = """
            { "available": true, "playing": 5, "max_players": 6, "stale": true }
        """.trimIndent()

        val dto = gson.fromJson(json, PopulationDto::class.java)

        assertTrue(dto.available)
        assertTrue(dto.stale)
        assertEquals(5, dto.playing)
        assertEquals(6, dto.maxPlayers)
    }
}
