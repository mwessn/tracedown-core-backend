package dev.tracedown.scheduler.crypto

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Wraps the platform trust manager and, on top of ordinary CA-chain
 * validation, enforces that the agent on the other end of the connection is
 * exactly the one the scheduler intended to dial.
 *
 * Chain trust alone only proves "signed by our internal CA" — every agent's
 * certificate satisfies that, so without this an agent (or anything that
 * hijacked an agent's address) could receive probe scripts and resolved secret
 * variables meant for a different agent. This adds, for the leaf certificate:
 *
 *  1. **Slug pinning** — a DNS SubjectAltName equal to [expectedSlug]. The
 *     gateway sets the SAN server-side from the authenticated slug, so this
 *     binds the connection to a specific agent identity, independent of the
 *     network address dialed.
 *  2. **EKU** — `serverAuth` must be present. Agent certificates are issued
 *     serverAuth-only; a certificate lacking it is not a legitimate agent cert.
 *  3. **Revocation** — the certificate must not be revoked (superseded or
 *     decommissioned).
 *
 * Client-side validation (the scheduler as TLS client) is unaffected for the
 * `checkClientTrusted` path, which simply delegates.
 */
class PinningTrustManager(
    private val delegate: X509TrustManager,
    private val expectedSlug: String,
    private val revocationChecker: RevocationChecker,
) : X509TrustManager {

    private companion object {
        const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
        const val SAN_TYPE_DNS = 2
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("empty certificate chain")
        // CA-chain, validity and basic-constraint checks first.
        delegate.checkServerTrusted(chain, authType)

        val leaf = chain[0]

        val eku = leaf.extendedKeyUsage
        if (eku == null || OID_SERVER_AUTH !in eku) {
            throw CertificateException("agent certificate lacks the serverAuth EKU")
        }

        if (!hasDnsSan(leaf, expectedSlug)) {
            throw CertificateException(
                "agent certificate identity does not match the expected agent '$expectedSlug'",
            )
        }

        if (revocationChecker.isRevoked(leaf)) {
            throw CertificateException("agent certificate has been revoked")
        }
    }

    private fun hasDnsSan(cert: X509Certificate, expected: String): Boolean {
        val sans = try {
            cert.subjectAlternativeNames ?: return false
        } catch (e: Exception) {
            return false
        }
        return sans.any { entry ->
            entry.size >= 2 && (entry[0] as? Int) == SAN_TYPE_DNS && entry[1] == expected
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        delegate.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
