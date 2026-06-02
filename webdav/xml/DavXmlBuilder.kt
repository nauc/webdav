package com.webdav.xml

import com.webdav.DavAce
import com.webdav.DavPrincipal
import javax.xml.namespace.QName

/**
 * Builds WebDAV XML request bodies without any XML framework dependencies.
 * All output is UTF-8 encoded XML strings.
 */
object DavXmlBuilder {

    private const val DAV_NS = "DAV:"
    private const val DAV_PREFIX = "D"

    // PROPFIND

    /** Builds `<D:propfind><D:allprop/></D:propfind>` */
    fun propfindAllProp(): String =
        xmlDecl() +
            """<D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>"""

    /** Builds `<D:propfind><D:prop>…props…</D:prop></D:propfind>` */
    fun propfindProps(props: Set<QName>): String {
        if (props.isEmpty()) {
            return xmlDecl() + """<D:propfind xmlns:D="DAV:"><D:prop/></D:propfind>"""
        }
        val nsMap = buildNsMap(props.map { it })
        return buildString {
            append(xmlDecl())
            append("<D:propfind")
            appendNsDeclarations(nsMap)
            append("><D:prop>")
            for (qname in props) {
                val prefix = nsMap[qname.namespaceURI] ?: DAV_PREFIX
                append("<$prefix:${qname.localPart}/>")
            }
            append("</D:prop></D:propfind>")
        }
    }

    /** Builds a PROPFIND for specific standard DAV properties used in ACL queries. */
    fun propfindAcl(): String =
        xmlDecl() +
            """<D:propfind xmlns:D="DAV:"><D:prop><D:owner/><D:group/><D:acl/></D:prop></D:propfind>"""

    /** Builds a PROPFIND for quota properties. */
    fun propfindQuota(): String =
        xmlDecl() +
            """<D:propfind xmlns:D="DAV:"><D:prop><D:quota-available-bytes/><D:quota-used-bytes/></D:prop></D:propfind>"""

    /** Builds a PROPFIND for principal-collection-set. */
    fun propfindPrincipalCollectionSet(): String =
        xmlDecl() +
            """<D:propfind xmlns:D="DAV:"><D:prop><D:principal-collection-set/></D:prop></D:propfind>"""

    /** Builds a PROPFIND for displayname + resourcetype + principal-URL. */
    fun propfindPrincipals(): String =
        xmlDecl() +
            """<D:propfind xmlns:D="DAV:"><D:prop><D:displayname/><D:resourcetype/><D:principal-URL/></D:prop></D:propfind>"""

    // PROPPATCH

    /**
     * Builds a `<D:propertyupdate>` body containing both set and remove sections.
     */
    fun proppatch(setProps: Map<QName, String>, removeProps: List<QName>): String {
        val allQNames = setProps.keys.toList() + removeProps
        val nsMap = buildNsMap(allQNames)
        return buildString {
            append(xmlDecl())
            append("<D:propertyupdate")
            appendNsDeclarations(nsMap)
            append(">")

            if (setProps.isNotEmpty()) {
                append("<D:set><D:prop>")
                for ((qname, value) in setProps) {
                    val prefix = nsMap[qname.namespaceURI] ?: DAV_PREFIX
                    append("<$prefix:${qname.localPart}>")
                    append(escapeXml(value))
                    append("</$prefix:${qname.localPart}>")
                }
                append("</D:prop></D:set>")
            }

            if (removeProps.isNotEmpty()) {
                append("<D:remove><D:prop>")
                for (qname in removeProps) {
                    val prefix = nsMap[qname.namespaceURI] ?: DAV_PREFIX
                    append("<$prefix:${qname.localPart}/>")
                }
                append("</D:prop></D:remove>")
            }

            append("</D:propertyupdate>")
        }
    }

    // LOCK

    /** Builds an exclusive write lock body. */
    fun lockinfo(): String =
        xmlDecl() +
            """<D:lockinfo xmlns:D="DAV:"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockinfo>"""

    // ACL

    /** Builds a `<D:acl>` body from the given list of [DavAce] entries. */
    fun aclRequest(aces: List<DavAce>): String = buildString {
        append(xmlDecl())
        append("""<D:acl xmlns:D="DAV:">""")
        for (ace in aces) {
            if (ace.inherited != null || ace.isProtected) continue
            append("<D:ace>")
            append(buildPrincipalXml(ace.principal))
            if (ace.granted.isNotEmpty()) {
                append("<D:grant>")
                ace.granted.forEach { append("<D:privilege><D:${requireValidXmlName(it)}/></D:privilege>") }
                append("</D:grant>")
            }
            if (ace.denied.isNotEmpty()) {
                append("<D:deny>")
                ace.denied.forEach { append("<D:privilege><D:${requireValidXmlName(it)}/></D:privilege>") }
                append("</D:deny>")
            }
            append("</D:ace>")
        }
        append("</D:acl>")
    }

    // SEARCH

    /** Builds a WebDAV SEARCH request body. */
    fun searchRequest(language: String, query: String): String = buildString {
        append(xmlDecl())
        append("""<D:searchrequest xmlns:D="DAV:">""")
        append("<${escapeXml(language)}>")
        append(escapeXml(query))
        append("</${escapeXml(language)}>")
        append("</D:searchrequest>")
    }

    // REPORT (sync-collection)

    /**
     * Builds a `<D:sync-collection>` REPORT body.
     */
    fun syncCollection(
        syncToken: String,
        syncLevel: String,
        props: Set<QName>,
        limit: Int? = null
    ): String {
        val nsMap = if (props.isEmpty()) mutableMapOf(DAV_NS to DAV_PREFIX)
        else buildNsMap(props.toList())

        return buildString {
            append(xmlDecl())
            append("<D:sync-collection")
            appendNsDeclarations(nsMap)
            append(">")
            append("<D:sync-token>${escapeXml(syncToken)}</D:sync-token>")
            append("<D:sync-level>${escapeXml(syncLevel)}</D:sync-level>")
            if (limit != null && limit > 0) {
                append("<D:limit><D:nresults>$limit</D:nresults></D:limit>")
            }
            append("<D:prop>")
            for (qname in props) {
                val prefix = nsMap[qname.namespaceURI] ?: DAV_PREFIX
                append("<$prefix:${qname.localPart}/>")
            }
            append("</D:prop>")
            append("</D:sync-collection>")
        }
    }

    // Private helpers

    private fun xmlDecl() = """<?xml version="1.0" encoding="utf-8"?>"""

    /** Builds a namespace-prefix map, always including DAV: → D */
    private fun buildNsMap(qnames: List<QName>): MutableMap<String, String> {
        val map = mutableMapOf(DAV_NS to DAV_PREFIX)
        var counter = 1
        for (qname in qnames) {
            val ns = qname.namespaceURI
            if (ns.isNotEmpty() && !map.containsKey(ns)) {
                val prefix = qname.prefix.ifEmpty { "ns${counter++}" }
                map[ns] = prefix
            }
        }
        return map
    }

    private fun StringBuilder.appendNsDeclarations(nsMap: Map<String, String>) {
        for ((ns, prefix) in nsMap) {
            append(""" xmlns:$prefix="$ns"""")
        }
    }

    private fun buildPrincipalXml(principal: DavPrincipal): String = buildString {
        append("<D:principal>")
        when (principal.principalType) {
            DavPrincipal.PrincipalType.HREF ->
                append("<D:href>${escapeXml(principal.value ?: "")}</D:href>")
            DavPrincipal.PrincipalType.KEY ->
                append("<D:${requireValidXmlName(principal.value ?: throw IllegalArgumentException("KEY principal has null value"))}/>")
            DavPrincipal.PrincipalType.PROPERTY -> {
                val qname = principal.property
                if (qname != null) {
                    val prefix = qname.prefix.ifEmpty { "p" }
                    val ns = qname.namespaceURI
                    append("""<D:property><$prefix:${qname.localPart} xmlns:$prefix="$ns"/></D:property>""")
                }
            }
            null -> { /* skip */ }
        }
        append("</D:principal>")
    }

    internal fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /**
     * Validates that [name] is a safe XML NCName (element/attribute name).
     * Allows letters, digits, underscore, hyphen and dot; must not start with a digit or hyphen.
     * Throws [IllegalArgumentException] on invalid input to prevent XML injection.
     */
    private fun requireValidXmlName(name: String): String {
        require(name.isNotEmpty() && name.matches(XML_NAME_REGEX)) {
            "Invalid XML name: '$name'"
        }
        return name
    }

    private val XML_NAME_REGEX = Regex("[a-zA-Z_][a-zA-Z0-9._\\-]*")
}

