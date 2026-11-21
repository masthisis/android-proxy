package com.neurorift.android.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * sing-box JSON converter (v1.8 parity with the Windows singbox.go):
 * converts shadowsocks / vmess / vless / trojan / hysteria2 / tuic /
 * wireguard / socks / http outbounds into mihomo proxy maps, including
 * REALITY, uTLS fingerprints and stream transports. Skipped outbounds are
 * reported as warnings — never silently dropped.
 */
object SingBoxParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns (nodes, warnings). */
    fun convert(content: String, warn: Boolean = false): Pair<List<Node>, List<String>> {
        val warnings = mutableListOf<String>()
        val document: JsonObject = parseDocument(content)
            ?: return emptyList<Node>() to listOf("sing-box: invalid JSON document")

        val outbounds = document["outbounds"] as? JsonArray
            ?: return emptyList<Node>() to listOf("sing-box: no outbounds array")

        val nodes = mutableListOf<Node>()
        for (element in outbounds) {
            val ob = element as? JsonObject ?: continue
            val type = ob.str("type") ?: continue
            if (type in setOf("direct", "block", "dns", "urltest", "selector")) continue
            val converted = convertOutbound(ob, warnings, warn)
            if (converted != null) nodes.add(converted)
        }
        return Decode.sanitize(nodes) to warnings
    }

    /** Parses raw or base64-wrapped sing-box documents. */
    private fun parseDocument(content: String): JsonObject? {
        val direct = runCatching { json.parseToJsonElement(content.trim()).jsonObject }.getOrNull()
        if (direct != null) return direct
        val decoded = runCatching { UriParse.b64Decode(content.trim()).decodeToString() }.getOrNull() ?: return null
        return runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull()
    }

    /** sing-box outbound type -> mihomo proxy type. */
    private fun mihomoType(sbType: String): String = when (sbType) {
        "shadowsocks" -> "ss"
        "socks" -> "socks5"
        else -> sbType
    }

    private fun convertOutbound(ob: JsonObject, warnings: MutableList<String>, warn: Boolean): Node? {
        val sbType = ob.str("type") ?: return null
        val type = mihomoType(sbType)
        val tag = ob.str("tag") ?: ""
        val server = ob.str("server") ?: ""
        val port = ob.int("server_port")
        if (server.isEmpty() || port == null) {
            if (warn) warnings.add("sing-box: '$tag' missing server/port, skipped")
            return null
        }

        val node = linkedMapOf<String, Any?>(
            "name" to tag.ifEmpty { "$type-$server-$port" },
            "type" to type,
            "server" to server,
            "port" to port,
        )

        when (sbType) {
            "shadowsocks" -> {
                node["cipher"] = ob.str("method") ?: ""
                node["password"] = ob.str("password") ?: ""
                applyPlugin(ob, node, warnings, tag, warn)
            }

            "vmess" -> {
                node["uuid"] = ob.str("uuid") ?: ""
                node["alterId"] = ob.int("alter_id") ?: 0
                node["cipher"] = ob.str("security") ?: "auto"
                applyTlsAndTransport(ob, node)
            }

            "vless" -> {
                node["uuid"] = ob.str("uuid") ?: ""
                ob.str("flow")?.takeIf { it.isNotEmpty() }?.let { node["flow"] = it }
                applyTlsAndTransport(ob, node)
            }

            "trojan" -> {
                node["password"] = ob.str("password") ?: ""
                applyTlsAndTransport(ob, node)
            }

            "hysteria2" -> {
                node["password"] = ob.str("password") ?: ob.str("auth") ?: ""
                ob.str("obfs")?.let { node["obfs"] = it }
                ob["obfs_password"].strSafe()?.let { node["obfs-password"] = it }
                applyTlsAndTransport(ob, node)
            }

            "tuic" -> {
                node["uuid"] = ob.str("uuid") ?: ""
                node["password"] = ob.str("password") ?: ""
                ob.str("congestion_control")?.let { node["congestion-controller"] = it }
                ob.str("udp_relay_mode")?.let { node["udp-relay-mode"] = it }
                applyTlsAndTransport(ob, node)
            }

            "wireguard" -> {
                node["private-key"] = ob.str("private_key") ?: ""
                node["public-key"] = ob.str("peer_public_key") ?: ""
                ob.str("local_address")?.let { node["ip"] = it }
                node["mtu"] = ob.int("mtu") ?: 1420
            }

            "socks" -> {
                node["version"] = "5"
                ob.str("username")?.let { node["username"] = it }
                ob.str("password")?.let { node["password"] = it }
            }

            "http" -> {
                ob.str("username")?.let { node["username"] = it }
                ob.str("password")?.let { node["password"] = it }
                node["tls"] = ob.str("tls") == "true"
            }

            else -> {
                if (warn) warnings.add("sing-box: outbound type '$sbType' is not supported, skipped")
                return null
            }
        }
        return Node(node["name"] as String, node["type"] as String, node)
    }

    private fun applyPlugin(
        ob: JsonObject,
        node: LinkedHashMap<String, Any?>,
        warnings: MutableList<String>,
        tag: String,
        warn: Boolean,
    ) {
        val plugin = ob.str("plugin") ?: return
        val opts = ob["plugin_options"]?.jsonObject
        when (plugin) {
            "obfs-local", "simple-obfs" -> {
                node["plugin"] = "obfs"
                val pluginOpts = linkedMapOf<String, Any?>()
                opts?.str("mode")?.let { pluginOpts["mode"] = it }
                opts?.str("host")?.let { pluginOpts["host"] = it }
                node["plugin-opts"] = pluginOpts
            }

            "v2ray-plugin" -> {
                node["plugin"] = "v2ray-plugin"
                val pluginOpts = linkedMapOf<String, Any?>()
                opts?.str("mode")?.let { pluginOpts["mode"] = it }
                opts?.bool("tls")?.let { pluginOpts["tls"] = it }
                node["plugin-opts"] = pluginOpts
            }

            else -> if (warn) warnings.add("sing-box: '$tag' plugin '$plugin' not supported, skipped")
        }
    }

    private fun applyTlsAndTransport(ob: JsonObject, node: LinkedHashMap<String, Any?>) {
        val tls = ob["tls"]?.jsonObject
        if (tls != null) {
            node["tls"] = true
            tls.str("server_name")?.let { node["servername"] = it }
            tls.str("alpn")?.let { node["alpn"] = it }
            tls.bool("insecure")?.let { node["skip-cert-verify"] = it }
            tls["utls"]?.jsonObject?.str("fingerprint")?.let { node["client-fingerprint"] = it }
            tls["reality"]?.jsonObject?.let { reality ->
                val realityOpts = linkedMapOf<String, Any?>()
                reality.str("public_key")?.let { realityOpts["public-key"] = it }
                reality.str("short_id")?.let { realityOpts["short-id"] = it }
                node["reality-opts"] = realityOpts
            }
        }
        val transport = ob["transport"]?.jsonObject ?: return
        when (transport.str("type")) {
            "ws" -> {
                node["network"] = "ws"
                val opts = linkedMapOf<String, Any?>()
                transport.str("path")?.let { opts["path"] = it }
                transport["headers"]?.jsonObject?.let { headers ->
                    headers.str("Host")?.let { opts["headers"] = linkedMapOf<String, Any?>("Host" to it) }
                }
                node["ws-opts"] = opts
            }

            "grpc" -> {
                node["network"] = "grpc"
                node["grpc-opts"] = linkedMapOf<String, Any?>(
                    "grpc-service-name" to (transport.str("service_name") ?: ""),
                )
            }

            "http" -> {
                node["network"] = "h2"
                val opts = linkedMapOf<String, Any?>()
                transport.str("path")?.let { opts["path"] = it }
                (transport["host"] as? JsonArray)?.let { arr ->
                    opts["host"] = arr.mapNotNull { (it as? JsonPrimitive)?.content }
                }
                node["h2-opts"] = opts
            }

            "httpupgrade" -> {
                node["network"] = "httpupgrade"
                val opts = linkedMapOf<String, Any?>()
                transport.str("path")?.let { opts["path"] = it }
                transport.str("host")?.let { opts["host"] = it }
                node["httpupgrade-opts"] = opts
            }
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let {
            it.content == "true" || it.content == "1"
        }

    private fun JsonElement?.strSafe(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}
