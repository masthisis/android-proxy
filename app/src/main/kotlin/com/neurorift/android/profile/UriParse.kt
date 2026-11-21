package com.neurorift.android.profile

import java.net.URLDecoder
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Share-link parsers for every supported format — the Android port of the
 * Windows internal/profile/uriparse.go with fixture-parity tests:
 * `vmess:// vless:// trojan:// ss:// ssr:// hysteria2://(hy2) tuic://
 * anytls:// socks5:// http(s)://` including REALITY (pbk/sid), XTLS Vision
 * flow, WS/gRPC/H2/httpupgrade/xhttp transports, SS plugins, Hysteria2 port
 * hopping and TUIC v4 tokens.
 *
 * Fixtures use RFC 5737 addresses and example.com — never real servers.
 */
object UriParse {

    fun parseShareLink(link: String): Node {
        val trimmed = link.trim()
        val scheme = trimmed.substringBefore("://", "").lowercase()
        return when (scheme) {
            "vmess" -> parseVMess(trimmed)
            "vless" -> parseVLESS(trimmed)
            "trojan" -> parseTrojan(trimmed)
            "ss" -> parseSS(trimmed)
            "ssr" -> parseSSR(trimmed)
            "hysteria2", "hy2" -> parseHysteria2(trimmed)
            "tuic" -> parseTUIC(trimmed)
            "anytls" -> parseAnyTLS(trimmed)
            "socks5", "socks" -> parseSocks(trimmed)
            "http", "https" -> parseHttp(trimmed, scheme == "https")
            else -> throw LinkParseException("unsupported scheme: $scheme")
        }
    }

    /** True when the string looks like a share link we understand. */
    fun looksLikeShareLink(line: String): Boolean {
        val scheme = line.trim().substringBefore("://", "").lowercase()
        return scheme in setOf(
            "vmess", "vless", "trojan", "ss", "ssr", "hysteria2", "hy2",
            "tuic", "anytls", "socks5", "socks", "http", "https",
        )
    }

    // ------------------------------------------------------------ helpers --

    fun b64Decode(input: String): ByteArray {
        val cleaned = input.trim().replace("\n", "").replace("\r", "")
            .replace('-', '+').replace('_', '/')
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        // java.util.Base64 keeps this testable on the plain JVM (minSdk 26).
        return Base64.getDecoder().decode(padded)
    }

    fun b64Encode(input: ByteArray): String =
        Base64.getEncoder().withoutPadding().encodeToString(input)

    private class LinkParts(val raw: String) {
        val scheme = raw.substringBefore("://").lowercase()
        val fragment: String = raw.substringAfterLast("#", "")
        val name: String = if (fragment.isEmpty()) "" else URLDecoder.decode(fragment, "UTF-8")
        val withoutFragment: String = raw.substringBeforeLast("#", raw)
        val body: String = withoutFragment.substringAfter("://")
        val authority: String = body.substringBefore("/", body).substringBefore("?")
        val query: Map<String, String> = body
            .substringAfter("?", "")
            .takeIf { it.isNotEmpty() && it.contains("=") }
            ?.substringBefore("/")
            ?.split("&")
            ?.mapNotNull {
                val kv = it.split("=", limit = 2)
                if (kv.size == 2) URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv[1], "UTF-8") else null
            }
            ?.toMap()
            ?: emptyMap()

        fun q(key: String, default: String = ""): String = query[key] ?: default

        val hostPort: Pair<String, Int>
            get() {
                val at = authority.lastIndexOf('@')
                val hostPart = if (at >= 0) authority.substring(at + 1) else authority
                val host = hostPart.substringBeforeLast(":")
                val port = hostPart.substringAfterLast(":").toIntOrNull()
                    ?: throw LinkParseException("invalid host:port in $raw")
                return host to port
            }

        val userInfo: Pair<String, String>
            get() {
                val at = authority.lastIndexOf('@')
                val userinfo = if (at >= 0) authority.substring(0, at) else ""
                val user = userinfo.substringBefore(":")
                val pass = userinfo.substringAfter(":", "")
                return user to pass
            }
    }

    private fun baseNode(type: String, name: String, host: String, port: Int): LinkedHashMap<String, Any?> =
        linkedMapOf(
            "name" to name.ifEmpty { "$type-$host-$port" },
            "type" to type,
            "server" to host,
            "port" to port,
        )

    // -------------------------------------------------------------- vmess --

    private fun parseVMess(link: String): Node {
        val payload = link.removePrefix("vmess://")
        val decoded = runCatching { b64Decode(payload).decodeToString() }
            .getOrElse { throw LinkParseException("vmess: invalid base64 payload", it) }
        val json = runCatching { Json.parseToJsonElement(decoded).jsonObject }
            .getOrElse { throw LinkParseException("vmess: payload is not JSON", it) }

        fun jstr(key: String, default: String = ""): String =
            (json[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it != kotlinx.serialization.json.JsonNull }
                ?.content ?: default

        val name = jstr("ps")
        val host = jstr("add")
        val port = jstr("port").toIntOrNull()
            ?: throw LinkParseException("vmess: invalid port")
        val node = baseNode("vmess", name, host, port)
        node["uuid"] = jstr("id")
        node["alterId"] = jstr("aid", "0").toIntOrNull() ?: 0
        node["cipher"] = jstr("scy", "auto").ifEmpty { "auto" }

        val net = jstr("net", "tcp")
        node["network"] = net
        val tlsOn = jstr("tls") == "tls"
        node["tls"] = tlsOn
        if (tlsOn) node["servername"] = jstr("sni", host)
        jstr("fp").takeIf { it.isNotEmpty() }?.let { node["client-fingerprint"] = it }
        jstr("alpn").takeIf { it.isNotEmpty() }?.let { node["alpn"] = it.split(",") }

        val path = jstr("path")
        val hostHeader = jstr("host")
        when (net) {
            "ws" -> {
                val wsOpts = linkedMapOf<String, Any?>()
                if (path.isNotEmpty()) wsOpts["path"] = path
                if (hostHeader.isNotEmpty()) {
                    wsOpts["headers"] = linkedMapOf<String, Any?>("Host" to hostHeader)
                }
                node["ws-opts"] = wsOpts
            }

            "h2" -> {
                val h2 = linkedMapOf<String, Any?>()
                if (path.isNotEmpty()) h2["path"] = path
                if (hostHeader.isNotEmpty()) h2["host"] = listOf(hostHeader)
                node["h2-opts"] = h2
            }

            "grpc" -> {
                if (hostHeader.isNotEmpty()) node["grpc-opts"] = linkedMapOf<String, Any?>("grpc-service-name" to hostHeader)
            }
        }
        return Node(node["name"] as String, "vmess", node)
    }

    // -------------------------------------------------------------- vless --

    private fun parseVLESS(link: String): Node {
        val p = LinkParts(link)
        val (host, port) = p.hostPort
        val (uuid, _) = p.userInfo
        val node = baseNode("vless", p.name, host, port)
        node["uuid"] = uuid

        val security = p.q("security", "none")
        if (security == "tls" || security == "reality") node["tls"] = true
        p.q("sni").takeIf { it.isNotEmpty() }?.let { node["servername"] = it }
        p.q("fp").takeIf { it.isNotEmpty() }?.let { node["client-fingerprint"] = it }
        p.q("flow").takeIf { it.isNotEmpty() }?.let { node["flow"] = it }
        if (security == "reality") {
            val reality = linkedMapOf<String, Any?>()
            p.q("pbk").takeIf { it.isNotEmpty() }?.let { reality["public-key"] = it }
            p.q("sid").takeIf { it.isNotEmpty() }?.let { reality["short-id"] = it }
            node["reality-opts"] = reality
        }
        applyTransport(node, p)
        return Node(node["name"] as String, "vless", node)
    }

    // ------------------------------------------------------------- trojan --

    private fun parseTrojan(link: String): Node {
        val p = LinkParts(link)
        val (host, port) = p.hostPort
        val (password, _) = p.userInfo
        val node = baseNode("trojan", p.name, host, port)
        node["password"] = password
        node["tls"] = true
        p.q("sni").takeIf { it.isNotEmpty() }?.let { node["sni"] = it }
        p.q("fp").takeIf { it.isNotEmpty() }?.let { node["client-fingerprint"] = it }
        applyTransport(node, p)
        return Node(node["name"] as String, "trojan", node)
    }

    private fun applyTransport(node: LinkedHashMap<String, Any?>, p: LinkParts) {
        val net = p.q("type", "tcp").lowercase()
        if (net != "tcp" && net != "none") node["network"] = net
        val path = p.q("path")
        val host = p.q("host")
        val serviceName = p.q("serviceName")
        val method = p.q("method")
        when (net) {
            "ws" -> {
                val wsOpts = linkedMapOf<String, Any?>()
                if (path.isNotEmpty()) wsOpts["path"] = path
                if (host.isNotEmpty()) wsOpts["headers"] = linkedMapOf<String, Any?>("Host" to host)
                node["ws-opts"] = wsOpts
            }

            "grpc" -> {
                node["grpc-opts"] = linkedMapOf<String, Any?>(
                    "grpc-service-name" to serviceName.ifEmpty { host },
                )
            }

            "h2" -> {
                val h2 = linkedMapOf<String, Any?>()
                if (path.isNotEmpty()) h2["path"] = path
                if (host.isNotEmpty()) h2["host"] = listOf(host)
                node["h2-opts"] = h2
            }

            "httpupgrade" -> {
                val opts = linkedMapOf<String, Any?>()
                if (path.isNotEmpty()) opts["path"] = path
                if (host.isNotEmpty()) opts["host"] = host
                node["httpupgrade-opts"] = opts
            }

            "xhttp" -> {
                val opts = linkedMapOf<String, Any?>()
                if (path.isNotEmpty()) opts["path"] = path
                if (host.isNotEmpty()) opts["host"] = host
                if (method.isNotEmpty()) opts["mode"] = method
                node["xhttp-opts"] = opts
            }
        }
        p.q("alpn").takeIf { it.isNotEmpty() }?.let { node["alpn"] = it.split(",") }
        if (p.q("allowInsecure") == "1" || p.q("insecure") == "1") {
            node["skip-cert-verify"] = true
        }
    }

    // ----------------------------------------------------------------- ss --

    private fun parseSS(link: String): Node {
        val body = link.removePrefix("ss://")
        // SIP002: base64(method:pass)@host:port#name
        // Legacy: base64(method:pass@host:port)#name
        val hashIdx = body.lastIndexOf('#')
        val name = if (hashIdx >= 0) URLDecoder.decode(body.substring(hashIdx + 1), "UTF-8") else ""
        val main = if (hashIdx >= 0) body.substring(0, hashIdx) else body

        val atIdx = main.lastIndexOf('@')
        if (atIdx >= 0) {
            // SIP002 form: userinfo may be base64(method:password) or method:password
            val userinfo = main.substring(0, atIdx)
            val hostport = main.substring(atIdx + 1).substringBefore("?")
            val decodedUserinfo = runCatching { b64Decode(userinfo).decodeToString() }.getOrNull()
            val methodPass = decodedUserinfo ?: URLDecoder.decode(userinfo, "UTF-8")
            val method = methodPass.substringBefore(":")
            val password = methodPass.substringAfter(":", "")
            val host = hostport.substringBeforeLast(":")
            val port = hostport.substringAfterLast(":").toIntOrNull()
                ?: throw LinkParseException("ss: invalid port")
            val node = baseNode("ss", name, host, port)
            node["cipher"] = method
            node["password"] = password
            applySSPlugin(node, main.substringAfter("?", ""))
            return Node(node["name"] as String, "ss", node)
        }

        // Legacy form: everything is base64 — method:password@host:port.
        val decoded = runCatching { b64Decode(main.substringBefore("?")).decodeToString() }
            .getOrElse { throw LinkParseException("ss: invalid legacy base64", it) }
        val method = decoded.substringBefore(":")
        val rest = decoded.substringAfter(":")
        val password = rest.substringBefore("@")
        val hostport = rest.substringAfter("@")
        val host = hostport.substringBeforeLast(":")
        val port = hostport.substringAfterLast(":").toIntOrNull()
            ?: throw LinkParseException("ss: invalid legacy port")
        val node = baseNode("ss", name, host, port)
        node["cipher"] = method
        node["password"] = password
        return Node(node["name"] as String, "ss", node)
    }

    private fun applySSPlugin(node: LinkedHashMap<String, Any?>, query: String) {
        val pluginEncoded = query.split("&")
            .firstOrNull { it.startsWith("plugin=") }
            ?.substringAfter("plugin=")
            ?: return
        val plugin = runCatching { URLDecoder.decode(pluginEncoded, "UTF-8") }.getOrNull() ?: return
        // Format: obfs-local;obfs=http;obfs-host=example.com  |  v2ray-plugin;mode=websocket
        val parts = plugin.split(";")
        // mihomo plugin names: obfs-local/simple-obfs -> obfs.
        node["plugin"] = when (parts.firstOrNull()) {
            "obfs-local", "simple-obfs" -> "obfs"
            "v2ray-plugin" -> "v2ray-plugin"
            else -> parts.firstOrNull() ?: return
        } ?: return
        val opts = linkedMapOf<String, Any?>()
        for (param in parts.drop(1)) {
            val key = param.substringBefore("=")
            val value = param.substringAfter("=", "")
            when (key) {
                "obfs" -> opts["mode"] = value
                "obfs-host" -> opts["host"] = value
                "mode" -> opts["mode"] = value
                "tls" -> opts["tls"] = true
            }
        }
        if (opts.isNotEmpty()) node["plugin-opts"] = opts
    }

    // --------------------------------------------------------------- ssr --

    private fun parseSSR(link: String): Node {
        val decoded = runCatching { b64Decode(link.removePrefix("ssr://")).decodeToString() }
            .getOrElse { throw LinkParseException("ssr: invalid base64", it) }
        val main = decoded.substringBefore("/?")
        val paramsPart = decoded.substringAfter("/?", "")

        // host:port:protocol:method:obfs:base64(password)
        val segs = main.split(":")
        if (segs.size < 6) throw LinkParseException("ssr: invalid format")
        val host = segs[0]
        val port = segs[1].toIntOrNull() ?: throw LinkParseException("ssr: invalid port")
        val protocol = segs[2]
        val method = segs[3]
        val obfs = segs[4]
        val password = runCatching { b64Decode(segs.drop(5).joinToString(":")).decodeToString() }
            .getOrElse { throw LinkParseException("ssr: invalid password encoding", it) }

        val params = paramsPart.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()

        val remarks = params["remarks"]?.let { runCatching { b64Decode(it).decodeToString() }.getOrNull() } ?: ""
        val obfsParam = params["obfsparam"]?.let { runCatching { b64Decode(it).decodeToString() }.getOrNull() } ?: ""
        val protoParam = params["protoparam"]?.let { runCatching { b64Decode(it).decodeToString() }.getOrNull() } ?: ""

        val node = baseNode("ssr", remarks, host, port)
        node["cipher"] = method
        node["password"] = password
        node["protocol"] = protocol
        node["obfs"] = obfs
        if (protoParam.isNotEmpty()) node["protocol-param"] = protoParam
        if (obfsParam.isNotEmpty()) node["obfs-param"] = obfsParam
        return Node(node["name"] as String, "ssr", node)
    }

    // ---------------------------------------------------------- hysteria2 --

    private fun parseHysteria2(link: String): Node {
        val p = LinkParts(link)
        val (host, port) = p.hostPort
        val (auth, _) = p.userInfo // hysteria2 auth lives in the user slot
        val node = baseNode("hysteria2", p.name, host, port)
        node["password"] = auth
        p.q("sni").takeIf { it.isNotEmpty() }?.let { node["sni"] = it }
        p.q("obfs").takeIf { it.isNotEmpty() }?.let { node["obfs"] = it }
        p.q("obfs-password").takeIf { it.isNotEmpty() }?.let { node["obfs-password"] = it }
        p.q("mport").takeIf { it.isNotEmpty() }?.let { node["ports"] = it }
        if (p.q("insecure") == "1" || p.q("insecure") == "true") {
            node["skip-cert-verify"] = true
        }
        return Node(node["name"] as String, "hysteria2", node)
    }

    // --------------------------------------------------------------- tuic --

    private fun parseTUIC(link: String): Node {
        val p = LinkParts(link)
        val (host, port) = p.hostPort
        val (uuid, password) = p.userInfo
        val node = baseNode("tuic", p.name, host, port)
        if (uuid.isNotEmpty() || password.isNotEmpty()) {
            // TUIC v5
            node["uuid"] = uuid
            node["password"] = password
        } else {
            // TUIC v4 token form: tuic://token@host:port
            node["token"] = uuid
        }
        p.q("sni").takeIf { it.isNotEmpty() }?.let { node["sni"] = it }
        p.q("congestion_control").takeIf { it.isNotEmpty() }?.let { node["congestion-controller"] = it }
        p.q("congestion-controller").takeIf { it.isNotEmpty() }?.let { node["congestion-controller"] = it }
        p.q("udp_relay_mode").takeIf { it.isNotEmpty() }?.let { node["udp-relay-mode"] = it }
        p.q("alpn").takeIf { it.isNotEmpty() }?.let { node["alpn"] = it.split(",") }
        if (p.q("allow_insecure") == "1" || p.q("insecure") == "1") {
            node["skip-cert-verify"] = true
        }
        return Node(node["name"] as String, "tuic", node)
    }

    // ------------------------------------------------------------- anytls --

    private fun parseAnyTLS(link: String): Node {
        val p = LinkParts(link)
        val (host, port) = p.hostPort
        val (password, _) = p.userInfo
        val node = baseNode("anytls", p.name, host, port)
        node["password"] = password
        p.q("sni").takeIf { it.isNotEmpty() }?.let { node["sni"] = it }
        if (p.q("insecure") == "1") node["skip-cert-verify"] = true
        return Node(node["name"] as String, "anytls", node)
    }

    // ------------------------------------------------------------- socks --

    private fun parseSocks(link: String): Node {
        val p = LinkParts(link)
        val (host, port) = p.hostPort
        val (user, pass) = p.userInfo
        val node = baseNode("socks5", p.name, host, port)
        if (user.isNotEmpty()) node["username"] = user
        if (pass.isNotEmpty()) node["password"] = pass
        if (p.q("tls") == "1") node["tls"] = true
        return Node(node["name"] as String, "socks5", node)
    }

    private fun parseHttp(link: String, tls: Boolean): Node {
        val p = LinkParts(link)
        val (host, port) = p.hostPort
        val (user, pass) = p.userInfo
        val node = baseNode(if (tls) "http" else "http", p.name, host, port)
        if (user.isNotEmpty()) node["username"] = user
        if (pass.isNotEmpty()) node["password"] = pass
        if (tls) node["tls"] = true
        return Node(node["name"] as String, "http", node)
    }
}
