package com.macromonitor.app.data.api

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the JSON contract of `GET /api/v1/info` against the DTO the app deserializes. */
class InfoDtoGsonTest {

    private val gson = Gson()

    @Test
    fun `parses the full info response including the handshake fields`() {
        val json = """
            {
              "name": "Macro Monitor Bridge",
              "scheme": "macromonitor",
              "bridge_version": "0.2.0",
              "pairing_enabled": true,
              "accounts": 1,
              "api_version": "v1"
            }
        """.trimIndent()

        val info = gson.fromJson(json, InfoDto::class.java)

        assertEquals("Macro Monitor Bridge", info.name)
        assertEquals("macromonitor", info.scheme)
        assertEquals("0.2.0", info.bridgeVersion)
        assertEquals(true, info.pairingEnabled)
        assertEquals(1, info.accounts)
        assertEquals("v1", info.apiVersion)
    }

    @Test
    fun `missing handshake fields stay null so an older bridge is treated as unknown`() {
        val json = """
            {
              "name": "Macro Monitor Bridge",
              "bridge_version": "0.1.0",
              "pairing_enabled": false,
              "accounts": 0
            }
        """.trimIndent()

        val info = gson.fromJson(json, InfoDto::class.java)

        assertNull(info.scheme)
        assertNull(info.apiVersion)
        assertEquals("0.1.0", info.bridgeVersion)
    }
}
