package dev.tracedown.common.validation

/**
 * Reusable field checks for [Validatable.validate].
 *
 * Each returns an error code when the value is invalid, or null when it is fine —
 * so a DTO composes them into its error list:
 * ```
 * Validators.notBlank("email", email)?.let(::add)
 * Validators.email("email", email)?.let(::add)
 * ```
 * Codes are `<field>_<problem>` or `invalid_<field>`, kept stable so the frontend
 * can map them. Length limits should mirror the backing DB column so a value that
 * passes here can never overflow it downstream.
 */
object Validators {

    // RFC-5321-ish: good enough to reject typos and junk, not a full grammar.
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val COUNTRY = Regex("^[A-Za-z]{2}$")

    /** Required: rejects null/blank. */
    fun notBlank(field: String, value: String?): String? =
        if (value.isNullOrBlank()) "${field}_required" else null

    /** Caps length; null values pass (use [notBlank] for required fields). */
    fun maxLen(field: String, value: String?, max: Int): String? =
        if (value != null && value.length > max) "${field}_too_long" else null

    /** Enforces a minimum length on a present value. */
    fun minLen(field: String, value: String?, min: Int): String? =
        if (value != null && value.length < min) "${field}_too_short" else null

    /** A present value must look like an email. */
    fun email(field: String, value: String?): String? =
        if (value != null && !EMAIL.matches(value.trim())) "invalid_$field" else null

    /** A present value must be an ISO-3166 alpha-2 country code. */
    fun countryCode(field: String, value: String?): String? =
        if (value != null && !COUNTRY.matches(value.trim())) "invalid_$field" else null

    /** A present value must be a UUID. */
    fun uuid(field: String, value: String?): String? =
        if (value != null && !UUID_RE.matches(value)) "invalid_$field" else null

    /** A present value must match [regex]. */
    fun pattern(field: String, value: String?, regex: Regex): String? =
        if (value != null && !regex.matches(value)) "invalid_$field" else null

    /** A present value must be one of [allowed]. */
    fun oneOf(field: String, value: String?, allowed: Set<String>): String? =
        if (value != null && value !in allowed) "invalid_$field" else null

    /** An integer must fall within [range]. */
    fun inRange(field: String, value: Int?, range: IntRange): String? =
        if (value != null && value !in range) "invalid_$field" else null

    /** Every element of a present list must satisfy [check]; reports the first failure. */
    fun each(value: List<String>?, check: (String) -> String?): String? =
        value?.firstNotNullOfOrNull(check)
}
