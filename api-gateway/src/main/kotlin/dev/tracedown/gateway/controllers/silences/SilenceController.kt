package dev.tracedown.gateway.controllers.silences

import dev.tracedown.common.models.NotificationSilences
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.gateway.data.silences.CreateSilenceRequest
import dev.tracedown.gateway.data.silences.SilenceSummary
import dev.tracedown.gateway.data.silences.UpdateSilenceRequest
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.NotFoundException
import org.dmfs.rfc5545.recur.RecurrenceRule
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

object SilenceController {

    // "quiet-hours" is a carrier: it matches no dispatch channel, so the row
    // never silences anything — it only holds the user's quietHours window
    // (the dispatcher reads quietHours from any of the user's rows).
    private val validChannels = setOf("email", "webhook", "all", "quiet-hours")

    /** Creates a notification silence for the current user. */
    fun create(orgId: UUID, userId: UUID, request: CreateSilenceRequest): SilenceSummary {
        validateRequest(request)

        val wsId = request.workspaceId?.let { parseUuid(it, "workspace ID") }
        val projId = request.projectId?.let { parseUuid(it, "project ID") }
        val svcId = request.serviceId?.let { parseUuid(it, "service ID") }

        validateQuietHours(request.quietHours)

        return transaction {
            val orgUserId = resolveOrgUserId(orgId, userId)

            wsId?.let { requireWorkspaceExists(it, orgId) }
            projId?.let { requireProjectExists(it) }
            svcId?.let { requireServiceExists(it) }

            val id = UUID.randomUUID()

            NotificationSilences.insert {
                it[NotificationSilences.id] = id
                it[NotificationSilences.orgUserId] = orgUserId
                it[workspaceId] = wsId
                it[projectId] = projId
                it[serviceId] = svcId
                it[channel] = request.channel
                it[config] = request.config?.let(::parseJsonField)
                it[quietHours] = request.quietHours
            }

            silenceSummary(id)
        }
    }

    /** Lists silences for the current user in this org. */
    fun list(orgId: UUID, userId: UUID, pfs: PfsParams): Page<SilenceSummary> {
        return transaction {
            val orgUserId = resolveOrgUserId(orgId, userId)

            val query = NotificationSilences.selectAll()
                .where { NotificationSilences.orgUserId eq orgUserId }

            val (pagedQuery, total) = query.applyPfs(pfs)
            val items = pagedQuery.map { silenceSummaryFromRow(it) }

            Page(items = items, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    /** Returns a single silence. Must belong to the current user. */
    fun get(orgId: UUID, userId: UUID, silenceId: UUID): SilenceSummary {
        return transaction {
            val orgUserId = resolveOrgUserId(orgId, userId)
            silenceSummary(silenceId, orgUserId)
        }
    }

    /** Updates a silence's channel, config, or quiet hours. */
    fun update(orgId: UUID, userId: UUID, silenceId: UUID, request: UpdateSilenceRequest): SilenceSummary {
        if (request.channel != null && request.channel !in validChannels) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
        validateQuietHours(request.quietHours)

        return transaction {
            val orgUserId = resolveOrgUserId(orgId, userId)
            requireSilenceOwnership(silenceId, orgUserId)

            NotificationSilences.update({ NotificationSilences.id eq silenceId }) {
                request.channel?.let { v -> it[channel] = v }
                request.config?.let { v -> it[config] = parseJsonField(v) }
                request.quietHours?.let { v -> it[quietHours] = v }
            }

            silenceSummary(silenceId)
        }
    }

    /** Deletes a silence. Must belong to the current user. */
    fun delete(orgId: UUID, userId: UUID, silenceId: UUID) {
        transaction {
            val orgUserId = resolveOrgUserId(orgId, userId)
            requireSilenceOwnership(silenceId, orgUserId)

            NotificationSilences.deleteWhere { NotificationSilences.id eq silenceId }
        }
    }

    // ── Validation ──

    private fun validateRequest(request: CreateSilenceRequest) {
        if (request.channel !in validChannels) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }

        val scopeCount = listOfNotNull(request.workspaceId, request.projectId, request.serviceId).size
        if (scopeCount > 1) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
    }

    /**
     * Quiet hours are a recurrence spec `RRULE[/durationMinutes[/timezone]]`,
     * the same format as the service maintenance window. Reject malformed specs
     * up front — the dispatcher silently ignores them, which would read as
     * "quiet hours set" while never actually suppressing. Timezone names contain
     * slashes, so split as rrule / duration / rest.
     */
    private fun validateQuietHours(quietHours: String?) {
        if (quietHours.isNullOrBlank()) return
        val parts = quietHours.trim().split('/')
        if (parts.size > 1) {
            val duration = parts[1].toLongOrNull()
            if (duration == null || duration !in 1..1440) {
                throw BadRequestException(ErrorCodes.FIELD_INVALID)
            }
        }
        if (parts.size > 2) {
            val zone = parts.drop(2).joinToString("/")
            if (zone !in java.time.ZoneId.getAvailableZoneIds()) {
                throw BadRequestException(ErrorCodes.FIELD_INVALID)
            }
        }
        try {
            RecurrenceRule(parts[0])
        } catch (_: Exception) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
    }

    private fun parseUuid(value: String, label: String): UUID {
        return try { UUID.fromString(value) } catch (_: Exception) {
            throw BadRequestException(ErrorCodes.INVALID_UUID)
        }
    }

    // ── Internals ──

    private fun resolveOrgUserId(orgId: UUID, userId: UUID): UUID {
        val row = OrgUsers.selectAll()
            .where {
                (OrgUsers.organizationId eq orgId) and
                (OrgUsers.userId eq userId) and
                (OrgUsers.status eq "active") and
                (OrgUsers.deleted eq false)
            }
            .firstOrNull() ?: throw ForbiddenException(ErrorCodes.NOT_ORG_MEMBER)
        return row[OrgUsers.id]
    }

    private fun requireSilenceOwnership(silenceId: UUID, orgUserId: UUID) {
        val exists = NotificationSilences.selectAll()
            .where {
                (NotificationSilences.id eq silenceId) and
                (NotificationSilences.orgUserId eq orgUserId)
            }
            .any()
        if (!exists) throw NotFoundException()
    }

    private fun requireWorkspaceExists(workspaceId: UUID, orgId: UUID) {
        val exists = Workspaces.selectAll()
            .where { (Workspaces.id eq workspaceId) and (Workspaces.organizationId eq orgId) and (Workspaces.deleted eq false) }
            .any()
        if (!exists) throw NotFoundException()
    }

    private fun requireProjectExists(projectId: UUID) {
        val exists = Projects.selectAll()
            .where { (Projects.id eq projectId) and (Projects.deleted eq false) }
            .any()
        if (!exists) throw NotFoundException()
    }

    private fun requireServiceExists(serviceId: UUID) {
        val exists = Services.selectAll()
            .where { (Services.id eq serviceId) and (Services.deleted eq false) }
            .any()
        if (!exists) throw NotFoundException()
    }

    private fun silenceSummary(id: UUID, orgUserId: UUID? = null): SilenceSummary {
        val query = if (orgUserId != null) {
            NotificationSilences.selectAll().where {
                (NotificationSilences.id eq id) and
                (NotificationSilences.orgUserId eq orgUserId)
            }
        } else {
            NotificationSilences.selectAll().where {
                NotificationSilences.id eq id
            }
        }
        val row = query.firstOrNull() ?: throw NotFoundException()
        return silenceSummaryFromRow(row)
    }

    private fun silenceSummaryFromRow(row: org.jetbrains.exposed.sql.ResultRow) = SilenceSummary(
        id = row[NotificationSilences.id].toString(),
        orgUserId = row[NotificationSilences.orgUserId].toString(),
        workspaceId = row[NotificationSilences.workspaceId]?.toString(),
        projectId = row[NotificationSilences.projectId]?.toString(),
        serviceId = row[NotificationSilences.serviceId]?.toString(),
        channel = row[NotificationSilences.channel],
        config = row[NotificationSilences.config]?.toString(),
        quietHours = row[NotificationSilences.quietHours],
        resourceName = resolveResourceName(row),
    )

    /** Display name of the most specific silenced scope, for list UIs. */
    private fun resolveResourceName(row: org.jetbrains.exposed.sql.ResultRow): String? {
        row[NotificationSilences.serviceId]?.let { id ->
            return Services.selectAll().where { Services.id eq id }.firstOrNull()?.get(Services.name)
        }
        row[NotificationSilences.projectId]?.let { id ->
            return Projects.selectAll().where { Projects.id eq id }.firstOrNull()?.get(Projects.name)
        }
        row[NotificationSilences.workspaceId]?.let { id ->
            return Workspaces.selectAll().where { Workspaces.id eq id }.firstOrNull()?.get(Workspaces.name)
        }
        return null
    }
    /** JSONB columns only accept valid JSON — reject anything else up front. */
    private fun parseJsonField(value: String): kotlinx.serialization.json.JsonElement {
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(value)
        } catch (_: Exception) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
    }

}
