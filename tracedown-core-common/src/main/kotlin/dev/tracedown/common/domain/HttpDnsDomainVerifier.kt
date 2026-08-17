package dev.tracedown.common.domain

import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import javax.naming.directory.InitialDirContext
import javax.naming.Context
import java.util.Hashtable

/**
 * Verifies domain ownership via HTTP well-known file or DNS TXT record.
 *
 * HTTP (http-01): fetches `http://{domain}/.well-known/tracedown-verify.txt`
 * and checks that the response body contains the challenge token.
 *
 * DNS (dns-01): looks up TXT records for `_tracedown-verify.{domain}`
 * and checks that one of them matches `tracedown-verify={challenge}`.
 */
class HttpDnsDomainVerifier(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val httpScheme: String = "https",
    private val httpPort: Int? = null,
) : DomainVerifier {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun verify(domain: String, challenge: String, verificationType: String): VerificationResult {
        return when (verificationType) {
            "http-01" -> verifyHttp(domain, challenge)
            "dns-01" -> verifyDns(domain, challenge)
            else -> VerificationResult(verified = false, error = "Unknown verification type: $verificationType")
        }
    }

    private fun verifyHttp(domain: String, challenge: String): VerificationResult {
        val host = if (httpPort != null) "$domain:$httpPort" else domain
        val url = "$httpScheme://$host/.well-known/tracedown-verify.txt"
        return try {
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    return VerificationResult(
                        verified = false,
                        error = "HTTP ${it.code} from $url",
                    )
                }

                val body = it.body?.string()?.trim() ?: ""
                if (body == challenge) {
                    VerificationResult(verified = true)
                } else {
                    VerificationResult(
                        verified = false,
                        error = "Challenge token mismatch at $url",
                    )
                }
            }
        } catch (e: Exception) {
            log.debug("HTTP verification failed for {}: {}", domain, e.message)
            VerificationResult(
                verified = false,
                error = "Failed to reach $url: ${e.message}",
            )
        }
    }

    private fun verifyDns(domain: String, challenge: String): VerificationResult {
        val lookupDomain = "_tracedown-verify.$domain"
        val expectedValue = "tracedown-verify=$challenge"
        return try {
            val env = Hashtable<String, String>()
            env[Context.INITIAL_CONTEXT_FACTORY] = "com.sun.jndi.dns.DnsContextFactory"
            val ctx = InitialDirContext(env)
            val attrs = ctx.getAttributes(lookupDomain, arrayOf("TXT"))
            val txtAttr = attrs.get("TXT")

            if (txtAttr == null) {
                return VerificationResult(
                    verified = false,
                    error = "No TXT records found for $lookupDomain",
                )
            }

            val values = (0 until txtAttr.size()).map { txtAttr.get(it).toString().trim('"', ' ') }
            if (values.any { it == expectedValue }) {
                VerificationResult(verified = true)
            } else {
                VerificationResult(
                    verified = false,
                    error = "No matching TXT record at $lookupDomain",
                )
            }
        } catch (e: javax.naming.NameNotFoundException) {
            VerificationResult(
                verified = false,
                error = "No DNS records found for $lookupDomain",
            )
        } catch (e: Exception) {
            log.debug("DNS verification failed for {}: {}", domain, e.message)
            VerificationResult(
                verified = false,
                error = "DNS lookup failed for $lookupDomain: ${e.message}",
            )
        }
    }
}
