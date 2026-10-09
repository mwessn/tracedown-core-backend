package dev.tracedown.gateway.controllers.events

import dev.tracedown.common.alerts.SystemAlertService
import dev.tracedown.common.auth.ApiKeyAuthenticator
import dev.tracedown.common.auth.ApiKeyResult
import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.auth.canRead
import dev.tracedown.common.auth.canWrite
import dev.tracedown.common.auth.resolveCachedPermissions
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.OutboxRetention
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.gateway.context.apiKeyRefusal
import dev.tracedown.gateway.data.events.EventPage
import dev.tracedown.gateway.data.events.EventResource
import dev.tracedown.gateway.data.events.FeedEvent
import dev.tracedown.gateway.util.ApiException
import dev.tracedown.gateway.util.UnauthorizedException
import dev.tracedown.gateway.util.fieldError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.time.Duration
import java.util.Base64
import java.util.UUID

/** The feed's event types — the public names, not the outbox's. */
object EventTypes {
    const val RESULT_RECORDED = "result.recorded"
    const val SERVICE_STATUS_CHANGED = "service.status_changed"
    const val ALERT_RAISED = "alert.raised"

    /** `<resource>.created|updated|deleted` for these resources. */
    val RESOURCES = listOf("workspace", "project", "service", "variable")
    private val CHANGES = listOf("created", "updated", "deleted")

    val ALL: List<String> =
        listOf(RESULT_RECORDED, SERVICE_STATUS_CHANGED) +
            RESOURCES.flatMap { r -> CHANGES.map { "$r.$it" } } +
            ALERT_RAISED
}

/**
 * The event feed: the outbox, read by position, as the events a caller may
 * see.
 *
 * **Position.** Every outbox row has a `seq`; a cursor is a `seq` the reader
 * has read up to, written opaquely ([encodeCursor]). The same position always
 * gives the same cursor, and reading from it again gives the same events
 * (less any the caller may no longer see). A read moves the cursor over every
 * row it looked at — another organization's, a type the caller did not ask
 * for, one they may not see — so an empty page still moves it.
 *
 * **A hole is not skipped while it may still fill.** `seq` is taken at INSERT
 * and seen at COMMIT, so a row can be readable while a lower one, in a
 * transaction still open, is not; moving past that hole would lose the row
 * for good. A read stops before a hole whose next row was written less than
 * [GAP_GRACE] ago and looks again; a hole older than that was a rollback (or
 * the purge), and is passed.
 *
 * **Retention.** The outbox is trimmed; [OutboxRetention] says how far. A
 * cursor below that may have missed rows and is refused — 410
 * `cursor_expired` with the oldest cursor that has missed nothing.
 *
 * **Who sees what** is decided on every look, from the user's permissions as
 * they are then — the checks the dashboard's reads of the same resource make
 * ([visible]) — and the key is looked at again each time too, so a grant
 * withdrawn, a member removed or a key revoked stops delivery from the next
 * look, even within one long-poll.
 *
 * **Waiting** holds nothing: each look is a short transaction, and between
 * looks the read suspends on a signal from [EventWakeups], or for
 * [RECHECK] when none comes.
 */
object EventFeedController {

    const val MAX_WAIT_SECONDS = 30
    const val MAX_LIMIT = 100

    /** Outbox rows looked at per look, whoever they belong to. */
    private const val SCAN_ROWS = 500

    /** How long a hole in `seq` is waited for before it is taken to be a rollback. */
    val GAP_GRACE: Duration = Duration.ofSeconds(10)

    /** How long a waiting read goes without looking, when nothing wakes it. */
    private val RECHECK: Duration = Duration.ofSeconds(5)

    /** How soon to look again while held at a hole. */
    private val HOLE_RECHECK: Duration = Duration.ofSeconds(1)

    /** A pause after a wake-up, so a burst of writes is read in one look. */
    private val SETTLE: Duration = Duration.ofMillis(200)

    private const val CURSOR_PREFIX = "ev1:"

    /** The cursor for position [seq]. */
    fun encodeCursor(seq: Long): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$CURSOR_PREFIX$seq".toByteArray())

    /** The position a cursor names, or null when it is not one. */
    fun decodeCursor(cursor: String): Long? = runCatching {
        val text = String(Base64.getUrlDecoder().decode(cursor))
        text.removePrefix(CURSOR_PREFIX).takeIf { text.startsWith(CURSOR_PREFIX) }?.toLong()?.takeIf { it >= 0 }
    }.getOrNull()

    /**
     * Reads the events after [after] that [userId] may see in [orgId] through
     * key [keyId]: those already there, or — waiting up to [waitSeconds] —
     * the first that arrive. Without [after], reads from now on.
     */
    suspend fun read(
        orgId: UUID,
        userId: UUID,
        keyId: UUID,
        after: String?,
        waitSeconds: Int,
        types: Set<String>?,
        limit: Int,
    ): EventPage {
        var position = if (after == null) {
            onIo { headSeq() }
        } else {
            decodeCursor(after) ?: throw fieldError("after")
        }
        val purgedThrough = onIo { purgedThrough() }
        if (position < purgedThrough) {
            throw ApiException(
                HttpStatusCode.Gone, ErrorCodes.CURSOR_EXPIRED,
                details = buildJsonObject { put("oldest", encodeCursor(purgedThrough)) },
            )
        }

        val deadline = System.nanoTime() + Duration.ofSeconds(waitSeconds.toLong()).toNanos()
        while (true) {
            // Registered before looking, so a nudge during the look is kept.
            val signal = EventWakeups.register(orgId)
            try {
                val look = onIo { look(orgId, userId, keyId, position, types, limit) }
                position = look.position
                if (look.items.isNotEmpty()) return EventPage(look.items, encodeCursor(position))
                val remaining = Duration.ofNanos(deadline - System.nanoTime())
                if (remaining.isNegative || remaining.isZero) return EventPage(emptyList(), encodeCursor(position))
                val pause = minOf(remaining, if (look.held) HOLE_RECHECK else RECHECK)
                val woken = withTimeoutOrNull(pause.toMillis()) { signal.await() } != null
                if (woken) delay(minOf(SETTLE, Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(0))).toMillis())
            } finally {
                EventWakeups.release(orgId, signal)
            }
        }
    }

    /** Blocking database work, off the caller's thread, in a transaction of its own. */
    private suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) { transaction { block() } }

    private fun headSeq(): Long = queryLong("SELECT COALESCE(MAX(seq), 0) FROM outbox")

    private fun purgedThrough(): Long =
        OutboxRetention.selectAll().where { OutboxRetention.id eq 1 }.firstOrNull()?.get(OutboxRetention.purgedThrough) ?: 0L

    private fun queryLong(sql: String): Long {
        val connection = TransactionManager.current().connection.connection as Connection
        return connection.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    /** One look's outcome: what it found, how far it read, and whether a hole stopped it. */
    internal data class Look(val items: List<FeedEvent>, val position: Long, val held: Boolean)

    /** One outbox row as a look reads it. [payload] is null for another organization's row. */
    private data class Row(
        val seq: Long,
        val id: UUID,
        val eventType: String,
        val aggregateId: UUID,
        val createdAt: java.time.Instant,
        val payload: JsonObject?,
    )

    /** One look, in the caller's transaction. */
    internal fun look(orgId: UUID, userId: UUID, keyId: UUID, after: Long, types: Set<String>?, limit: Int): Look {
        // The key and its user as they are now, not as they were when the
        // request came in: a long-poll outlives that moment.
        val key = ApiKeyAuthenticator.recheck(keyId)
        if (key is ApiKeyResult.Invalid) throw UnauthorizedException(apiKeyRefusal(key.reason))
        val cached = resolveCachedPermissions(orgId, userId)
            ?: throw UnauthorizedException(ErrorCodes.API_KEY_OWNER_INACTIVE)

        val (rows, held) = readRows(orgId, after)
        val context = Context.load(rows)

        val items = mutableListOf<FeedEvent>()
        var position = after
        for (row in rows) {
            val events = eventsOf(row, cached, context).filter { types == null || it.type in types }
            if (items.isNotEmpty() && items.size + events.size > limit) break
            items += events
            position = row.seq
            if (items.size >= limit) break
        }
        // An empty look took every row it read, so [held] still says why it stopped.
        return Look(items, position, held)
    }

    /**
     * The rows after [after] up to the first hole that may still fill, and
     * whether one stopped the read. Rows of other organizations come without
     * their payload: only their position matters here.
     */
    private fun readRows(orgId: UUID, after: Long): Pair<List<Row>, Boolean> {
        val sql = """
            SELECT seq, id, event_type, aggregate_id, created_at,
                   COALESCE(clock_timestamp() - inserted_at < make_interval(secs => ?), false) AS young,
                   CASE WHEN payload ->> 'orgId' = ? OR payload ->> 'organizationId' = ? THEN payload::text END AS payload
            FROM outbox
            WHERE seq > ?
            ORDER BY seq
            LIMIT ?
        """.trimIndent()
        val connection = TransactionManager.current().connection.connection as Connection
        val rows = mutableListOf<Row>()
        var held = false
        connection.prepareStatement(sql).use { stmt ->
            stmt.setDouble(1, GAP_GRACE.toMillis() / 1000.0)
            stmt.setString(2, orgId.toString())
            stmt.setString(3, orgId.toString())
            stmt.setLong(4, after)
            stmt.setInt(5, SCAN_ROWS)
            stmt.executeQuery().use { rs ->
                var expected = after + 1
                while (rs.next()) {
                    val seq = rs.getLong("seq")
                    if (seq != expected && rs.getBoolean("young")) {
                        held = true
                        break
                    }
                    rows += Row(
                        seq = seq,
                        id = rs.getObject("id") as UUID,
                        eventType = rs.getString("event_type"),
                        aggregateId = rs.getObject("aggregate_id") as UUID,
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                        payload = rs.getString("payload")?.let { Json.parseToJsonElement(it).jsonObject },
                    )
                    expected = seq + 1
                }
            }
        }
        return rows to held
    }

    /** What a look needs to know about the resources its rows name, read once per look. */
    private class Context(
        val projectWorkspace: Map<UUID, UUID>,
        val serviceParents: Map<UUID, Pair<UUID, UUID>>,
        val variableKeys: Map<UUID, String>,
    ) {
        companion object {
            fun load(rows: List<Row>): Context {
                val projects = mutableSetOf<UUID>()
                val services = mutableSetOf<UUID>()
                val variables = mutableMapOf<String, MutableSet<UUID>>()
                for (row in rows) {
                    val payload = row.payload ?: continue
                    val parent = payload.uuid("parentId")
                    when (row.eventType) {
                        "resource.service.created", "resource.service.updated", "resource.service.deleted" ->
                            parent?.let(projects::add)
                        "resource.variable.created", "resource.variable.updated", "resource.variable.deleted" -> {
                            val scope = payload.text("scope") ?: continue
                            variables.getOrPut(scope) { mutableSetOf() } += row.aggregateId
                            when (scope) {
                                "project" -> parent?.let(projects::add)
                                "service" -> parent?.let(services::add)
                            }
                        }
                    }
                }
                val projectWorkspace = if (projects.isEmpty()) emptyMap() else
                    Projects.select(Projects.id, Projects.workspaceId)
                        .where { Projects.id inList projects }
                        .associate { it[Projects.id] to it[Projects.workspaceId] }
                val serviceParents = if (services.isEmpty()) emptyMap() else
                    Services.join(Projects, JoinType.INNER, Services.projectId, Projects.id)
                        .select(Services.id, Services.projectId, Projects.workspaceId)
                        .where { Services.id inList services }
                        .associate { it[Services.id] to (it[Services.projectId] to it[Projects.workspaceId]) }
                val keys = mutableMapOf<UUID, String>()
                for ((scope, ids) in variables) {
                    val (table, id, key) = when (scope) {
                        "org" -> Triple(OrgVariables, OrgVariables.id, OrgVariables.key)
                        "workspace" -> Triple(WorkspaceVariables, WorkspaceVariables.id, WorkspaceVariables.key)
                        "project" -> Triple(ProjectVariables, ProjectVariables.id, ProjectVariables.key)
                        "service" -> Triple(ServiceVariables, ServiceVariables.id, ServiceVariables.key)
                        else -> continue
                    }
                    keys += keysOf(table, id, key, ids)
                }
                return Context(projectWorkspace, serviceParents, keys)
            }

            private fun keysOf(table: Table, id: Column<UUID>, key: Column<String>, ids: Set<UUID>): Map<UUID, String> =
                table.select(id, key).where { id inList ids }.associate { it[id] to it[key] }
        }
    }

    /**
     * The events [row] is, as the caller may see them: none for another
     * organization's row, a kind the feed does not carry, or a resource the
     * caller may not read.
     */
    private fun eventsOf(row: Row, cached: CachedPermissions, context: Context): List<FeedEvent> {
        val payload = row.payload ?: return emptyList()
        val at = row.createdAt.toString()
        val change = row.eventType.substringAfterLast('.')
        fun event(type: String, resource: String, id: UUID, data: JsonObject, suffix: String = "") =
            FeedEvent(row.id.toString() + suffix, type, at, EventResource(resource, id.toString()), data)

        return when (row.eventType) {
            "probe_result.created" -> {
                val service = payload.uuid("serviceId") ?: return emptyList()
                val project = payload.uuid("projectId") ?: return emptyList()
                val workspace = payload.uuid("workspaceId") ?: return emptyList()
                if (!visible(cached, "service", service, project, workspace)) return emptyList()
                val status = payload.text("status")
                buildList {
                    add(event(EventTypes.RESULT_RECORDED, "service", service, buildJsonObject {
                        put("resultId", payload.text("resultId"))
                        put("status", status)
                        put("durationMs", payload["runDurationMs"]?.jsonPrimitive?.longOrNull)
                        put("projectId", project.toString())
                        put("workspaceId", workspace.toString())
                    }))
                    if (payload["statusChanged"]?.jsonPrimitive?.booleanOrNull == true) {
                        add(event(EventTypes.SERVICE_STATUS_CHANGED, "service", service, buildJsonObject {
                            put("status", status)
                            put("previousStatus", payload["previousStatus"] ?: JsonNull)
                            put("resultId", payload.text("resultId"))
                        }, suffix = ":status"))
                    }
                }
            }
            "resource.workspace.created", "resource.workspace.updated", "resource.workspace.deleted" -> {
                val workspace = row.aggregateId
                if (!visible(cached, "workspace", workspace, null, workspace)) return emptyList()
                listOf(event("workspace.$change", "workspace", workspace, JsonObject(emptyMap())))
            }
            "resource.project.created", "resource.project.updated", "resource.project.deleted" -> {
                val workspace = payload.uuid("parentId") ?: return emptyList()
                if (!visible(cached, "project", row.aggregateId, row.aggregateId, workspace)) return emptyList()
                listOf(event("project.$change", "project", row.aggregateId, buildJsonObject {
                    put("workspaceId", workspace.toString())
                }))
            }
            "resource.service.created", "resource.service.updated", "resource.service.deleted" -> {
                val project = payload.uuid("parentId") ?: return emptyList()
                val workspace = context.projectWorkspace[project] ?: return emptyList()
                if (!visible(cached, "service", row.aggregateId, project, workspace)) return emptyList()
                listOf(event("service.$change", "service", row.aggregateId, buildJsonObject {
                    put("projectId", project.toString())
                    put("workspaceId", workspace.toString())
                }))
            }
            "resource.variable.created", "resource.variable.updated", "resource.variable.deleted" -> {
                val scope = payload.text("scope") ?: return emptyList()
                val parent = payload.uuid("parentId") ?: return emptyList()
                val allowed = when (scope) {
                    // Organization variables are read under the settings section.
                    "org" -> cached.org.settings.canRead()
                    "workspace" -> visible(cached, "workspace", parent, null, parent)
                    "project" -> context.projectWorkspace[parent]?.let { visible(cached, "project", parent, parent, it) } == true
                    "service" -> context.serviceParents[parent]?.let { (p, w) -> visible(cached, "service", parent, p, w) } == true
                    // A webhook's variables are not part of the feed.
                    else -> false
                }
                if (!allowed) return emptyList()
                // Its key, never its value: the value is not in the outbox, and
                // is not looked up here.
                listOf(event("variable.$change", "variable", row.aggregateId, buildJsonObject {
                    put("scope", scope)
                    put("scopeId", parent.toString())
                    put("key", context.variableKeys[row.aggregateId])
                }))
            }
            SystemAlertService.ALERT_RAISED_EVENT -> {
                // The warning log's permission: settings write.
                if (!cached.org.settings.canWrite()) return emptyList()
                listOf(event(EventTypes.ALERT_RAISED, "alert", row.aggregateId, buildJsonObject {
                    put("alertType", payload.text("alertType"))
                    put("subject", payload.text("subject"))
                    put("severity", payload.text("severity"))
                }))
            }
            else -> emptyList()
        }
    }

    /**
     * Whether the caller may read the workspace, project or service — the
     * check the dashboard's read of it makes, with the same downward
     * inheritance: a service through its project and workspace, a project
     * through its workspace.
     */
    internal fun visible(cached: CachedPermissions, type: String, id: UUID, project: UUID?, workspace: UUID): Boolean =
        when (type) {
            "workspace" -> canAccessResource(cached, "workspace", id)
            "project" -> canAccessResource(cached, "project", id, listOf("workspace::$workspace"))
            "service" -> canAccessResource(cached, "service", id, listOf("project::$project", "workspace::$workspace"))
            else -> false
        }

    private fun JsonObject.text(field: String): String? = (this[field] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.uuid(field: String): UUID? = text(field)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}
