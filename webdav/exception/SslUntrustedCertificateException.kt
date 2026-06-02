package com.webdav.exception

import java.security.cert.X509Certificate

/**
 * Thrown when the server presents an SSL certificate that is not trusted
 * by the system CA store and no certificate has been pinned yet.
 *
 * The caller should present the [sha256Fingerprint] and [subjectCN] to the user
 * for confirmation, then save [certificate] via [WebDavPreferences.setPinnedCertificate].
 */
class SslUntrustedCertificateException(
    /** The server's leaf certificate (DER-encoded). */
    val certificate: X509Certificate,
    /** SHA-256 fingerprint formatted as "AA:BB:CC:..." */
    val sha256Fingerprint: String,
    /** Common Name from the certificate subject, e.g. "synology.local" */
    val subjectCN: String
) : Exception("Untrusted SSL certificate: CN=$subjectCN")

