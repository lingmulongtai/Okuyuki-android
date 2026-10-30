package com.okuyuki.android.tv

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TvLauncherTest {
    @Test fun `SSDP answers from LG TVs give the TV's id and description`() {
        val answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.20:1754/\r\n" +
            "ST: urn:lge-com:service:webos-second-screen:1\r\n" +
            "USN: uuid:9f8e7d6c-1234-5678-9abc-def012345678::urn:lge-com:service:webos-second-screen:1\r\n\r\n"
        val headers = Ssdp.parse(answer)!!
        assertEquals(Ssdp.SERVICE, headers["st"])
        assertEquals("http://192.168.1.20:1754/", headers["location"])
        assertEquals("9f8e7d6c-1234-5678-9abc-def012345678", Ssdp.uuid(headers["usn"]))
        assertNull(Ssdp.parse("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n"))
        assertNull(Ssdp.uuid(null))
        assertNull(Ssdp.uuid("uuid:"))
        assertEquals("[LG] webOS TV OLED65C5PJA",
            Ssdp.friendlyName("<root><device><friendlyName>[LG] webOS TV OLED65C5PJA</friendlyName></device></root>"))
        assertNull(Ssdp.friendlyName("<root/>"))
        assertTrue(String(Ssdp.request).contains("\r\nST: urn:lge-com:service:webos-second-screen:1\r\n"))
    }

    @Test fun `registration asks only for launch rights and launching passes the phone's address`() {
        val first = Json.parseToJsonElement(Ssap.register(null)).jsonObject
        assertEquals("register", first["type"]!!.jsonPrimitive.content)
        val payload = first["payload"]!!.jsonObject
        assertEquals("PROMPT", payload["pairingType"]!!.jsonPrimitive.content)
        assertNull(payload["client-key"])
        assertEquals(listOf("LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP"),
            payload["manifest"]!!.jsonObject["permissions"]!!.jsonArray.map { it.jsonPrimitive.content })
        val known = Json.parseToJsonElement(Ssap.register("abc")).jsonObject["payload"]!!.jsonObject
        assertEquals("abc", known["client-key"]!!.jsonPrimitive.content)

        val params = buildJsonObject { put("host", "192.168.1.10"); put("port", 38765); put("deviceId", "phone") }
        val launch = Json.parseToJsonElement(Ssap.launch(params)).jsonObject
        assertEquals("ssap://system.launcher/launch", launch["uri"]!!.jsonPrimitive.content)
        assertEquals(Ssap.APP_ID, launch["payload"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(params, launch["payload"]!!.jsonObject["params"])
    }

    @Test fun `TV replies are read for prompts, keys and results`() {
        assertTrue(Ssap.reply("""{"type":"response","id":"register_0","payload":{"pairingType":"PROMPT","returnValue":true}}""")!!.prompting)
        assertEquals("k123", Ssap.reply("""{"type":"registered","id":"register_0","payload":{"client-key":"k123"}}""")!!.clientKey)
        val launched = Ssap.reply("""{"type":"response","id":"launch_1","payload":{"returnValue":true,"id":"com.okuyuki.tv"}}""")!!
        assertTrue(launched.succeeded)
        assertFalse(launched.missingApp)
        val missing = Ssap.reply("""{"type":"error","id":"launch_1","error":"404 no such app","payload":{}}""")!!
        assertEquals("404 no such app", missing.problem)
        assertTrue(missing.missingApp)
        val refused = Ssap.reply("""{"type":"response","id":"launch_1","payload":{"returnValue":false,"errorText":"Permission denied"}}""")!!
        assertFalse(refused.succeeded)
        assertEquals("Permission denied", refused.problem)
        assertFalse(refused.missingApp)
        assertNull(Ssap.reply("not json"))
        assertNull(Ssap.reply("""{"id":"x"}"""))
    }

    @Test fun `the phone gives the TV its address on the TV's network`() {
        assertEquals("192.168.1.10", TvRemote.phoneAddress(listOf("10.0.0.5:38765", "192.168.1.10:38765"), "192.168.1.20"))
        assertEquals("10.0.0.5", TvRemote.phoneAddress(listOf("10.0.0.5:38765"), "192.168.1.20"))
        assertNull(TvRemote.phoneAddress(emptyList(), "192.168.1.20"))
    }
}
