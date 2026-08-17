package dev.tracedown.gateway.controllers.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.SessionAuthenticator
import dev.tracedown.common.auth.SessionResult
import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.common.email.EmailPublisher
import dev.tracedown.common.models.OrgGroups
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.onboarding.PasswordHasher
import dev.tracedown.common.auth.canRead
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.PasswordResetTokens
import dev.tracedown.common.models.SessionStatus
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.TotpRecoveryCodes
import dev.tracedown.common.models.Users
import dev.tracedown.gateway.context.AuthPrincipal
import dev.tracedown.gateway.data.auth.LoginRequest
import dev.tracedown.gateway.data.auth.LoginResponse
import dev.tracedown.gateway.data.auth.MeResponse
import dev.tracedown.gateway.data.auth.OrgMembership
import dev.tracedown.gateway.data.auth.OrgPermissionsDto
import dev.tracedown.gateway.data.auth.TotpSetupResponse
import dev.tracedown.gateway.data.auth.UserSummary
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.PasswordPolicyConfig
import dev.tracedown.gateway.util.UnauthorizedException
import dev.tracedown.gateway.util.validatePassword
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AuthController {

    /** Mirrors platform.trustedDomainMode; set once at startup via [init]. */
    private var trustedDomainMode: Boolean = true

    fun init(trustedDomainMode: Boolean) {
        this.trustedDomainMode = trustedDomainMode
    }

    private val secureRandom = SecureRandom()
    private const val CHALLENGE_TTL_SECONDS = 300L // 5 minutes
    private const val SESSION_TOUCH_DEBOUNCE_SECONDS = 60L
    private const val MAX_TOTP_ATTEMPTS = 5 // failed codes per pending session before lockout

    /** Tracks last touch time per session to debounce last_active_at updates. */
    private val sessionTouchCache = java.util.concurrent.ConcurrentHashMap<UUID, Long>()

    private lateinit var hmacKey: ByteArray
    private var totpIssuer: String = "Tracedown"

    fun init(aesKeyHex: String, totpIssuer: String = "Tracedown") {
        hmacKey = aesKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        this.totpIssuer = totpIssuer
    }

    /**
     * Authenticates user credentials and determines the login response:
     * - If TOTP is enabled or enforced and user is enrolled: returns challenge for TOTP verification
     * - If TOTP is enforced but user is NOT enrolled: returns setupToken for TOTP enrollment
     * - Otherwise: creates session directly
     */
    fun login(
        request: LoginRequest,
        sessionTtlMinutes: Long,
        ipAddress: String?,
        userAgent: String?,
    ): LoginResponse {
        if (request.email.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (request.password.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)

        return transaction {
            val user = verifyCredentials(request.email, request.password)
            val userId = user[Users.id]
            val userHasTotp = user[Users.totpEnabled]
            val targetOrgId = resolveTargetOrgId(user)
            // A user with no active organization may still sign in — they land on
            // the app's "no organizations" screen (e.g. removed from their last
            // org, or a pending invitee). Org-mandated TOTP only applies when
            // there is actually an org to enforce it.
            val totpEnforced = targetOrgId != null && isTotpEnforcedForOrg(userId, targetOrgId)

            when {
                userHasTotp -> {
                    // User has TOTP enrolled — open a pending session; its id is the
                    // challenge the client echoes back to verifyTotp.
                    val pendingId = createPendingSession(userId, targetOrgId, ipAddress, userAgent)
                    LoginResponse(totpRequired = true, challenge = pendingId.toString())
                }
                totpEnforced -> {
                    // TOTP is enforced but user hasn't enrolled — require setup
                    val setupToken = createChallenge(userId)
                    LoginResponse(totpSetupRequired = true, setupToken = setupToken)
                }
                else -> {
                    createSession(user, sessionTtlMinutes, ipAddress, userAgent)
                }
            }
        }
    }

    /**
     * Verifies a TOTP code against the pending session named by [challenge] (its
     * id) and, on success, activates that same row into a usable session — the
     * bearer token is minted only here, so the pre-auth challenge never doubles
     * as a credential. Failed codes increment the row's attempt counter and lock
     * it at [MAX_TOTP_ATTEMPTS] for the remainder of its TTL.
     */
    fun verifyTotp(
        challenge: String,
        code: String,
        sessionTtlMinutes: Long,
        ipAddress: String?,
        userAgent: String?,
    ): LoginResponse {
        val pendingId = try {
            UUID.fromString(challenge)
        } catch (_: IllegalArgumentException) {
            throw UnauthorizedException()
        }

        return transaction {
            val pending = Sessions.selectAll()
                .where {
                    (Sessions.id eq pendingId) and
                    (Sessions.status eq SessionStatus.PENDING_TOTP)
                }
                .firstOrNull()
                ?: throw UnauthorizedException()

            if (pending[Sessions.expiresAt] < Instant.now()) {
                throw UnauthorizedException(ErrorCodes.SESSION_EXPIRED)
            }

            val attempts = pending[Sessions.totpAttemptCount]
            if (attempts >= MAX_TOTP_ATTEMPTS) {
                throw UnauthorizedException(ErrorCodes.INVALID_TOTP_CODE)
            }

            val userId = pending[Sessions.userId]
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull()
                ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

            if (!user[Users.isActive]) throw UnauthorizedException(ErrorCodes.ACCOUNT_DEACTIVATED)

            val secret = user[Users.totpSecretEncrypted]
                ?: throw UnauthorizedException(ErrorCodes.TOTP_NOT_CONFIGURED)
            val iv = user[Users.totpSecretIv]
                ?: throw UnauthorizedException(ErrorCodes.TOTP_NOT_CONFIGURED)

            val decryptedSecret = TotpUtil.decryptSecret(secret, iv, hmacKey)
            val valid = TotpUtil.verifyCode(decryptedSecret, code) || tryRecoveryCode(userId, code)

            if (!valid) {
                Sessions.update({ Sessions.id eq pendingId }) {
                    it[totpAttemptCount] = attempts + 1
                }
                throw UnauthorizedException(ErrorCodes.INVALID_TOTP_CODE)
            }

            Users.update({ Users.id eq userId }) {
                it[totpLastUsedAt] = Instant.now()
            }

            // Activate the pending row in place: mint the bearer token now.
            val token = generateToken()
            val now = Instant.now()
            val expiresAt = now.plusSeconds(sessionTtlMinutes * 60)
            Sessions.update({ Sessions.id eq pendingId }) {
                it[sessionTokenHash] = TokenHasher.sha256Hex(token)
                it[status] = SessionStatus.ACTIVE
                it[Sessions.expiresAt] = expiresAt
                it[lastActiveAt] = now
                it[Sessions.ipAddress] = ipAddress ?: pending[Sessions.ipAddress]
                it[Sessions.userAgent] = userAgent ?: pending[Sessions.userAgent]
            }

            LoginResponse(
                token = token,
                expiresAt = expiresAt.toString(),
                user = userSummaryFrom(user),
            )
        }
    }

    /**
     * Generates a new TOTP secret for enrollment.
     * Returns the secret as base32 and an otpauth URI for QR code generation,
     * plus a confirmToken that embeds the encrypted secret for confirmation.
     */
    fun beginTotpSetup(setupToken: String): TotpSetupResponse {
        val userId = validateChallenge(setupToken)
        return generateTotpSetup(userId)
    }

    /**
     * Generates a new TOTP secret + confirm token for [userId]. Shared by the
     * login-time setup flow (validated via setupToken) and authenticated
     * self-service enrollment (validated via session principal).
     */
    fun generateTotpSetup(userId: UUID): TotpSetupResponse {
        return transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull()
                ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

            if (user[Users.totpEnabled]) {
                throw BadRequestException(ErrorCodes.ALREADY_EXISTS)
            }

            // Generate new secret
            val secret = ByteArray(20).also { secureRandom.nextBytes(it) }
            val base32Secret = encodeBase32(secret)
            val email = user[Users.email]
            val otpauthUri = "otpauth://totp/${totpIssuer}:${email}?secret=${base32Secret}&issuer=${totpIssuer}&digits=6&period=30"

            // Encrypt and embed in confirm token
            val (encrypted, iv) = TotpUtil.encryptSecret(secret, hmacKey)
            val confirmPayload = "$userId:$encrypted:$iv"
            val expiry = Instant.now().epochSecond + CHALLENGE_TTL_SECONDS
            val signedPayload = "$confirmPayload:$expiry"
            val sig = hmacSign(signedPayload)
            val confirmToken = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("$signedPayload:$sig".toByteArray())

            TotpSetupResponse(
                secret = base32Secret,
                otpauthUri = otpauthUri,
                confirmToken = confirmToken,
            )
        }
    }

    /**
     * Confirms TOTP enrollment by verifying a code against the embedded secret.
     * Stores the encrypted secret, generates recovery codes, and creates a session.
     * Recovery codes are returned in the LoginResponse (shown once, never again).
     */
    fun confirmTotpSetup(
        confirmToken: String,
        code: String,
        sessionTtlMinutes: Long,
        ipAddress: String?,
        userAgent: String?,
    ): LoginResponse {
        // Decode confirm token: userId:encrypted:iv:expiry:sig
        val decoded = try {
            String(Base64.getUrlDecoder().decode(confirmToken))
        } catch (e: Exception) {
            throw UnauthorizedException(ErrorCodes.INVALID_SETUP_TOKEN)
        }

        val parts = decoded.split(":")
        if (parts.size != 5) throw UnauthorizedException(ErrorCodes.INVALID_SETUP_TOKEN)

        val userId = try { UUID.fromString(parts[0]) } catch (e: Exception) {
            throw UnauthorizedException(ErrorCodes.INVALID_SETUP_TOKEN)
        }
        val encrypted = parts[1]
        val iv = parts[2]
        val expiry = parts[3].toLongOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_SETUP_TOKEN)
        val sig = parts[4]

        if (Instant.now().epochSecond > expiry) {
            throw UnauthorizedException(ErrorCodes.SETUP_TOKEN_EXPIRED)
        }

        val payload = "${parts[0]}:${parts[1]}:${parts[2]}:${parts[3]}"
        if (hmacSign(payload) != sig) {
            throw UnauthorizedException(ErrorCodes.INVALID_SETUP_TOKEN)
        }

        // Verify the code against the embedded secret
        val secret = TotpUtil.decryptSecret(encrypted, iv, hmacKey)
        if (!TotpUtil.verifyCode(secret, code)) {
            throw UnauthorizedException(ErrorCodes.INVALID_TOTP_CODE)
        }

        return transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull()
                ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

            // Store the secret and enable TOTP
            Users.update({ Users.id eq userId }) {
                it[totpSecretEncrypted] = encrypted
                it[totpSecretIv] = iv
                it[totpEnabled] = true
                it[totpEnrolledAt] = Instant.now()
                it[totpLastUsedAt] = Instant.now()
            }

            // Generate recovery codes (shown once)
            val recoveryCodes = generateRecoveryCodes(userId)

            // Re-read user to get updated fields
            val updatedUser = Users.selectAll()
                .where { Users.id eq userId }
                .first()

            val response = createSession(updatedUser, sessionTtlMinutes, ipAddress, userAgent)
            response.copy(recoveryCodes = recoveryCodes)
        }
    }

    /**
     * Resolves a session token to an AuthPrincipal.
     * Checks TOTP enrollment enforcement — if TOTP is required but not enrolled,
     * throws 403 unless the request path is exempt (handled by caller).
     */
    fun resolveSession(token: String, checkTotpEnrollment: Boolean = true): AuthPrincipal {
        // Validity is decided by the shared authenticator (one definition for
        // gateway + realtime). Per-reason mapping preserves the gateway's error codes.
        val ctx = when (val result = SessionAuthenticator.authenticate(token)) {
            is SessionResult.Valid -> result.context
            is SessionResult.Invalid -> throw when (result.reason) {
                SessionResult.Reason.EXPIRED -> UnauthorizedException(ErrorCodes.SESSION_EXPIRED)
                SessionResult.Reason.USER_DELETED -> UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)
                SessionResult.Reason.USER_INACTIVE -> UnauthorizedException(ErrorCodes.ACCOUNT_DEACTIVATED)
                SessionResult.Reason.NOT_FOUND, SessionResult.Reason.REVOKED -> UnauthorizedException()
            }
        }

        // TOTP enrollment guard — gateway policy, only enforced for the session's org.
        val orgId = ctx.organizationId
        if (checkTotpEnrollment && !ctx.totpEnabled && orgId != null) {
            val enforced = transaction { isTotpEnforcedForOrg(ctx.userId, orgId) }
            if (enforced) throw ForbiddenException()
        }

        touchSessionActivity(ctx.sessionId)

        return AuthPrincipal(
            userId = ctx.userId,
            sessionId = ctx.sessionId,
            email = ctx.email,
            organizationId = ctx.organizationId,
        )
    }

    /**
     * Debounced session activity touch. Only writes to the DB if the session
     * hasn't been touched in the last [SESSION_TOUCH_DEBOUNCE_SECONDS].
     * Uses atomic ConcurrentHashMap.compute to prevent concurrent requests
     * from racing past the debounce check.
     */
    private fun touchSessionActivity(sessionId: UUID) {
        val now = Instant.now().epochSecond
        var shouldWrite = false
        sessionTouchCache.compute(sessionId) { _, lastTouch ->
            if (lastTouch == null || now - lastTouch >= SESSION_TOUCH_DEBOUNCE_SECONDS) {
                shouldWrite = true
                now
            } else {
                lastTouch
            }
        }
        if (!shouldWrite) return

        try {
            transaction {
                Sessions.update({ Sessions.id eq sessionId }) {
                    it[lastActiveAt] = Instant.now()
                }
            }
        } catch (_: Exception) {
            // Best-effort — if it fails, next debounce window will retry
        }
    }

    /** Revokes a session. */
    fun logout(sessionId: UUID) {
        transaction {
            Sessions.update({ Sessions.id eq sessionId }) {
                it[revoked] = true
            }
        }
    }

    /** Returns the current user's summary. */
    fun me(principal: AuthPrincipal): UserSummary {
        return transaction {
            val user = Users.selectAll()
                .where { Users.id eq principal.userId }
                .first()
            userSummaryFrom(user)
        }
    }

    /** Returns the user profile wrapped with org-level permissions (if org selected). */
    fun meWithPermissions(principal: AuthPrincipal): MeResponse {
        return transaction {
            val user = Users.selectAll()
                .where { Users.id eq principal.userId }
                .first()
            val userSummary = userSummaryFrom(user)

            val cached = principal.organizationId?.let { orgId ->
                dev.tracedown.common.auth.resolveCachedPermissions(orgId, principal.userId)
            }
            val permissions = cached?.org?.let {
                OrgPermissionsDto(
                    users = it.users,
                    settings = it.settings,
                    domains = it.domains,
                    webhooks = it.webhooks,
                    notifications = it.notifications,
                    admin = it.admin,
                    workspaces = it.workspaces,
                    isOwner = it.isOwner,
                    // Sections registered by additional modules, so a host's
                    // surfaces can gate on their own permissions like built-ins.
                    extra = it.extra,
                )
            }

            val orgDefaultTimezone = principal.organizationId?.let { orgId ->
                Organizations.selectAll()
                    .where { Organizations.id eq orgId }
                    .firstOrNull()
                    ?.get(Organizations.defaultTimezone)
            }

            MeResponse(
                user = userSummary,
                organizationId = principal.organizationId?.toString(),
                permissions = permissions,
                resources = cached?.resources ?: emptyMap(),
                // Needed by anyone editing scripts (window editor prefill) —
                // not sensitive, always included.
                orgDefaultTimezone = orgDefaultTimezone,
                // Platform-config disclosure — settings readers only; others
                // get the safe default (domains UI hidden either way).
                trustedDomainMode = if (cached?.org?.settings?.canRead() == true) {
                    trustedDomainMode
                } else {
                    true
                },
            )
        }
    }

    /** Returns all active org memberships for the current user (id + name pairs). */
    fun listOrgs(userId: UUID): List<OrgMembership> {
        return transaction {
            (OrgUsers innerJoin Organizations)
                .select(Organizations.id, Organizations.name)
                .where {
                    (OrgUsers.userId eq userId) and
                    (OrgUsers.status eq "active") and
                    (OrgUsers.deleted eq false) and
                    (Organizations.deleted eq false)
                }
                .map { row ->
                    OrgMembership(
                        id = row[Organizations.id].toString(),
                        name = row[Organizations.name],
                    )
                }
        }
    }

    /**
     * Switches the user's active organization. Revokes the current session
     * and creates a new one scoped to the target org. Updates selectedOrgId
     * so future logins default to this org.
     */
    fun switchOrg(
        principal: AuthPrincipal,
        targetOrgId: UUID,
        sessionTtlMinutes: Long,
        ipAddress: String?,
        userAgent: String?,
    ): LoginResponse {
        return transaction {
            // Verify membership in target org
            OrgUsers.selectAll()
                .where {
                    (OrgUsers.userId eq principal.userId) and
                    (OrgUsers.organizationId eq targetOrgId) and
                    (OrgUsers.status eq "active") and
                    (OrgUsers.deleted eq false)
                }
                .firstOrNull()
                ?: throw BadRequestException(ErrorCodes.NOT_ORG_MEMBER)

            // Update selectedOrgId for future logins
            Users.update({ Users.id eq principal.userId }) {
                it[selectedOrgId] = targetOrgId
            }

            // Revoke current session
            Sessions.update({ Sessions.id eq principal.sessionId }) {
                it[revoked] = true
            }

            // Create new session directly scoped to target org (bypass selectedOrgId resolution)
            val user = Users.selectAll()
                .where { Users.id eq principal.userId }
                .first()

            val sessionId = UUID.randomUUID()
            val token = generateToken()
            val now = Instant.now()
            val expiresAt = now.plusSeconds(sessionTtlMinutes * 60)

            Sessions.insert {
                it[id] = sessionId
                it[Sessions.userId] = principal.userId
                it[organizationId] = targetOrgId
                it[sessionTokenHash] = TokenHasher.sha256Hex(token)
                it[Sessions.ipAddress] = ipAddress
                it[Sessions.userAgent] = userAgent
                it[Sessions.expiresAt] = expiresAt
                it[lastActiveAt] = now
                it[createdAt] = now
            }

            LoginResponse(
                token = token,
                expiresAt = expiresAt.toString(),
                user = userSummaryFrom(user),
            )
        }
    }

    /** Creates a session for a user — used by invite acceptance flow. */
    fun createSessionForUser(
        user: ResultRow,
        sessionTtlMinutes: Long,
        ipAddress: String?,
        userAgent: String?,
    ): LoginResponse = createSession(user, sessionTtlMinutes, ipAddress, userAgent)

    // ── Password Reset ──

    /**
     * Initiates a password reset. Sends an email with a reset link.
     * Always returns success (timing-safe) to prevent email enumeration.
     */
    fun requestPasswordReset(
        email: String,
        emailPublisher: EmailPublisher,
        resetUrlBuilder: (String) -> String,
        expiryMinutes: Long = 60,
    ) {
        if (email.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)

        // Timing normalization: always take ~500ms regardless of whether user exists
        val startTime = System.currentTimeMillis()

        transaction {
            val user = Users.selectAll()
                .where { (Users.email eq email) and (Users.deleted eq false) and (Users.isActive eq true) }
                .firstOrNull()

            if (user != null) {
                val userId = user[Users.id]
                val rawToken = generateToken()
                val tokenHash = BCrypt.withDefaults().hashToString(10, rawToken.toCharArray())
                val now = Instant.now()
                val expiresAt = now.plusSeconds(expiryMinutes * 60)

                // Invalidate any existing unused reset tokens for this user
                PasswordResetTokens.update({
                    (PasswordResetTokens.userId eq userId) and (PasswordResetTokens.used eq false)
                }) {
                    it[used] = true
                }

                PasswordResetTokens.insert {
                    it[id] = UUID.randomUUID()
                    it[PasswordResetTokens.userId] = userId
                    it[PasswordResetTokens.tokenHash] = tokenHash
                    it[PasswordResetTokens.expiresAt] = expiresAt
                    it[createdAt] = now
                }

                val resetLink = resetUrlBuilder(rawToken)
                emailPublisher.publish(
                    to = email,
                    subject = "Reset your password",
                    type = "system.password-reset",
                    vars = mapOf(
                        "userName" to user[Users.displayName],
                        "expiryMinutes" to expiryMinutes.toString(),
                        "resetLink" to resetLink,
                    ),
                    source = "api-gateway",
                )
            }
        }

        // Normalize timing to prevent enumeration
        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed < 500) {
            Thread.sleep(500 - elapsed)
        }
    }

    /**
     * Confirms a password reset by validating the token and setting a new password.
     * The token is single-use and expires after the configured TTL.
     */
    fun confirmPasswordReset(token: String, newPassword: String, passwordPolicy: PasswordPolicyConfig) {
        if (token.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (newPassword.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)

        val errors = validatePassword(newPassword, passwordPolicy)
        if (errors.isNotEmpty()) throw BadRequestException(ErrorCodes.PASSWORD_TOO_WEAK)

        transaction {
            // Find all unused, non-expired tokens and verify against each
            val candidates = PasswordResetTokens.selectAll()
                .where { (PasswordResetTokens.used eq false) }
                .toList()

            val matchedToken = candidates.firstOrNull { row ->
                val expiresAt = row[PasswordResetTokens.expiresAt]
                expiresAt > Instant.now() &&
                BCrypt.verifyer().verify(token.toCharArray(), row[PasswordResetTokens.tokenHash]).verified
            } ?: throw BadRequestException(ErrorCodes.INVALID_TOKEN)

            val userId = matchedToken[PasswordResetTokens.userId]
            val passwordHash = PasswordHasher.hash(newPassword)

            // Update password
            Users.update({ Users.id eq userId }) {
                it[Users.passwordHash] = passwordHash
            }

            // Mark token as used
            PasswordResetTokens.update({ PasswordResetTokens.id eq matchedToken[PasswordResetTokens.id] }) {
                it[used] = true
            }

            // Revoke all sessions for security
            Sessions.update({
                (Sessions.userId eq userId) and (Sessions.revoked eq false)
            }) {
                it[revoked] = true
            }
        }
    }

    // ── Profile ──

    /** Updates the user's display name. */
    fun updateProfile(userId: UUID, displayName: String?): UserSummary {
        return transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

            if (displayName != null) {
                if (displayName.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
                if (displayName.length > 128) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
            }

            Users.update({ Users.id eq userId }) {
                displayName?.let { v -> it[Users.displayName] = v }
            }

            val updated = Users.selectAll().where { Users.id eq userId }.first()
            userSummaryFrom(updated)
        }
    }

    /** Changes the user's password. Requires the current password for verification. */
    fun changePassword(userId: UUID, currentPassword: String, newPassword: String, passwordPolicy: PasswordPolicyConfig) {
        if (currentPassword.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (newPassword.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)

        val errors = validatePassword(newPassword, passwordPolicy)
        if (errors.isNotEmpty()) throw BadRequestException(ErrorCodes.PASSWORD_TOO_WEAK)

        transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

            val hashResult = BCrypt.verifyer().verify(
                currentPassword.toCharArray(),
                user[Users.passwordHash],
            )
            if (!hashResult.verified) throw BadRequestException(ErrorCodes.INCORRECT_PASSWORD)

            val newHash = PasswordHasher.hash(newPassword)
            Users.update({ Users.id eq userId }) {
                it[passwordHash] = newHash
            }
        }
    }

    /**
     * Re-verifies the caller's identity for sensitive operations: password
     * always; a TOTP (or recovery) code when the user is enrolled. Throws on
     * any mismatch. Call within a transaction-free context.
     */
    fun verifyIdentity(userId: UUID, password: String, code: String?) {
        transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

            val hashResult = BCrypt.verifyer().verify(
                password.toCharArray(),
                user[Users.passwordHash],
            )
            if (!hashResult.verified) throw BadRequestException(ErrorCodes.INCORRECT_PASSWORD)

            val secret = user[Users.totpSecretEncrypted]
            val iv = user[Users.totpSecretIv]
            if (secret != null && iv != null) {
                if (code.isNullOrBlank()) throw BadRequestException(ErrorCodes.INVALID_TOTP_CODE)
                val decryptedSecret = TotpUtil.decryptSecret(secret, iv, hmacKey)
                val valid = TotpUtil.verifyCode(decryptedSecret, code) || tryRecoveryCode(userId, code)
                if (!valid) throw BadRequestException(ErrorCodes.INVALID_TOTP_CODE)
            }
        }
    }

    // ── TOTP Management ──

    /** Disables TOTP for a user. Requires a valid TOTP code or recovery code for verification. */
    fun disableTotp(userId: UUID, code: String) {
        if (code.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)

        transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

            if (!user[Users.totpEnabled]) {
                throw BadRequestException(ErrorCodes.TOTP_NOT_CONFIGURED)
            }

            val secret = user[Users.totpSecretEncrypted]
                ?: throw BadRequestException(ErrorCodes.TOTP_NOT_CONFIGURED)
            val iv = user[Users.totpSecretIv]
                ?: throw BadRequestException(ErrorCodes.TOTP_NOT_CONFIGURED)

            val decryptedSecret = TotpUtil.decryptSecret(secret, iv, hmacKey)
            if (!TotpUtil.verifyCode(decryptedSecret, code)) {
                // Try recovery code
                if (!tryRecoveryCode(userId, code)) {
                    throw BadRequestException(ErrorCodes.INVALID_TOTP_CODE)
                }
            }

            // Clear TOTP fields
            Users.update({ Users.id eq userId }) {
                it[totpEnabled] = false
                it[totpSecretEncrypted] = null
                it[totpSecretIv] = null
                it[totpEnrolledAt] = null
            }

            // Delete all recovery codes
            TotpRecoveryCodes.deleteWhere { TotpRecoveryCodes.userId eq userId }
        }
    }

    /**
     * Regenerates the user's TOTP recovery codes, invalidating the old set. Requires
     * a valid TOTP (or recovery) code, and that TOTP is enrolled. Returns the fresh
     * codes once — they are never retrievable again.
     */
    fun regenerateRecoveryCodes(userId: UUID, code: String): List<String> {
        if (code.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        return transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)
            if (!user[Users.totpEnabled]) throw BadRequestException(ErrorCodes.TOTP_NOT_CONFIGURED)
            val secret = user[Users.totpSecretEncrypted] ?: throw BadRequestException(ErrorCodes.TOTP_NOT_CONFIGURED)
            val iv = user[Users.totpSecretIv] ?: throw BadRequestException(ErrorCodes.TOTP_NOT_CONFIGURED)
            val decryptedSecret = TotpUtil.decryptSecret(secret, iv, hmacKey)
            if (!TotpUtil.verifyCode(decryptedSecret, code) && !tryRecoveryCode(userId, code)) {
                throw BadRequestException(ErrorCodes.INVALID_TOTP_CODE)
            }
            // Replaces the whole set (delete + insert), returning the plaintext once.
            generateRecoveryCodes(userId)
        }
    }

    /**
     * Generates 8 recovery codes for a user. Each code is 8 chars alphanumeric.
     * Stores bcrypt hashes; returns plaintext codes (shown once).
     */
    internal fun generateRecoveryCodes(userId: UUID): List<String> {
        val codes = mutableListOf<String>()
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no I/O/0/1
        val now = Instant.now()

        // Delete any existing codes
        TotpRecoveryCodes.deleteWhere { TotpRecoveryCodes.userId eq userId }

        repeat(8) {
            val code = (1..8).map { chars[secureRandom.nextInt(chars.length)] }.joinToString("")
            val hash = BCrypt.withDefaults().hashToString(10, code.toCharArray())

            TotpRecoveryCodes.insert {
                it[id] = UUID.randomUUID()
                it[TotpRecoveryCodes.userId] = userId
                it[codeHash] = hash
                it[createdAt] = now
            }
            codes.add(code)
        }

        return codes
    }

    /**
     * Tries to use a recovery code for TOTP verification.
     * Returns true if a valid unused code was found and consumed.
     */
    internal fun tryRecoveryCode(userId: UUID, code: String): Boolean {
        val candidates = TotpRecoveryCodes.selectAll()
            .where {
                (TotpRecoveryCodes.userId eq userId) and
                (TotpRecoveryCodes.used eq false)
            }
            .toList()

        val matched = candidates.firstOrNull { row ->
            BCrypt.verifyer().verify(code.toCharArray(), row[TotpRecoveryCodes.codeHash]).verified
        } ?: return false

        TotpRecoveryCodes.update({ TotpRecoveryCodes.id eq matched[TotpRecoveryCodes.id] }) {
            it[used] = true
            it[usedAt] = Instant.now()
        }

        return true
    }

    // ── Internals ──

    /**
     * Resolves which org the user's session will target — same logic as createSession
     * but without actually creating the session. Used at login to determine TOTP enforcement.
     */
    private fun resolveTargetOrgId(user: ResultRow): UUID? {
        val selectedOrg = user[Users.selectedOrgId]
        return if (selectedOrg != null) {
            val valid = OrgUsers.selectAll()
                .where {
                    (OrgUsers.userId eq user[Users.id]) and
                    (OrgUsers.organizationId eq selectedOrg) and
                    (OrgUsers.status eq "active") and
                    (OrgUsers.deleted eq false)
                }
                .firstOrNull()
            if (valid != null) selectedOrg else {
                OrgUsers.selectAll()
                    .where { (OrgUsers.userId eq user[Users.id]) and (OrgUsers.status eq "active") and (OrgUsers.deleted eq false) }
                    .firstOrNull()?.get(OrgUsers.organizationId)
            }
        } else {
            OrgUsers.selectAll()
                .where { (OrgUsers.userId eq user[Users.id]) and (OrgUsers.status eq "active") and (OrgUsers.deleted eq false) }
                .firstOrNull()?.get(OrgUsers.organizationId)
        }
    }

    /**
     * Checks if TOTP is enforced for a user in a specific org.
     * Reads from permission_cache if available, otherwise checks org + groups directly.
     */
    private fun isTotpEnforcedForOrg(userId: UUID, orgId: UUID): Boolean {
        val membership = OrgUsers.selectAll()
            .where {
                (OrgUsers.userId eq userId) and
                (OrgUsers.organizationId eq orgId) and
                (OrgUsers.status eq "active") and
                (OrgUsers.deleted eq false)
            }
            .firstOrNull() ?: return false

        val cache = membership[OrgUsers.permissionCache]
        if (cache != null) {
            return CachedPermissions.fromJsonObject(cache).totpRequired
        }

        // Fallback: check org and groups directly
        val org = Organizations.selectAll()
            .where { Organizations.id eq orgId }
            .firstOrNull()
        if (org != null && org[Organizations.totpRequired]) return true

        val groupIds = OrgUserGroups.selectAll()
            .where { OrgUserGroups.orgUserId eq membership[OrgUsers.id] }
            .map { it[OrgUserGroups.orgGroupId] }

        for (groupId in groupIds) {
            val group = OrgGroups.selectAll()
                .where { OrgGroups.id eq groupId }
                .firstOrNull()
            if (group != null && group[OrgGroups.totpRequired]) return true
        }

        return false
    }

    private fun verifyCredentials(email: String, password: String): ResultRow {
        val user = Users.selectAll()
            .where { (Users.email eq email) and (Users.deleted eq false) }
            .firstOrNull()
            ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

        if (!user[Users.isActive]) throw UnauthorizedException(ErrorCodes.ACCOUNT_DEACTIVATED)

        val hashResult = BCrypt.verifyer().verify(
            password.toCharArray(),
            user[Users.passwordHash],
        )
        if (!hashResult.verified) throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

        return user
    }

    /**
     * Opens a tokenless `pending_totp` session after a successful password step.
     * Its id is handed to the client as the TOTP challenge; [verifyTotp] activates
     * it. Must run inside a transaction (it does — called from [login]).
     */
    private fun createPendingSession(
        userId: UUID,
        orgId: UUID?,
        ipAddress: String?,
        userAgent: String?,
    ): UUID {
        val sessionId = UUID.randomUUID()
        val now = Instant.now()
        Sessions.insert {
            it[id] = sessionId
            it[Sessions.userId] = userId
            it[organizationId] = orgId
            it[status] = SessionStatus.PENDING_TOTP
            it[Sessions.ipAddress] = ipAddress
            it[Sessions.userAgent] = userAgent
            it[expiresAt] = now.plusSeconds(CHALLENGE_TTL_SECONDS)
            it[lastActiveAt] = now
            it[createdAt] = now
            // sessionTokenHash stays null until activation; totpAttemptCount defaults to 0.
        }
        return sessionId
    }

    private fun createSession(
        user: ResultRow,
        sessionTtlMinutes: Long,
        ipAddress: String?,
        userAgent: String?,
    ): LoginResponse {
        val sessionId = UUID.randomUUID()
        val token = generateToken()
        val now = Instant.now()
        val expiresAt = now.plusSeconds(sessionTtlMinutes * 60)

        val selectedOrg = user[Users.selectedOrgId]
        val orgId = if (selectedOrg != null) {
            val valid = OrgUsers.selectAll()
                .where {
                    (OrgUsers.userId eq user[Users.id]) and
                    (OrgUsers.organizationId eq selectedOrg) and
                    (OrgUsers.status eq "active") and
                    (OrgUsers.deleted eq false)
                }
                .firstOrNull()
            if (valid != null) selectedOrg else {
                OrgUsers.selectAll()
                    .where { (OrgUsers.userId eq user[Users.id]) and (OrgUsers.status eq "active") and (OrgUsers.deleted eq false) }
                    .firstOrNull()?.get(OrgUsers.organizationId)
            }
        } else {
            OrgUsers.selectAll()
                .where { (OrgUsers.userId eq user[Users.id]) and (OrgUsers.status eq "active") and (OrgUsers.deleted eq false) }
                .firstOrNull()?.get(OrgUsers.organizationId)
        }

        Sessions.insert {
            it[id] = sessionId
            it[Sessions.userId] = user[Users.id]
            it[organizationId] = orgId
            it[sessionTokenHash] = TokenHasher.sha256Hex(token)
            it[Sessions.ipAddress] = ipAddress
            it[Sessions.userAgent] = userAgent
            it[Sessions.expiresAt] = expiresAt
            it[lastActiveAt] = now
            it[createdAt] = now
        }

        return LoginResponse(
            token = token,
            expiresAt = expiresAt.toString(),
            user = userSummaryFrom(user),
        )
    }

    private fun createChallenge(userId: UUID): String {
        val expiry = Instant.now().epochSecond + CHALLENGE_TTL_SECONDS
        val payload = "$userId:$expiry"
        val sig = hmacSign(payload)
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$payload:$sig".toByteArray())
    }

    private fun validateChallenge(challenge: String): UUID {
        val decoded = try {
            String(Base64.getUrlDecoder().decode(challenge))
        } catch (e: Exception) {
            throw UnauthorizedException()
        }

        val parts = decoded.split(":")
        if (parts.size != 3) throw UnauthorizedException()

        val userId = try { UUID.fromString(parts[0]) } catch (e: Exception) {
            throw UnauthorizedException()
        }
        val expiry = parts[1].toLongOrNull()
            ?: throw UnauthorizedException()
        val sig = parts[2]

        if (Instant.now().epochSecond > expiry) {
            throw UnauthorizedException(ErrorCodes.SESSION_EXPIRED)
        }

        val expectedSig = hmacSign("${parts[0]}:${parts[1]}")
        if (sig != expectedSig) {
            throw UnauthorizedException()
        }

        return userId
    }

    private fun hmacSign(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(data.toByteArray()))
    }

    internal fun userSummaryFrom(user: ResultRow) = UserSummary(
        id = user[Users.id].toString(),
        email = user[Users.email],
        displayName = user[Users.displayName],
        totpEnabled = user[Users.totpEnabled],
        selectedOrgId = user[Users.selectedOrgId]?.toString(),
    )

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun encodeBase32(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(alphabet[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            sb.append(alphabet[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return sb.toString()
    }

    /**
     * Deletes the user's account.
     *
     * Requires password confirmation. The user must not be the owner of any
     * organization — ownership must be transferred first.
     * Soft-deletes the user, revokes all sessions, and removes org memberships.
     */
    fun deleteAccount(userId: UUID, password: String) {
        transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

            // Verify password
            val result = BCrypt.verifyer().verify(password.toCharArray(), user[Users.passwordHash])
            if (!result.verified) throw UnauthorizedException(ErrorCodes.INCORRECT_PASSWORD)

            // Check user doesn't own any active orgs
            val ownedOrgs = Organizations.selectAll()
                .where { (Organizations.ownerId eq userId) and (Organizations.deleted eq false) }
                .toList()
            if (ownedOrgs.isNotEmpty()) {
                throw BadRequestException(ErrorCodes.FORBIDDEN)
            }

            val now = Instant.now()

            // Revoke all sessions
            Sessions.update({ Sessions.userId eq userId }) {
                it[revoked] = true
            }

            // Remove org memberships
            OrgUsers.update({ (OrgUsers.userId eq userId) and (OrgUsers.deleted eq false) }) {
                it[deleted] = true
                it[deletedAt] = now
            }

            // Soft-delete user
            Users.update({ Users.id eq userId }) {
                it[deleted] = true
                it[deletedAt] = now
                it[isActive] = false
                it[purgeAfter] = now.plusSeconds(30L * 24 * 3600) // 30 days retention
            }
        }
    }
}
