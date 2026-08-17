package dev.tracedown.scheduler.crypto

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.network.tls.addCertificateChain
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds and caches the scheduler's outbound mTLS clients, one per target agent
 * slug. Each client presents the scheduler's `clientAuth` certificate and pins
 * the peer to a specific agent identity via [PinningTrustManager].
 *
 * A client is cached per slug (agents are few and long-lived), so pinning is
 * enforced at the TLS handshake rather than after a response has already been
 * received — the point at which an SSRF or misdirected-secret leak would have
 * already happened.
 */
class AgentMtlsClientFactory(
    private val schedulerCert: X509Certificate,
    private val schedulerKey: PrivateKey,
    private val caCert: X509Certificate,
    trustedCas: List<X509Certificate>,
    private val revocationChecker: RevocationChecker,
) {

    private val baseTrustManager: X509TrustManager = run {
        val trustStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            trustedCas.forEachIndexed { i, ca -> setCertificateEntry("ca-$i", ca) }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        tmf.trustManagers.first() as X509TrustManager
    }

    private val clients = ConcurrentHashMap<String, HttpClient>()

    /** Returns the pinned mTLS client for [expectedSlug], building it on first use. */
    fun client(expectedSlug: String): HttpClient =
        clients.computeIfAbsent(expectedSlug) { build(it) }

    private fun build(expectedSlug: String): HttpClient {
        val pinning = PinningTrustManager(baseTrustManager, expectedSlug, revocationChecker)
        return HttpClient(CIO) {
            engine {
                https {
                    trustManager = pinning
                    // Present the scheduler's client certificate — the agent
                    // requires it (mutual TLS) and, via its serverAuth/clientAuth
                    // EKU split, rejects anything but a genuine scheduler cert.
                    addCertificateChain(arrayOf(schedulerCert, caCert), schedulerKey)
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            // Per-request timeouts are set on each dispatch's `timeout { }` block.
            install(HttpTimeout)
        }
    }

    /** Shuts down every cached client. */
    fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }
}
