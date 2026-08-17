package dev.tracedown.common.onboarding

import at.favre.lib.crypto.bcrypt.BCrypt

/**
 * Single source of truth for password hashing. Both account creation
 * (self-serve signup, invite accept) and login verification go through here so
 * the BCrypt cost factor never drifts between the write and read paths.
 */
object PasswordHasher {

    /** BCrypt cost factor. Matches the value previously inlined in the auth controller. */
    private const val COST = 12

    /** Hashes a plaintext password for storage. */
    fun hash(password: String): String =
        BCrypt.withDefaults().hashToString(COST, password.toCharArray())

    /** Verifies a plaintext password against a stored hash. */
    fun verify(password: String, hash: String): Boolean =
        BCrypt.verifyer().verify(password.toCharArray(), hash).verified
}
