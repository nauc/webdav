package com.webdav

import com.webdav.xml.ParsedAce

/**
 * An Access Control Entry (ACE) - grants or denies privileges to a principal.
 */
class DavAce private constructor(
    val principal: DavPrincipal,
    val granted: List<String>,
    val denied: List<String>,
    /** Href of the resource this ACE is inherited from, or null if not inherited. */
    val inherited: String?,
    val isProtected: Boolean
) {
    companion object {
        /**
         * Creates an ACE granting rights to the specified principal.
         */
        fun grant(principal: DavPrincipal, vararg privileges: String): DavAce =
            DavAce(principal, privileges.toList(), emptyList(), null, false)

        /**
         * Creates an ACE denying rights to the specified principal.
         */
        fun deny(principal: DavPrincipal, vararg privileges: String): DavAce =
            DavAce(principal, emptyList(), privileges.toList(), null, false)

        internal fun fromParsed(parsed: ParsedAce): DavAce {
            val principal = DavPrincipal.fromParsed(
                href = parsed.principalHref,
                key = parsed.principalKey,
                propertyNs = parsed.principalPropertyNs,
                propertyLocal = parsed.principalPropertyLocal
            )
            return DavAce(
                principal = principal,
                granted = parsed.granted,
                denied = parsed.denied,
                inherited = parsed.inheritedHref,
                isProtected = parsed.isProtected
            )
        }
    }
}

