package com.neurorift.android.profile

import org.yaml.snakeyaml.Yaml as SnakeYaml

/**
 * Content auto-detection — the Android port of the Windows
 * internal/profile/decode.go + content.go decision order:
 *
 * 1. base64 link list (whole payload decodes to share links)
 * 2. plain link list (one share link per line)
 * 3. Clash YAML with groups/rules (full profile → CLASH)
 * 4. proxies-only YAML (nodes list → provider materialization)
 * 5. sing-box JSON (raw or base64) — v1.8 parity
 *
 * Every skip/repair is surfaced as a warning (Windows bug-fix parity: import
 * warnings are never silent).
 */
object Decode {

    private val yamlLoader = SnakeYaml()

    private fun looksLikeSingBoxJson(content: String): Boolean {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("eyJ")) return false
        return trimmed.contains("\"outbounds\"")
    }

    fun decode(content: String): DecodeResult = when {
        looksLikeSingBoxJson(content) -> SingBoxParser.convert(content, warn = false).let {
            DecodeResult(it.first, it.second, "sing-box")
        }

        looksLikeBase64(content) -> decodeBase64(content)

        content.trimStart().startsWith("{") || content.contains("\"outbounds\"") ->
            SingBoxParser.convert(content, warn = false).let {
                DecodeResult(it.first, it.second, "sing-box")
            }

        looksLikePlainLinkList(content) -> decodeLinkList(content)

        else -> decodeClash(content)
    }

    // -------------------------------------------------------------- links --

    private fun looksLikeBase64(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.contains("\n")) {
            // Multi-line base64 or a plain list — decide by decoding.
            val decoded = runCatching { UriParse.b64Decode(trimmed).decodeToString() }.getOrNull() ?: return false
            val lines = decoded.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            return lines.isNotEmpty() && lines.all { UriParse.looksLikeShareLink(it) }
        }
        if (UriParse.looksLikeShareLink(trimmed)) return false
        val decoded = runCatching { UriParse.b64Decode(trimmed).decodeToString() }.getOrNull() ?: return false
        val lines = decoded.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        return lines.isNotEmpty() && lines.all { UriParse.looksLikeShareLink(it) }
    }

    private fun decodeBase64(content: String): DecodeResult {
        val decoded = runCatching { UriParse.b64Decode(content.trim()).decodeToString() }.getOrNull()
            ?: return DecodeResult(emptyList(), listOf("base64 payload could not be decoded"), "links")
        return decodeLinkList(decoded)
    }

    private fun looksLikePlainLinkList(content: String): Boolean {
        val lines = content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        return lines.isNotEmpty() && lines.any { UriParse.looksLikeShareLink(it) } &&
            lines.none { it.contains("proxies:") || it.contains("proxy-groups:") }
    }

    private fun decodeLinkList(content: String): DecodeResult {
        val nodes = mutableListOf<Node>()
        val warnings = mutableListOf<String>()
        val lines = content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        for ((index, line) in lines.withIndex()) {
            if (!UriParse.looksLikeShareLink(line)) {
                warnings.add("line ${index + 1}: unrecognized, skipped")
                continue
            }
            runCatching { UriParse.parseShareLink(line) }
                .onSuccess { nodes.add(it) }
                .onFailure { warnings.add("line ${index + 1}: ${it.message}") }
        }
        return DecodeResult(sanitize(nodes), warnings, "links")
    }

    // --------------------------------------------------------------- clash --

    private fun looksLikeClashYaml(content: String): Boolean {
        if (content.contains("proxies:")) return true
        if (content.contains("proxy-groups:") || content.contains("rules:")) return true
        return false
    }

    private fun decodeClash(content: String): DecodeResult {
        if (!looksLikeClashYaml(content)) {
            return DecodeResult(emptyList(), listOf("unrecognized content: no links, no proxies, no clash keys"), "unknown")
        }
        val loaded = runCatching { yamlLoader.load<Any>(content) }.getOrNull()
            ?: return DecodeResult(emptyList(), listOf("invalid YAML document"), "clash")
        @Suppress("UNCHECKED_CAST")
        val doc = loaded as? Map<String, Any?>
            ?: return DecodeResult(emptyList(), listOf("YAML root is not a mapping"), "clash")

        val nodes = mutableListOf<Node>()
        val warnings = mutableListOf<String>()
        val proxies = doc["proxies"] as? List<Any?> ?: emptyList()
        for (element in proxies) {
            @Suppress("UNCHECKED_CAST")
            val map = element as? LinkedHashMap<String, Any?>
            if (map == null) {
                warnings.add("proxies: non-mapping entry skipped")
                continue
            }
            val name = map["name"] as? String
            val type = map["type"] as? String
            val server = map["server"] as? String
            val port = (map["port"] as? Int) ?: (map["port"] as? String)?.toIntOrNull()
            if (name.isNullOrEmpty() || type.isNullOrEmpty() || server.isNullOrEmpty() || port == null) {
                warnings.add("proxies: entry '${map["name"]}' missing required fields, skipped")
                continue
            }
            nodes.add(Node(name, type, map))
        }

        val isFullProfile = doc.containsKey("proxy-groups") || doc.containsKey("rules")
        return DecodeResult(sanitize(nodes), warnings, if (isFullProfile) "clash" else "clash-proxies")
    }

    // ------------------------------------------------------------ sanitize --

    /** Drops broken nodes and dedupes names (Windows sanitize.go parity). */
    fun sanitize(nodes: List<Node>): List<Node> {
        val seen = HashMap<String, Int>()
        val out = mutableListOf<Node>()
        for (node in nodes) {
            val raw = node.raw
            val server = raw["server"] as? String
            val port = (raw["port"] as? Int) ?: (raw["port"] as? String)?.toIntOrNull()
            if (server.isNullOrEmpty() || port == null || port !in 1..65535) continue
            var name = node.name.ifEmpty { "${node.type}-${server}-$port" }
            val count = seen[name] ?: 0
            seen[name] = count + 1
            if (count > 0) name = "$name ${count + 1}"
            out.add(node.copy(name = name, raw = LinkedHashMap(raw).apply { put("name", name) }))
        }
        return out
    }
}
