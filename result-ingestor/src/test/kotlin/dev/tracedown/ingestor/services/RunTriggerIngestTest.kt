package dev.tracedown.ingestor.services

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.RunRequests
import dev.tracedown.common.models.RunState
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.runs.RunTrigger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * What started a run is recorded with it, and a run somebody asked for under
 * an id settles their request in the same transaction as its result.
 *
 * An envelope from a scheduler that predates the `trigger` field is filed as
 * scheduled, which is what the column says of every row before it existed.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunTriggerIngestTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_run_trigger_ingest_test")
            .withUsername("test")
            .withPassword("test")

        private val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    }

    private val userId: UUID = UUID.randomUUID()
    private val orgId: UUID = UUID.randomUUID()
    private val workspaceId: UUID = UUID.randomUUID()
    private val projectId: UUID = UUID.randomUUID()
    private val serviceId: UUID = UUID.randomUUID()

    @BeforeAll
    fun setup() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/initial_schema", "classpath:db/migrations")
            .load()
            .migrate()
        DatabaseFactory.init(postgres.jdbcUrl, postgres.username, postgres.password)

        transaction {
            Users.insert {
                it[id] = userId
                it[email] = "run-trigger-$userId@tracedown.test"
                it[passwordHash] = "x"
                it[displayName] = "Run Trigger"
                it[createdAt] = NOW
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Run Trigger Org"
                it[ownerId] = userId
                it[createdAt] = NOW
            }
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "Workspace"
                it[createdAt] = NOW
            }
            Projects.insert {
                it[id] = projectId
                it[Projects.workspaceId] = this@RunTriggerIngestTest.workspaceId
                it[name] = "Project"
                it[createdAt] = NOW
            }
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = this@RunTriggerIngestTest.projectId
                it[name] = "Service"
                it[createdAt] = NOW
            }
        }
    }

    @Test
    fun `the trigger an envelope names is recorded, and one that names none is a scheduled run`() {
        val manual = persist(trigger = RunTrigger.MANUAL)
        val scheduled = persist(trigger = RunTrigger.SCHEDULE)
        val unnamed = persist(trigger = null)
        val unknown = persist(trigger = "cron")
        assertEquals(RunTrigger.MANUAL, triggerOf(manual))
        assertEquals(RunTrigger.SCHEDULE, triggerOf(scheduled))
        assertEquals(RunTrigger.SCHEDULE, triggerOf(unnamed))
        assertEquals(RunTrigger.SCHEDULE, triggerOf(unknown))
    }

    @Test
    fun `a manual run filed under a request's id settles it, done or skipped`() {
        val done = request()
        persist(id = done, trigger = RunTrigger.MANUAL)
        assertEquals(RunState.DONE to done, stateOf(done))

        val skipped = request()
        persist(id = skipped, trigger = RunTrigger.MANUAL, outcome = "skipped", reason = RunTrigger.SKIP_ALREADY_RUNNING)
        assertEquals(RunState.SKIPPED to skipped, stateOf(skipped))
    }

    @Test
    fun `a scheduled run under a request's id does not settle it, and a redelivery changes nothing`() {
        val request = request()
        persist(id = request, trigger = RunTrigger.SCHEDULE)
        assertEquals(RunState.PENDING to null, stateOf(request))

        val settled = request()
        persist(id = settled, trigger = RunTrigger.MANUAL)
        assertEquals(
            ResultPersistenceService.PersistOutcome.ALREADY_PERSISTED,
            ResultPersistenceService.persist(envelope(settled, RunTrigger.MANUAL, "skipped", RunTrigger.SKIP_ALREADY_RUNNING)),
        )
        assertEquals(RunState.DONE to settled, stateOf(settled))
    }

    @Test
    fun `a skip answering a request raises nothing`() {
        for (reason in listOf(
            RunTrigger.SKIP_SERVICE_INACTIVE, RunTrigger.SKIP_SCRIPT_MISSING, RunTrigger.SKIP_IN_SERVICE_WINDOW,
            RunTrigger.SKIP_HELD, RunTrigger.SKIP_ALREADY_RUNNING, RunTrigger.SKIP_ALREADY_QUEUED,
        )) {
            assertNull(SkippedProbeAlert.alertType(reason), reason)
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun request(): UUID {
        val id = UUID.randomUUID()
        transaction {
            RunRequests.insert {
                it[RunRequests.id] = id
                it[RunRequests.serviceId] = this@RunTriggerIngestTest.serviceId
                it[organizationId] = orgId
                it[requestedBy] = userId
                it[requestedAt] = NOW
            }
        }
        return id
    }

    private fun persist(
        id: UUID = UUID.randomUUID(),
        trigger: String?,
        outcome: String = "success",
        reason: String? = null,
    ): UUID {
        assertEquals(
            ResultPersistenceService.PersistOutcome.PERSISTED,
            ResultPersistenceService.persist(envelope(id, trigger, outcome, reason)),
        )
        return id
    }

    private fun triggerOf(id: UUID): String = transaction {
        ProbeResults.selectAll().where { ProbeResults.id eq id }.single()[ProbeResults.trigger]
    }

    private fun stateOf(id: UUID): Pair<String, UUID?> = transaction {
        val row = RunRequests.selectAll().where { RunRequests.id eq id }.single()
        row[RunRequests.state] to row[RunRequests.resultId]
    }

    private fun envelope(id: UUID, trigger: String?, outcome: String, reason: String?) = Json.parseToJsonElement(
        """
        {
          "resultId": "$id",
          "serviceId": "$serviceId",
          "projectId": "$projectId",
          "workspaceId": "$workspaceId",
          "organizationId": "$orgId",
          "startedAt": "$NOW",
          ${if (trigger != null) "\"trigger\": \"$trigger\"," else ""}
          "rawResult": {"outcome": "$outcome", "elapsedMs": 0${if (reason != null) ", \"reason\": \"$reason\"" else ""}}
        }
        """.trimIndent(),
    ).jsonObject
}
