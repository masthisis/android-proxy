package com.neurorift.android.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decode-content auto-detection tests (Windows decode/content parity). */
class DecodeTest {

    @Test
    fun `base64 link list decodes to nodes`() {
        val links = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@h1.example.com:1#A\n" +
            "trojan://p@h2.example.com:2#B\n"
        val result = Decode.decode(UriParse.b64Encode(links.encodeToByteArray()))
        assertEquals("links", result.format)
        assertEquals(2, result.nodes.size)
        assertEquals("A", result.nodes[0].name)
        assertEquals("vless", result.nodes[0].type)
        assertEquals("B", result.nodes[1].name)
    }

    @Test
    fun `plain link list decodes with per-line warnings`() {
        val links = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@h1.example.com:1#A\n" +
            "garbage-line\n" +
            "trojan://p@h2.example.com:2#B\n"
        val result = Decode.decode(links)
        assertEquals("links", result.format)
        assertEquals(2, result.nodes.size)
        assertTrue(result.warnings.any { it.contains("line 2") })
    }

    @Test
    fun `full clash yaml is detected as clash profile`() {
        val yaml = """
            port: 7890
            proxies:
              - name: n1
                type: ss
                server: 198.51.100.5
                port: 8388
                cipher: aes-256-gcm
                password: x
            proxy-groups:
              - name: G
                type: select
                proxies: [n1]
            rules:
              - MATCH,G
        """.trimIndent()
        val result = Decode.decode(yaml)
        assertEquals("clash", result.format)
        assertEquals(1, result.nodes.size)
        assertEquals("n1", result.nodes[0].name)
    }

    @Test
    fun `proxies-only yaml is detected as clash-proxies`() {
        val yaml = """
            proxies:
              - name: n1
                type: ss
                server: 198.51.100.5
                port: 8388
                cipher: aes-256-gcm
                password: x
        """.trimIndent()
        val result = Decode.decode(yaml)
        assertEquals("clash-proxies", result.format)
        assertEquals(1, result.nodes.size)
    }

    @Test
    fun `duplicate names get numeric suffixes`() {
        val links = "trojan://p@h1.example.com:1#Same\n" +
            "trojan://p@h2.example.com:2#Same\n"
        val result = Decode.decode(links)
        assertEquals(listOf("Same", "Same 2"), result.nodes.map { it.name })
    }

    @Test
    fun `broken nodes are dropped with sanitize`() {
        val nodes = listOf(
            Node("ok", "ss", linkedMapOf("name" to "ok", "type" to "ss", "server" to "198.51.100.5", "port" to 8388)),
            Node("no-port", "ss", linkedMapOf("name" to "no-port", "type" to "ss", "server" to "198.51.100.5")),
        )
        val sanitized = Decode.sanitize(nodes)
        assertEquals(listOf("ok"), sanitized.map { it.name })
    }

    @Test
    fun `sing-box json converts outbounds`() {
        val doc = """
            {"outbounds":[
              {"type":"shadowsocks","tag":"ss-1","server":"198.51.100.10","server_port":8388,
               "method":"aes-128-gcm","password":"pw"},
              {"type":"vless","tag":"vless-1","server":"198.51.100.11","server_port":443,
               "uuid":"b831381d-6324-4d53-ad4f-8cda48b30811",
               "tls":{"enabled":true,"server_name":"www.microsoft.com","utls":{"fingerprint":"chrome"},
                      "reality":{"enabled":true,"public_key":"PBK","short_id":"ab"}},
               "flow":"xtls-rprx-vision",
               "transport":{"type":"ws","path":"/ws","headers":{"Host":"ws.example.com"}}},
              {"type":"direct","tag":"direct"}
            ]}
        """.trimIndent()
        val result = Decode.decode(doc)
        assertEquals("sing-box", result.format)
        assertEquals(2, result.nodes.size)
        val ss = result.nodes[0]
        assertEquals("ss", ss.type)
        assertEquals("aes-128-gcm", ss.raw["cipher"])
        val vless = result.nodes[1]
        assertEquals("vless", vless.type)
        assertEquals(true, vless.raw["tls"])
        assertEquals("chrome", vless.raw["client-fingerprint"])
        @Suppress("UNCHECKED_CAST")
        val reality = vless.raw["reality-opts"] as LinkedHashMap<String, Any?>
        assertEquals("PBK", reality["public-key"])
        assertEquals("ab", reality["short-id"])
        assertEquals("ws", vless.raw["network"])
    }

    @Test
    fun `unrecognized content produces a warning not a crash`() {
        val result = Decode.decode("hello world, nothing here")
        assertEquals(0, result.nodes.size)
        assertTrue(result.warnings.isNotEmpty())
    }
}
