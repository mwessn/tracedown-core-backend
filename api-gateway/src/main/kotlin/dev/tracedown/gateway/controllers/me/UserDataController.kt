package dev.tracedown.gateway.controllers.me

import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.ApiKeys
import dev.tracedown.common.models.NotificationSilences
import dev.tracedown.common.models.OrgAuditLog
import dev.tracedown.common.models.OrgGroups
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.ResourcePermissions
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.gateway.controllers.auth.AuthController
import dev.tracedown.gateway.controllers.auth.SessionController
import dev.tracedown.gateway.data.auth.UserSummary
import dev.tracedown.gateway.data.me.ChangeEmailRequest
import dev.tracedown.gateway.data.me.ExportApiKey
import dev.tracedown.gateway.data.me.ExportAuditEntry
import dev.tracedown.gateway.data.me.ExportNotificationSilence
import dev.tracedown.gateway.data.me.ExportOrgMembership
import dev.tracedown.gateway.data.me.ExportProfile
import dev.tracedown.gateway.data.me.ExportResourceGrant
import dev.tracedown.gateway.data.me.ExportSentInvite
import dev.tracedown.gateway.data.me.ExportSession
import dev.tracedown.gateway.data.me.ExportVariable
import dev.tracedown.gateway.data.me.UserDataExport
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.UnauthorizedException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/**
 * Account-scoped data operations for the calling user: a full personal data
 * export and self-service email change.
 */
object UserDataController {

    /**
     * Assembles a single JSON document of everything stored about [userId].
     * Secrets are excluded by construction: every section is an explicit
     * field allowlist (no password hash, TOTP secret, session tokens, API key
     * material, invite tokens, or variable values).
     */
    fun export(userId: UUID, currentSessionId: UUID): UserDataExport = transaction {
        val user = Users.selectAll()
            .where { (Users.id eq userId) and (Users.deleted eq false) }
            .firstOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

        UserDataExport(
            generatedAt = Instant.now().toString(),
            profile = ExportProfile(
                id = user[Users.id].toString(),
                email = user[Users.email],
                displayName = user[Users.displayName],
                totpEnabled = user[Users.totpEnabled],
                totpEnrolledAt = user[Users.totpEnrolledAt]?.toString(),
                selectedOrgId = user[Users.selectedOrgId]?.toString(),
                isActive = user[Users.isActive],
                createdAt = user[Users.createdAt].toString(),
            ),
            sessions = exportSessions(userId, currentSessionId),
            orgMemberships = exportMemberships(userId),
            resourceGrants = exportResourceGrants(userId),
            auditLog = exportAuditLog(userId),
            apiKeys = exportApiKeys(userId),
            notificationSilences = exportSilences(userId),
            variables = exportVariables(userId),
            sentInvites = exportSentInvites(userId),
        )
    }

    /**
     * Changes the account email. Re-verifies identity (password, plus a TOTP
     * code when enrolled), enforces global email uniqueness (case-insensitive,
     * matching account creation), audits the change, and — as a
     * credential-adjacent event — signs out every other session, keeping the
     * current one. Returns the updated profile.
     */
    fun changeEmail(
        userId: UUID,
        sessionId: UUID,
        orgId: UUID?,
        request: ChangeEmailRequest,
    ): UserSummary {
        AuthController.verifyIdentity(userId, request.currentPassword, request.code)

        val newEmail = request.newEmail.trim()
        val updated = transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)
            val oldEmail = user[Users.email]

            val taken = Users.selectAll()
                .where {
                    (Users.email.lowerCase() eq newEmail.lowercase()) and
                    (Users.deleted eq false) and
                    (Users.id neq userId)
                }
                .limit(1)
                .any()
            if (taken) throw BadRequestException(ErrorCodes.EMAIL_TAKEN)

            Users.update({ Users.id eq userId }) { it[email] = newEmail }

            // Record the rectification (GDPR Art. 16). The audit log is org-scoped
            // (OrgAuditLog.organizationId is NOT NULL) and there is no user-scoped
            // audit table, so an org context is required to have a home. When a
            // session has a selected org we log there; when it does not, we log the
            // change into EVERY org the user is an active member of, so an account
            // email change is never invisible in the audit trail just because no org
            // happened to be selected. An account with no membership at all has no
            // audit home by construction — accepted, and the deliberate no-op below.
            val emailDiff = buildJsonObject {
                putJsonObject("email") {
                    put("old", oldEmail)
                    put("new", newEmail)
                }
            }.toString()
            val auditOrgIds = if (orgId != null) {
                listOf(orgId)
            } else {
                OrgUsers.selectAll()
                    .where {
                        (OrgUsers.userId eq userId) and
                        (OrgUsers.status eq "active") and
                        (OrgUsers.deleted eq false)
                    }
                    .map { it[OrgUsers.organizationId] }
                    .distinct()
            }
            auditOrgIds.forEach { auditOrgId ->
                AuditService.log(
                    auditOrgId, userId, "update.email", "user", userId.toString(),
                    entityDisplayName = user[Users.displayName],
                    diff = emailDiff,
                )
            }

            Users.selectAll().where { Users.id eq userId }.first()
        }

        SessionController.revokeAllOtherSessions(userId, sessionId, orgId)
        return AuthController.userSummaryFrom(updated)
    }

    // ── Export sections ──

    private fun exportSessions(userId: UUID, currentSessionId: UUID): List<ExportSession> =
        Sessions.selectAll()
            .where { Sessions.userId eq userId }
            .orderBy(Sessions.createdAt, SortOrder.DESC)
            .map { row ->
                ExportSession(
                    ipAddress = row[Sessions.ipAddress],
                    userAgent = row[Sessions.userAgent],
                    createdAt = row[Sessions.createdAt].toString(),
                    lastActiveAt = row[Sessions.lastActiveAt].toString(),
                    expiresAt = row[Sessions.expiresAt].toString(),
                    revoked = row[Sessions.revoked],
                    current = row[Sessions.id] == currentSessionId,
                )
            }

    private fun exportMemberships(userId: UUID): List<ExportOrgMembership> =
        (OrgUsers innerJoin Organizations).selectAll()
            .where {
                (OrgUsers.userId eq userId) and
                (OrgUsers.deleted eq false) and
                (Organizations.deleted eq false)
            }
            .map { row ->
                val groups = (OrgUserGroups innerJoin OrgGroups)
                    .select(OrgGroups.name)
                    .where { OrgUserGroups.orgUserId eq row[OrgUsers.id] }
                    .map { it[OrgGroups.name] }
                ExportOrgMembership(
                    organizationId = row[Organizations.id].toString(),
                    organizationName = row[Organizations.name],
                    status = row[OrgUsers.status],
                    joinedAt = row[OrgUsers.joinedAt]?.toString(),
                    isOwner = row[Organizations.ownerId] == userId,
                    groups = groups,
                )
            }

    private fun exportResourceGrants(userId: UUID): List<ExportResourceGrant> =
        ResourcePermissions.selectAll()
            .where {
                (ResourcePermissions.principalType eq "user") and
                (ResourcePermissions.principalId eq userId)
            }
            .map { row ->
                ExportResourceGrant(
                    organizationId = row[ResourcePermissions.orgId].toString(),
                    resourceType = row[ResourcePermissions.resourceType],
                    resourceId = row[ResourcePermissions.resourceId].toString(),
                    permissions = row[ResourcePermissions.permissions],
                )
            }

    private fun exportAuditLog(userId: UUID): List<ExportAuditEntry> =
        OrgAuditLog.selectAll()
            .where { OrgAuditLog.userId eq userId }
            .orderBy(OrgAuditLog.createdAt, SortOrder.DESC)
            .map { row ->
                ExportAuditEntry(
                    organizationId = row[OrgAuditLog.organizationId].toString(),
                    action = row[OrgAuditLog.action],
                    entityType = row[OrgAuditLog.entityType],
                    entityId = row[OrgAuditLog.entityId],
                    entityDisplayName = row[OrgAuditLog.entityDisplayName],
                    diff = row[OrgAuditLog.diff],
                    comment = row[OrgAuditLog.comment],
                    createdAt = row[OrgAuditLog.createdAt].toString(),
                )
            }

    private fun exportApiKeys(userId: UUID): List<ExportApiKey> =
        ApiKeys.selectAll()
            .where { (ApiKeys.createdBy eq userId) and (ApiKeys.deleted eq false) }
            .map { row ->
                ExportApiKey(
                    organizationId = row[ApiKeys.organizationId].toString(),
                    name = row[ApiKeys.name],
                    lastUsedAt = row[ApiKeys.lastUsedAt]?.toString(),
                    expiresAt = row[ApiKeys.expiresAt]?.toString(),
                    revoked = row[ApiKeys.revoked],
                    createdAt = row[ApiKeys.createdAt].toString(),
                )
            }

    private fun exportSilences(userId: UUID): List<ExportNotificationSilence> =
        (NotificationSilences innerJoin OrgUsers).selectAll()
            .where { OrgUsers.userId eq userId }
            .map { row ->
                ExportNotificationSilence(
                    organizationId = row[OrgUsers.organizationId].toString(),
                    channel = row[NotificationSilences.channel],
                    workspaceId = row[NotificationSilences.workspaceId]?.toString(),
                    projectId = row[NotificationSilences.projectId]?.toString(),
                    serviceId = row[NotificationSilences.serviceId]?.toString(),
                    quietHours = row[NotificationSilences.quietHours],
                    config = row[NotificationSilences.config],
                )
            }

    /**
     * Variables the user created, across all four scopes — metadata only.
     * Values are write-only through the API and stay out of the export.
     */
    private fun exportVariables(userId: UUID): List<ExportVariable> {
        fun collect(
            table: Table,
            createdBy: Column<UUID?>,
            deleted: Column<Boolean>,
            scope: String,
            scopeId: Column<UUID>,
            key: Column<String>,
            secret: Column<Boolean>,
            createdAt: Column<Instant>,
            updatedAt: Column<Instant>,
        ): List<ExportVariable> = table.selectAll()
            .where { (createdBy eq userId) and (deleted eq false) }
            .map { row: ResultRow ->
                ExportVariable(
                    scope = scope,
                    scopeId = row[scopeId].toString(),
                    key = row[key],
                    secret = row[secret],
                    createdAt = row[createdAt].toString(),
                    updatedAt = row[updatedAt].toString(),
                )
            }

        return collect(
            OrgVariables, OrgVariables.createdBy, OrgVariables.deleted, "org",
            OrgVariables.organizationId, OrgVariables.key, OrgVariables.secret,
            OrgVariables.createdAt, OrgVariables.updatedAt,
        ) + collect(
            WorkspaceVariables, WorkspaceVariables.createdBy, WorkspaceVariables.deleted, "workspace",
            WorkspaceVariables.workspaceId, WorkspaceVariables.key, WorkspaceVariables.secret,
            WorkspaceVariables.createdAt, WorkspaceVariables.updatedAt,
        ) + collect(
            ProjectVariables, ProjectVariables.createdBy, ProjectVariables.deleted, "project",
            ProjectVariables.projectId, ProjectVariables.key, ProjectVariables.secret,
            ProjectVariables.createdAt, ProjectVariables.updatedAt,
        ) + collect(
            ServiceVariables, ServiceVariables.createdBy, ServiceVariables.deleted, "service",
            ServiceVariables.serviceId, ServiceVariables.key, ServiceVariables.secret,
            ServiceVariables.createdAt, ServiceVariables.updatedAt,
        )
    }

    /** Pending invites the user sent. The invite token itself is never exported. */
    private fun exportSentInvites(userId: UUID): List<ExportSentInvite> =
        OrgUsers.join(Users, JoinType.INNER, onColumn = OrgUsers.userId, otherColumn = Users.id)
            .selectAll()
            .where {
                (OrgUsers.invitedBy eq userId) and
                (OrgUsers.status eq "invited") and
                (OrgUsers.deleted eq false)
            }
            .map { row ->
                ExportSentInvite(
                    organizationId = row[OrgUsers.organizationId].toString(),
                    email = row[Users.email],
                    invitedAt = row[OrgUsers.invitedAt]?.toString(),
                    inviteExpiresAt = row[OrgUsers.inviteExpiresAt]?.toString(),
                )
            }
}
