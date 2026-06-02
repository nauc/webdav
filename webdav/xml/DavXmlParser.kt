package com.hoggie.hoggiemap.webdav.xml

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

import java.io.InputStream
import javax.xml.namespace.QName

/**
 * Parses WebDAV XML responses (PROPFIND multistatus, LOCK responses)
 * using Android's XmlPullParser”.
 */
internal object DavXmlParser {

    const val DAV_NS = "DAV:"

    // Public API

    /**
     * Parses a WebDAV `multistatus` XML response into [ParsedMultistatus].
     */
    fun parseMultistatus(input: InputStream): ParsedMultistatus {
        val parser = createParser(input)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG &&
                parser.namespace == DAV_NS && parser.name == "multistatus"
            ) {
                return parseMultistatusElement(parser)
            }
        }
        return ParsedMultistatus(emptyList())
    }

    /**
     * Parses a WebDAV LOCK response and extracts the lock-token href.
     */
    fun parseLockToken(input: InputStream): String? {
        val parser = createParser(input)
        var inLockDiscovery = false
        var inActivelock = false
        var inLocktoken = false

        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            val isDav = parser.namespace == DAV_NS
            if (event == XmlPullParser.START_TAG && isDav) {
                when (parser.name) {
                    "lockdiscovery"  -> inLockDiscovery = true
                    "activelock"     -> if (inLockDiscovery) inActivelock = true
                    "locktoken"      -> if (inActivelock) inLocktoken = true
                    "href"           -> if (inLocktoken) return parser.nextText().trim()
                }
            } else if (event == XmlPullParser.END_TAG && isDav) {
                when (parser.name) {
                    "lockdiscovery"  -> inLockDiscovery = false
                    "activelock"     -> inActivelock = false
                    "locktoken"      -> inLocktoken = false
                }
            }
        }
        return null
    }

    // Private helpers

    private fun createParser(input: InputStream): XmlPullParser =
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            .newPullParser()
            .apply { setInput(input, null) }

    /**
     * Skips the current element and all its descendants.
     * Precondition: currentEvent == START_TAG.
     * Postcondition: currentEvent == the matching END_TAG.
     */
    private fun skipElement(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG   -> depth--
            }
        }
    }

    private fun parseMultistatusElement(parser: XmlPullParser): ParsedMultistatus {
        val responses = mutableListOf<ParsedDavResponse>()
        var syncToken: String? = null

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == "multistatus")
        ) {
            if (event == XmlPullParser.START_TAG && parser.namespace == DAV_NS) {
                when (parser.name) {
                    "response"   -> responses.add(parseResponseElement(parser))
                    "sync-token" -> syncToken = parser.nextText().trim()
                    else         -> skipElement(parser)
                }
            } else if (event == XmlPullParser.START_TAG) {
                skipElement(parser)
            }
            event = parser.next()
        }
        return ParsedMultistatus(responses, syncToken)
    }

    private fun parseResponseElement(parser: XmlPullParser): ParsedDavResponse {
        var href = ""
        val propStats = mutableListOf<ParsedPropStat>()
        var status: String? = null

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == "response")
        ) {
            if (event == XmlPullParser.START_TAG && parser.namespace == DAV_NS) {
                when (parser.name) {
                    "href"     -> href = parser.nextText().trim()
                    "propstat" -> propStats.add(parsePropStatElement(parser))
                    "status"   -> status = parser.nextText().trim()
                    else       -> skipElement(parser)
                }
            } else if (event == XmlPullParser.START_TAG) {
                skipElement(parser)
            }
            event = parser.next()
        }
        return ParsedDavResponse(href, propStats, status)
    }

    private fun parsePropStatElement(parser: XmlPullParser): ParsedPropStat {
        var prop = ParsedProp()
        var status: String? = null

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == "propstat")
        ) {
            if (event == XmlPullParser.START_TAG && parser.namespace == DAV_NS) {
                when (parser.name) {
                    "prop"   -> prop = parsePropElement(parser)
                    "status" -> status = parser.nextText().trim()
                    else     -> skipElement(parser)
                }
            } else if (event == XmlPullParser.START_TAG) {
                skipElement(parser)
            }
            event = parser.next()
        }
        return ParsedPropStat(prop, status)
    }

    @Suppress("LongMethod")
    private fun parsePropElement(parser: XmlPullParser): ParsedProp {
        var creationDate: String? = null
        var lastModified: String? = null
        var contentType: String? = null
        var contentLength: String? = null
        var etag: String? = null
        var displayName: String? = null
        var contentLanguage: String? = null
        var isCollection = false
        var isPrincipal = false
        val customProps = mutableMapOf<QName, String>()
        var ownerHref: String? = null
        var ownerUnauthenticated = false
        var groupHref: String? = null
        val aces = mutableListOf<ParsedAce>()
        var quotaAvailableBytes: String? = null
        var quotaUsedBytes: String? = null
        var lockTokenHref: String? = null
        val principalCollectionSetHrefs = mutableListOf<String>()

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == "prop")
        ) {
            if (event == XmlPullParser.START_TAG) {
                val ns = parser.namespace
                val local = parser.name
                if (ns == DAV_NS) {
                    when (local) {
                        "creationdate"          -> creationDate = parser.nextText().trim()
                        "getlastmodified"       -> lastModified = parser.nextText().trim()
                        "getcontenttype"        -> contentType = parser.nextText().trim()
                        "getcontentlength"      -> contentLength = parser.nextText().trim()
                        "getetag"               -> etag = parser.nextText().trim()
                        "displayname"           -> displayName = parser.nextText().trim()
                        "getcontentlanguage"    -> contentLanguage = parser.nextText().trim()
                        "resourcetype"          -> {
                            val (col, pri) = parseResourcetype(parser)
                            isCollection = col
                            isPrincipal = pri
                        }
                        "owner"                 -> {
                            val (href, unauth) = parseOwner(parser)
                            ownerHref = href
                            ownerUnauthenticated = unauth
                        }
                        "group"                 ->
                            groupHref = parseHrefContainer(parser, "group")
                        "acl"                   ->
                            aces.addAll(parseAcl(parser))
                        "quota-available-bytes" ->
                            quotaAvailableBytes = parser.nextText().trim()
                        "quota-used-bytes"      ->
                            quotaUsedBytes = parser.nextText().trim()
                        "lockdiscovery"         ->
                            lockTokenHref = parseLockDiscovery(parser)
                        "principal-collection-set" ->
                            principalCollectionSetHrefs.addAll(
                                parsePrincipalCollectionSet(parser)
                            )
                        else -> skipElement(parser)
                    }
                } else {
                    // Custom (non-DAV) property
                    val prefix = if (parser.prefix.isNullOrEmpty()) "" else parser.prefix
                    val qname = when {
                        ns.isNullOrEmpty() -> QName(local)
                        prefix.isEmpty()   -> QName(ns, local)
                        else               -> QName(ns, local, prefix)
                    }
                    val text = parser.nextText()
                    customProps[qname] = text
                }
            }
            event = parser.next()
        }

        return ParsedProp(
            creationDate = creationDate,
            lastModified = lastModified,
            contentType = contentType,
            contentLength = contentLength,
            etag = etag,
            displayName = displayName,
            contentLanguage = contentLanguage,
            isCollection = isCollection,
            isPrincipal = isPrincipal,
            customProps = customProps,
            ownerHref = ownerHref,
            ownerUnauthenticated = ownerUnauthenticated,
            groupHref = groupHref,
            aces = aces,
            quotaAvailableBytes = quotaAvailableBytes,
            quotaUsedBytes = quotaUsedBytes,
            lockTokenHref = lockTokenHref,
            principalCollectionSetHrefs = principalCollectionSetHrefs
        )
    }

    private fun parseResourcetype(parser: XmlPullParser): Pair<Boolean, Boolean> {
        var isCollection = false
        var isPrincipal = false

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == "resourcetype")
        ) {
            if (event == XmlPullParser.START_TAG && parser.namespace == DAV_NS) {
                when (parser.name) {
                    "collection" -> { isCollection = true; skipElement(parser) }
                    "principal"  -> { isPrincipal = true; skipElement(parser) }
                    else         -> skipElement(parser)
                }
            } else if (event == XmlPullParser.START_TAG) {
                skipElement(parser)
            }
            event = parser.next()
        }
        return Pair(isCollection, isPrincipal)
    }

    private fun parseOwner(parser: XmlPullParser): Pair<String?, Boolean> {
        var href: String? = null
        var unauthenticated = false

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == "owner")
        ) {
            if (event == XmlPullParser.START_TAG && parser.namespace == DAV_NS) {
                when (parser.name) {
                    "href"            -> href = parser.nextText().trim()
                    "unauthenticated" -> { unauthenticated = true; skipElement(parser) }
                    else              -> skipElement(parser)
                }
            } else if (event == XmlPullParser.START_TAG) {
                skipElement(parser)
            }
            event = parser.next()
        }
        return Pair(href, unauthenticated)
    }

    /** Parses a container element that contains a single `<D:href>` child. */
    private fun parseHrefContainer(parser: XmlPullParser, containerName: String): String? {
        var href: String? = null

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == containerName)
        ) {
            if (event == XmlPullParser.START_TAG &&
                parser.namespace == DAV_NS && parser.name == "href"
            ) {
                href = parser.nextText().trim()
            } else if (event == XmlPullParser.START_TAG) {
                skipElement(parser)
            }
            event = parser.next()
        }
        return href
    }

    private fun parseAcl(parser: XmlPullParser): List<ParsedAce> {
        val aces = mutableListOf<ParsedAce>()

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == "acl")
        ) {
            if (event == XmlPullParser.START_TAG &&
                parser.namespace == DAV_NS && parser.name == "ace"
            ) {
                aces.add(parseAce(parser))
            } else if (event == XmlPullParser.START_TAG) {
                skipElement(parser)
            }
            event = parser.next()
        }
        return aces
    }

    private fun parseAce(parser: XmlPullParser): ParsedAce {
        var principalHref: String? = null
        var principalKey: String? = null
        var principalPropertyNs: String? = null
        var principalPropertyLocal: String? = null
        val granted = mutableListOf<String>()
        val denied = mutableListOf<String>()
        var inheritedHref: String? = null
        var isProtected = false

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == "ace")
        ) {
            if (event == XmlPullParser.START_TAG && parser.namespace == DAV_NS) {
                when (parser.name) {
                    "principal" -> {
                        val pd = parsePrincipalElement(parser)
                        principalHref = pd.href
                        principalKey = pd.key
                        principalPropertyNs = pd.propertyNs
                        principalPropertyLocal = pd.propertyLocal
                    }
                    "grant"     -> granted.addAll(parsePrivileges(parser, "grant"))
                    "deny"      -> denied.addAll(parsePrivileges(parser, "deny"))
                    "inherited" -> inheritedHref = parseHrefContainer(parser, "inherited")
                    "protected" -> { isProtected = true; skipElement(parser) }
                    else        -> skipElement(parser)
                }
            } else if (event == XmlPullParser.START_TAG) {
                skipElement(parser)
            }
            event = parser.next()
        }
        return ParsedAce(
            principalHref = principalHref,
            principalKey = principalKey,
            principalPropertyNs = principalPropertyNs,
            principalPropertyLocal = principalPropertyLocal,
            granted = granted,
            denied = denied,
            inheritedHref = inheritedHref,
            isProtected = isProtected
        )
    }

    private data class PrincipalData(
        val href: String? = null,
        val key: String? = null,
        val propertyNs: String? = null,
        val propertyLocal: String? = null
    )

    private fun parsePrincipalElement(parser: XmlPullParser): PrincipalData {
        var href: String? = null
        var key: String? = null
        var propertyNs: String? = null
        var propertyLocal: String? = null

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == "principal")
        ) {
            if (event == XmlPullParser.START_TAG && parser.namespace == DAV_NS) {
                when (parser.name) {
                    "href"            -> href = parser.nextText().trim()
                    "all", "authenticated",
                    "unauthenticated", "self" -> {
                        key = parser.name
                        skipElement(parser)
                    }
                    "property"        -> {
                        val (ns, local) = parsePropertyPrincipal(parser)
                        propertyNs = ns
                        propertyLocal = local
                    }
                    else              -> skipElement(parser)
                }
            } else if (event == XmlPullParser.START_TAG) {
                skipElement(parser)
            }
            event = parser.next()
        }
        return PrincipalData(href, key, propertyNs, propertyLocal)
    }

    private fun parsePropertyPrincipal(parser: XmlPullParser): Pair<String?, String?> {
        var ns: String? = null
        var local: String? = null

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == "property")
        ) {
            if (event == XmlPullParser.START_TAG) {
                ns = parser.namespace
                local = parser.name
                skipElement(parser)
            }
            event = parser.next()
        }
        return Pair(ns, local)
    }

    private fun parsePrivileges(parser: XmlPullParser, containerName: String): List<String> {
        val privileges = mutableListOf<String>()

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == containerName)
        ) {
            if (event == XmlPullParser.START_TAG &&
                parser.namespace == DAV_NS && parser.name == "privilege"
            ) {
                privileges.addAll(parsePrivilege(parser))
            } else if (event == XmlPullParser.START_TAG) {
                skipElement(parser)
            }
            event = parser.next()
        }
        return privileges
    }

    private fun parsePrivilege(parser: XmlPullParser): List<String> {
        val privs = mutableListOf<String>()

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == "privilege")
        ) {
            if (event == XmlPullParser.START_TAG && parser.namespace == DAV_NS) {
                privs.add(parser.name)
                skipElement(parser)
            } else if (event == XmlPullParser.START_TAG) {
                skipElement(parser)
            }
            event = parser.next()
        }
        return privs
    }

    private fun parseLockDiscovery(parser: XmlPullParser): String? {
        var token: String? = null

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == "lockdiscovery")
        ) {
            if (event == XmlPullParser.START_TAG &&
                parser.namespace == DAV_NS && parser.name == "activelock"
            ) {
                token = parseActivelock(parser)
            } else if (event == XmlPullParser.START_TAG) {
                skipElement(parser)
            }
            event = parser.next()
        }
        return token
    }

    private fun parseActivelock(parser: XmlPullParser): String? {
        var token: String? = null

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == "activelock")
        ) {
            if (event == XmlPullParser.START_TAG &&
                parser.namespace == DAV_NS && parser.name == "locktoken"
            ) {
                token = parseHrefContainer(parser, "locktoken")
            } else if (event == XmlPullParser.START_TAG) {
                skipElement(parser)
            }
            event = parser.next()
        }
        return token
    }

    private fun parsePrincipalCollectionSet(parser: XmlPullParser): List<String> {
        val hrefs = mutableListOf<String>()

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG &&
                    parser.namespace == DAV_NS && parser.name == "principal-collection-set")
        ) {
            if (event == XmlPullParser.START_TAG &&
                parser.namespace == DAV_NS && parser.name == "href"
            ) {
                hrefs.add(parser.nextText().trim())
            } else if (event == XmlPullParser.START_TAG) {
                skipElement(parser)
            }
            event = parser.next()
        }
        return hrefs
    }
}


