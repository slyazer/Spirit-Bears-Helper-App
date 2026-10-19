package com.macromonitor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairPayloadTest {

    @Test
    fun `parses a full bridge payload`() {
        val payload = PairPayload.parse("macromonitor://pair?h=192.168.1.100&p=8787&n=My%20PC&c=A8EC8594")

        assertEquals("192.168.1.100", payload?.host)
        assertEquals(8787, payload?.port)
        assertEquals("My PC", payload?.name)
        assertEquals("A8EC8594", payload?.code)
        assertEquals("http://192.168.1.100:8787", payload?.baseUrl)
    }

    @Test
    fun `code is optional and defaults to null`() {
        assertNull(PairPayload.parse("macromonitor://pair?h=10.0.0.2&p=9000&n=PC")?.code)
    }

    @Test
    fun `blank code is treated as absent`() {
        assertNull(PairPayload.parse("macromonitor://pair?h=10.0.0.2&c=")?.code)
        assertNull(PairPayload.parse("macromonitor://pair?h=10.0.0.2&c=%20%20")?.code)
    }

    @Test
    fun `missing or non-numeric port falls back to the default`() {
        assertEquals(8787, PairPayload.parse("macromonitor://pair?h=10.0.0.2")?.port)
        assertEquals(8787, PairPayload.parse("macromonitor://pair?h=10.0.0.2&p=abc")?.port)
        assertEquals(9000, PairPayload.parse("macromonitor://pair?h=10.0.0.2&p=9000")?.port)
    }

    @Test
    fun `missing or blank name falls back to the default`() {
        assertEquals("Macro Monitor Bridge", PairPayload.parse("macromonitor://pair?h=10.0.0.2")?.name)
        assertEquals("Macro Monitor Bridge", PairPayload.parse("macromonitor://pair?h=10.0.0.2&n=")?.name)
        assertEquals("Macro Monitor Bridge", PairPayload.parse("macromonitor://pair?h=10.0.0.2&n=%20")?.name)
    }

    @Test
    fun `plus signs decode to spaces, matching the bridge urlencode output`() {
        // bridge/app/pairing.py builds the query with urlencode(), which emits '+' for a space.
        val payload = PairPayload.parse("macromonitor://pair?h=10.0.0.2&n=Work+PC")

        assertEquals("Work PC", payload?.name)
    }

    @Test
    fun `percent-encoded UTF-8 in the name is decoded`() {
        val payload = PairPayload.parse("macromonitor://pair?h=10.0.0.2&n=%D0%9C%D0%B0%D0%BA%D1%81%D0%B8%D0%BC")

        assertEquals("Максим", payload?.name)
    }

    @Test
    fun `an invalid escape is kept literally rather than crashing`() {
        val payload = PairPayload.parse("macromonitor://pair?h=10.0.0.2&n=100%25%zz")

        assertEquals("100%%zz", payload?.name)
    }

    @Test
    fun `missing or blank host is rejected`() {
        assertNull(PairPayload.parse("macromonitor://pair"))
        assertNull(PairPayload.parse("macromonitor://pair?h="))
        assertNull(PairPayload.parse("macromonitor://pair?h=%20%20"))
    }

    @Test
    fun `wrong scheme or host is rejected`() {
        assertNull(PairPayload.parse("https://pair?h=10.0.0.2"))
        assertNull(PairPayload.parse("macromonitor://other?h=10.0.0.2"))
        assertNull(PairPayload.parse("macromonitor:/pair?h=10.0.0.2"))
    }

    @Test
    fun `a scheme that merely starts with macromonitor is rejected`() {
        assertNull(PairPayload.parse("macromonitorX://pair?h=10.0.0.2&c=A8EC8594"))
        assertNull(PairPayload.parse("macromonitorEVIL://pair?h=10.0.0.2&c=A8EC8594"))
        assertNull(PairPayload.parse("MACROMONITORX://PAIR?h=10.0.0.2"))
    }

    @Test
    fun `scheme and host match case-insensitively`() {
        val payload = PairPayload.parse("MACROMONITOR://PAIR?h=10.0.0.2")

        assertEquals("10.0.0.2", payload?.host)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val payload = PairPayload.parse("  macromonitor://pair?h=10.0.0.2&c=CODE1234  ")

        assertEquals("CODE1234", payload?.code)
    }

    @Test
    fun `an optional port in the authority is ignored, as android Uri host would`() {
        val payload = PairPayload.parse("macromonitor://pair:9999?h=10.0.0.2&p=8787")

        assertEquals("10.0.0.2", payload?.host)
        assertEquals(8787, payload?.port)
    }

    @Test
    fun `a fragment is never treated as part of the query`() {
        val payload = PairPayload.parse("macromonitor://pair?h=10.0.0.2&c=CODE1234#fragment")

        assertEquals("CODE1234", payload?.code)
    }

    @Test
    fun `the first value wins when a key repeats, like Uri getQueryParameter`() {
        val payload = PairPayload.parse("macromonitor://pair?h=10.0.0.2&h=10.0.0.3&c=FIRST123")

        assertEquals("10.0.0.2", payload?.host)
        assertEquals("FIRST123", payload?.code)
    }

    @Test
    fun `null, blank and malformed input return null`() {
        assertNull(PairPayload.parse(null))
        assertNull(PairPayload.parse(""))
        assertNull(PairPayload.parse("   "))
        assertNull(PairPayload.parse("not a uri"))
        assertNull(PairPayload.parse("macromonitor://"))
    }
}
