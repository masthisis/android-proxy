package com.neurorift.android.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture-parity tests with the Windows internal/profile/uriparse_test.go —
 * the share-link parsing behaviour is the compatibility contract between the
 * two clients. All fixtures use RFC 5737 addresses / example.com.
 */
class UriParseTest {

    private fun node(link: String): Node = UriParse.parseShareLink(link)

    private fun n(node: Node, key: String): Any? = node.raw[key]

    // --- vmess --------------------------------------------------------------

    @Test
    fun `vmess base64 json maps to mihomo fields`() {
        val payload =
            """{"v":"2","ps":"Tokyo Node","add":"1.2.3.4","port":"443","id":"aaaabbbb-cccc-dddd-eeee-ffff00001111","aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/ws","tls":"tls","sni":"cdn.example.com","alpn":"h2,http/1.1","fp":"chrome"}"""
        val parsed = node("vmess://" + UriParse.b64Encode(payload.encodeToByteArray()))

        assertEquals("Tokyo Node", parsed.name)
        assertEquals("vmess", parsed.type)
        assertEquals("1.2.3.4", n(parsed, "server"))
        assertEquals(443, n(parsed, "port"))
        assertEquals("aaaabbbb-cccc-dddd-eeee-ffff00001111", n(parsed, "uuid"))
        assertEquals("auto", n(parsed, "cipher"))
        assertEquals(true, n(parsed, "tls"))
        assertEquals("cdn.example.com", n(parsed, "servername"))
        assertEquals("ws", n(parsed, "network"))
        assertEquals("chrome", n(parsed, "client-fingerprint"))
        @Suppress("UNCHECKED_CAST")
        val wsOpts = n(parsed, "ws-opts") as LinkedHashMap<String, Any?>
        assertEquals("/ws", wsOpts["path"])
        @Suppress("UNCHECKED_CAST")
        val headers = wsOpts["headers"] as LinkedHashMap<String, Any?>
        assertEquals("cdn.example.com", headers["Host"])
    }

    // --- vless REALITY -------------------------------------------------------

    @Test
    fun `vless reality keeps public key and short id`() {
        val parsed = node(
            "vless://uuid-1234@5.6.7.8:8443?encryption=none&security=reality&sni=www.microsoft.com&fp=chrome&pbk=PUBKEY123&sid=ab12&flow=xtls-rprx-vision&type=tcp#Reality%20Node",
        )
        assertEquals("Reality Node", parsed.name)
        assertEquals("vless", parsed.type)
        assertEquals(8443, n(parsed, "port"))
        assertEquals(true, n(parsed, "tls"))
        assertEquals("www.microsoft.com", n(parsed, "servername"))
        assertEquals("chrome", n(parsed, "client-fingerprint"))
        assertEquals("xtls-rprx-vision", n(parsed, "flow"))
        @Suppress("UNCHECKED_CAST")
        val reality = n(parsed, "reality-opts") as LinkedHashMap<String, Any?>
        assertEquals("PUBKEY123", reality["public-key"])
        assertEquals("ab12", reality["short-id"])
    }

    @Test
    fun `vless ws transport maps path and host header`() {
        val parsed = node(
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@h.example.com:1?type=ws&path=%2Fws&host=ws.example.com#WS",
        )
        assertEquals("ws", n(parsed, "network"))
        @Suppress("UNCHECKED_CAST")
        val wsOpts = n(parsed, "ws-opts") as LinkedHashMap<String, Any?>
        assertEquals("/ws", wsOpts["path"])
        @Suppress("UNCHECKED_CAST")
        val headers = wsOpts["headers"] as LinkedHashMap<String, Any?>
        assertEquals("ws.example.com", headers["Host"])
    }

    // --- trojan -------------------------------------------------------------

    @Test
    fun `trojan keeps password and sni`() {
        val parsed = node("trojan://pw-abc@t.example.com:443?sni=t.example.com&type=tcp#Trojan")
        assertEquals("Trojan", parsed.name)
        assertEquals("trojan", parsed.type)
        assertEquals("pw-abc", n(parsed, "password"))
        assertEquals(true, n(parsed, "tls"))
        assertEquals("t.example.com", n(parsed, "sni"))
    }

    // --- ss -----------------------------------------------------------------

    @Test
    fun `ss sip002 form with base64 userinfo`() {
        val userinfo = UriParse.b64Encode("aes-256-gcm:secret".encodeToByteArray())
        val parsed = node("ss://$userinfo@9.9.9.9:8388#SS%20SIP")
        assertEquals("SS SIP", parsed.name)
        assertEquals("ss", parsed.type)
        assertEquals("9.9.9.9", n(parsed, "server"))
        assertEquals(8388, n(parsed, "port"))
        assertEquals("aes-256-gcm", n(parsed, "cipher"))
        assertEquals("secret", n(parsed, "password"))
    }

    @Test
    fun `ss legacy base64 form`() {
        val legacy = UriParse.b64Encode("chacha20-ietf-poly1305:pw@7.7.7.7:1234".encodeToByteArray())
        val parsed = node("ss://$legacy#Legacy")
        assertEquals("Legacy", parsed.name)
        assertEquals("7.7.7.7", n(parsed, "server"))
        assertEquals(1234, n(parsed, "port"))
        assertEquals("chacha20-ietf-poly1305", n(parsed, "cipher"))
        assertEquals("pw", n(parsed, "password"))
    }

    @Test
    fun `ss plugin parameter maps to plugin-opts`() {
        val userinfo = UriParse.b64Encode("aes-256-gcm:secret".encodeToByteArray())
        val parsed = node(
            "ss://$userinfo@9.9.9.9:8388?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dcdn.example.com#Plugin",
        )
        assertEquals("obfs", n(parsed, "plugin"))
        @Suppress("UNCHECKED_CAST")
        val opts = n(parsed, "plugin-opts") as LinkedHashMap<String, Any?>
        assertEquals("http", opts["mode"])
        assertEquals("cdn.example.com", opts["host"])
    }

    // --- hysteria2 ------------------------------------------------------------

    @Test
    fun `hysteria2 port hopping and obfs survive`() {
        val parsed = node(
            "hy2://letmein@hy.example.com:443?sni=hy.example.com&insecure=1&obfs=salamander&obfs-password=obfspw&mport=443,8000-9000#HY2",
        )
        assertEquals("hysteria2", parsed.type)
        assertEquals("letmein", n(parsed, "password"))
        assertEquals("443,8000-9000", n(parsed, "ports"))
        assertEquals("salamander", n(parsed, "obfs"))
        assertEquals("obfspw", n(parsed, "obfs-password"))
        assertEquals(true, n(parsed, "skip-cert-verify"))
    }

    // --- tuic ---------------------------------------------------------------

    @Test
    fun `tuic v5 auth and options`() {
        val parsed = node(
            "tuic://uuid-xx:passyy@tu.example.com:8443?sni=tu.example.com&alpn=h3&congestion_control=bbr&udp_relay_mode=native#TU",
        )
        assertEquals("uuid-xx", n(parsed, "uuid"))
        assertEquals("passyy", n(parsed, "password"))
        assertEquals("bbr", n(parsed, "congestion-controller"))
        assertEquals("native", n(parsed, "udp-relay-mode"))
        assertEquals(listOf("h3"), n(parsed, "alpn"))
    }

    // --- ssr ------------------------------------------------------------------

    @Test
    fun `ssr base64 container with remarks and proto param`() {
        val main = "1.2.3.4:8388:origin:aes-256-cfb:plain:" +
            UriParse.b64Encode("ssrpass".encodeToByteArray())
        val params = "obfsparam=" + UriParse.b64Encode("".encodeToByteArray()) +
            "&protoparam=" + UriParse.b64Encode("32:aa".encodeToByteArray()) +
            "&remarks=" + UriParse.b64Encode("SSR Node".encodeToByteArray())
        val parsed = node("ssr://" + UriParse.b64Encode("$main/?$params".encodeToByteArray()))
        assertEquals("SSR Node", parsed.name)
        assertEquals("origin", n(parsed, "protocol"))
        assertEquals("plain", n(parsed, "obfs"))
        assertEquals("32:aa", n(parsed, "protocol-param"))
        assertEquals("aes-256-cfb", n(parsed, "cipher"))
    }

    // --- other schemes ---------------------------------------------------------

    @Test
    fun `anytls and socks parse`() {
        val anytls = node("anytls://pass@8.8.8.8:443?sni=a.example.com#AnyTLS")
        assertEquals("anytls", anytls.type)
        assertEquals("pass", n(anytls, "password"))
        assertEquals("a.example.com", n(anytls, "sni"))

        val socks = node("socks5://user:pw@8.8.4.4:1080#SOCKS")
        assertEquals("socks5", socks.type)
        assertEquals("user", n(socks, "username"))
        assertEquals("pw", n(socks, "password"))
    }

    @Test
    fun `unsupported scheme throws`() {
        try {
            node("wireguard://something")
            org.junit.Assert.fail("expected throw")
        } catch (e: LinkParseException) {
            assertTrue(e.message!!.contains("unsupported"))
        }
    }
}
