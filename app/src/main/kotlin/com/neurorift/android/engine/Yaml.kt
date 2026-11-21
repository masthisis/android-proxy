package com.neurorift.android.engine

/**
 * Minimal insertion-ordered YAML emitter (the Android counterpart of the
 * Windows yamlx.OMap): generated configs must be stable, human-reviewable
 * and diff-friendly (pitfall P12). Supports exactly the value shapes the
 * config generator produces: [OMap], List, String, Int/Long, Boolean, null.
 *
 * Strings are quoted when needed (YAML indicators, boolean/number look-
 * alikes, empty, padded, or non-ASCII — the group names contain emoji, and
 * being conservative here keeps mihomo's parser happy in every locale).
 */
class OMap private constructor(
    private val keys: MutableList<String>,
    private val values: LinkedHashMap<String, Any?>,
) {
    constructor() : this(mutableListOf(), LinkedHashMap())

    fun set(key: String, value: Any?): OMap {
        if (!values.containsKey(key)) keys.add(key)
        values[key] = value
        return this
    }

    fun get(key: String): Any? = values[key]
    fun keys(): List<String> = keys.toList()
    fun len(): Int = keys.size

    /**
     * Converts to a LinkedHashMap (recursively) — required when the map is
     * handed to snakeyaml for dump (clash splice path).
     */
    fun asJavaMap(): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (key in keys) {
            out[key] = when (val v = values[key]) {
                is OMap -> v.asJavaMap()
                is List<*> -> v.map { if (it is OMap) it.asJavaMap() else it }
                else -> v
            }
        }
        return out
    }
}

object Yaml {

    /** Renders the given [OMap] root as YAML text (block style, 2-space indent). */
    fun dump(root: OMap): String = buildString { dumpMap(root, 0) }.trimEnd('\n', ' ') + "\n"

    private fun StringBuilder.dumpMap(map: OMap, indent: Int) {
        val pad = " ".repeat(indent)
        for (key in map.keys()) {
            val value = map.get(key)
            when (value) {
                is OMap -> {
                    append(pad).append(scalar(key)).append(":\n")
                    dumpMap(value, indent + 2)
                }

                is List<*> -> {
                    append(pad).append(scalar(key)).append(":\n")
                    dumpList(value, indent + 2)
                }

                else -> {
                    append(pad).append(scalar(key)).append(": ").append(scalar(value)).append('\n')
                }
            }
        }
    }

    private fun StringBuilder.dumpList(items: List<*>, indent: Int) {
        val pad = " ".repeat(indent)
        for (item in items) {
            when (item) {
                is OMap -> {
                    // First key stays on the dash line, the rest are indented.
                    val keys = item.keys()
                    if (keys.isEmpty()) {
                        append(pad).append("- \n")
                        continue
                    }
                    keys.forEachIndexed { i, key ->
                        val value = item.get(key)
                        if (i == 0) {
                            when (value) {
                                is OMap -> {
                                    append(pad).append("- ").append(scalar(key)).append(":\n")
                                    dumpMap(value, indent + 4)
                                }

                                is List<*> -> {
                                    append(pad).append("- ").append(scalar(key)).append(":\n")
                                    dumpList(value, indent + 4)
                                }

                                else -> {
                                    append(pad).append("- ").append(scalar(key)).append(": ")
                                        .append(scalar(value)).append('\n')
                                }
                            }
                        } else {
                            when (value) {
                                is OMap -> {
                                    append(pad).append("  ").append(scalar(key)).append(":\n")
                                    dumpMap(value, indent + 4)
                                }

                                is List<*> -> {
                                    append(pad).append("  ").append(scalar(key)).append(":\n")
                                    dumpList(value, indent + 4)
                                }

                                else -> {
                                    append(pad).append("  ").append(scalar(key)).append(": ")
                                        .append(scalar(value)).append('\n')
                                }
                            }
                        }
                    }
                }

                is List<*> -> {
                    append(pad).append("-\n")
                    dumpList(item, indent + 2)
                }

                else -> append(pad).append("- ").append(scalar(item)).append('\n')
            }
        }
    }

    /** Renders a scalar value; returns "null" for null. */
    fun scalar(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        is String -> quoteIfNeeded(value)
        else -> quoteIfNeeded(value.toString())
    }

    private fun quoteIfNeeded(value: String): String {
        if (value.isEmpty()) return "\"\""
        val needsQuoting =
            value != value.trim() ||
                value.any { it.code < 0x20 || it.code > 0x7E } ||
                value in boolLike ||
                looksLikeNumber(value) ||
                value[0] in indicators ||
                value.contains(": ") ||
                value.contains(" #") ||
                value.endsWith(":") ||
                value.contains('\t')
        if (!needsQuoting) return value
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t") + "\""
    }

    private val boolLike = setOf("true", "false", "null", "yes", "no", "on", "off", "~")
    private val indicators = charArrayOf('-', '?', ':', ',', '[', ']', '{', '}', '#', '&', '*', '!', '|', '>', '\'', '"', '%', '@', '`')

    private fun looksLikeNumber(value: String): Boolean =
        value.toDoubleOrNull() != null || value.toLongOrNull() != null
}
