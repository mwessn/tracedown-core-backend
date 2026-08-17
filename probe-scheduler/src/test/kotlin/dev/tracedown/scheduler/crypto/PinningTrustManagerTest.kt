package dev.tracedown.scheduler.crypto

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Date
import javax.net.ssl.X509TrustManager

/**
 * Unit coverage for the scheduler's TLS peer-identity enforcement: it must
 * accept only the specific agent it intended to dial, reject a valid-but-wrong
 * agent, a certificate lacking serverAuth, and a revoked certificate.
 */
class PinningTrustManagerTest {

    private companion object {
        init {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    /** A trust manager that trusts anything (isolates the pinning logic under test). */
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun key(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()

    private fun leaf(cn: String, dnsSan: String?, eku: KeyPurposeId?): X509Certificate {
        val kp = key()
        val subject = X500Name("CN=$cn")
        val now = Instant.now()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()),
            Date.from(now.minus(Duration.ofDays(1))),
            Date.from(now.plus(Duration.ofDays(30))),
            subject,
            kp.public,
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            if (dnsSan != null) {
                addExtension(
                    Extension.subjectAlternativeName,
                    false,
                    GeneralNames(GeneralName(GeneralName.dNSName, dnsSan)),
                )
            }
            if (eku != null) {
                addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(eku))
            }
        }
        val holder = builder.build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
    }

    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    private fun noRevocations() = RevocationChecker(ttlMillis = 0) { emptySet() }

    @Test
    fun `accepts the intended agent`() {
        val cert = leaf("agent-a", "agent-a", KeyPurposeId.id_kp_serverAuth)
        val tm = PinningTrustManager(trustAll, "agent-a", noRevocations())
        tm.checkServerTrusted(arrayOf(cert), "RSA") // does not throw
    }

    @Test
    fun `rejects a valid certificate for a different agent`() {
        val cert = leaf("agent-b", "agent-b", KeyPurposeId.id_kp_serverAuth)
        val tm = PinningTrustManager(trustAll, "agent-a", noRevocations())
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(cert), "RSA")
        }
    }

    @Test
    fun `rejects a certificate without the serverAuth EKU`() {
        val clientAuthCert = leaf("agent-a", "agent-a", KeyPurposeId.id_kp_clientAuth)
        val tm = PinningTrustManager(trustAll, "agent-a", noRevocations())
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(clientAuthCert), "RSA")
        }
    }

    @Test
    fun `rejects a revoked certificate`() {
        val cert = leaf("agent-a", "agent-a", KeyPurposeId.id_kp_serverAuth)
        val revoked = RevocationChecker(ttlMillis = 0) { setOf(fingerprint(cert)) }
        val tm = PinningTrustManager(trustAll, "agent-a", revoked)
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(cert), "RSA")
        }
    }
}
