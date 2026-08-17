package dev.tracedown.common.domain

/**
 * Verifies domain ownership via challenge tokens.
 * Implementations check either HTTP well-known files or DNS TXT records.
 */
interface DomainVerifier {

    /**
     * Verifies that the challenge token is present at the expected location for the domain.
     * Returns a [VerificationResult] indicating success or failure with details.
     */
    fun verify(domain: String, challenge: String, verificationType: String): VerificationResult

    /** Always returns verified — used when trustedDomainMode is enabled. */
    object Trusted : DomainVerifier {
        override fun verify(domain: String, challenge: String, verificationType: String): VerificationResult {
            return VerificationResult(verified = true)
        }
    }
}

data class VerificationResult(
    val verified: Boolean,
    val error: String? = null,
)
