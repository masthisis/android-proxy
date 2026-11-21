package com.neurorift.android.update

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * One release entry of the project's `releases.atom` feed (issue #20).
 *
 * [tag] is the release tag (e.g. `v1.2.3`), [link] the HTML URL of the
 * release (`…/releases/tag/v1.2.3`) from which the asset download URLs are
 * derived, and [updated] the feed's timestamp for the entry.
 */
data class AtomRelease(
    val tag: String,
    val link: String,
    val updated: String,
)

/**
 * Parses the GitHub releases Atom feed (issue #20) — a pure function over
 * the XML text (JVM stdlib DOM parser, XXE-hardened: doctype declarations
 * are rejected). The feed lists entries newest-first; the document order is
 * preserved so the newest release comes back first.
 */
fun parseAtom(xml: String): List<AtomRelease> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        // Never resolve external entities (the feed is fetched over TLS but
        // is still third-party shaped input).
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isExpandEntityReferences = false
    }
    val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
    val entries = doc.getElementsByTagName("entry")
    val releases = mutableListOf<AtomRelease>()
    for (i in 0 until entries.length) {
        val entry = entries.item(i) as? Element ?: continue
        val link = entry.childElements("link").firstOrNull()?.getAttribute("href") ?: continue
        val tag = link.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: continue
        val updated = entry.childElements("updated").firstOrNull()?.textContent.orEmpty()
        releases.add(AtomRelease(tag = tag, link = link, updated = updated))
    }
    return releases
}

private fun Element.childElements(name: String): List<Element> {
    val children = childNodes
    val result = mutableListOf<Element>()
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node is Element && node.tagName == name) result.add(node)
    }
    return result
}
