package dev.tracedown.common.validation

/**
 * A request body that validates itself before the controller runs.
 *
 * Every route DTO received from a client implements this. A single generic
 * `RequestValidation` validator (installed per service) runs [validate] on any
 * `Validatable` body right after deserialization, so no request reaches a
 * controller — or the database — with a value that is too long or malformed.
 * That is enforced centrally rather than per handler.
 *
 * [validate] returns error codes (empty = valid). The service's `StatusPages`
 * maps a failure to `400` with the first code, matching the existing
 * `{ "error": "<code>" }` response shape; the frontend resolves the code to a
 * message via `errors.<code>`.
 *
 * Implementations build the list with [Validators]:
 * ```
 * override fun validate() = buildList {
 *     Validators.notBlank("name", name)?.let(::add)
 *     Validators.maxLen("name", name, 128)?.let(::add)
 * }
 * ```
 */
interface Validatable {
    fun validate(): List<String>
}
