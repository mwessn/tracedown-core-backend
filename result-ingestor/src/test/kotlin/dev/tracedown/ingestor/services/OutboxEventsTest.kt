package dev.tracedown.ingestor.services

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Outbox
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * What a recorded result writes to the outbox for the readers of the event
 * feed: whether the service's status moved and from what, a skipped run as an
 * event of its own type, a writeback's variable changes — each row carrying
 * its organization, and none a variable's value.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxEventsTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_outbox_events_test")
            .withUsername("test")
            .withPassword("test")

        private val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    }

    private val orgId: UUID = UUID.randomUUID()
    private val workspaceId: UUID = UUID.randomUUID()
    private val projectId: UUID = UUID.randomUUID()

    @BeforeAll
    fun setup() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/initial_schema", "classpath:db/migrations")
            .load()
            .migrate()
        DatabaseFactory.init(postgres.jdbcUrl, postgres.username, postgres.password)
        transaction {
            val userId = UUID.randomUUID()
            Users.insert {
                it[id] = userId
                it[email] = "events-$userId@tracedown.test"
                it[passwordHash] = "x"
                it[displayName] = "Events"
                it[createdAt] = NOW
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Events Org"
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
                it[Projects.workspaceId] = this@OutboxEventsTest.workspaceId
                it[name] = "Project"
                it[createdAt] = NOW
            }
        }
    }

    private fun newService(): UUID {
        val id = UUID.randomUUID()
        transaction {
            Services.insert {
                it[Services.id] = id
                it[Services.projectId] = this@OutboxEventsTest.projectId
                it[name] = "Service $id"
                it[createdAt] = NOW
            }
        }
        return id
    }

    private fun persist(serviceId: UUID, outcome: String, extra: String = ""): UUID {
        val resultId = UUID.randomUUID()
        val envelope = Json.parseToJsonElement(
            """
            {
              "resultId": "$resultId",
              "serviceId": "$serviceId",
              "projectId": "$projectId",
              "workspaceId": "$workspaceId",
              "organizationId": "$orgId",
              "startedAt": "$NOW",
              "rawResult": { "outcome": "$outcome", "elapsedMs": 40, "calls": [] $extra }
            }
            """.trimIndent(),
        ).jsonObject
        assertEquals(ResultPersistenceService.PersistOutcome.PERSISTED, ResultPersistenceService.persist(envelope))
        return resultId
    }

    /** The outbox rows about [aggregateId], with their organization column. */
    private fun rowsOf(aggregateId: UUID): List<Triple<String, JsonObject, UUID?>> = transaction {
        Outbox.selectAll().where { Outbox.aggregateId eq aggregateId }
            .map { Triple(it[Outbox.eventType], it[Outbox.payload], it[Outbox.organizationId]) }
    }

    @Test
    fun `a result says whether it moved the service's status, and from what`() {
        val service = newService()
        val first = rowsOf(persist(service, "success")).single()
        assertEquals("probe_result.created", first.first)
        assertEquals(orgId, first.third)
        assertEquals("true", first.second["statusChanged"]!!.jsonPrimitive.content)
        assertNull(first.second["previousStatus"], "A first run has no previous status")

        val same = rowsOf(persist(service, "success")).single().second
        assertEquals("false", same["statusChanged"]!!.jsonPrimitive.content)
        assertEquals("success", same["previousStatus"]!!.jsonPrimitive.content)

        val changed = rowsOf(persist(service, "failure")).single().second
        assertEquals("true", changed["statusChanged"]!!.jsonPrimitive.content)
        assertEquals("success", changed["previousStatus"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a skipped run is an event of its own type, with its reason`() {
        val service = newService()
        val (type, payload, org) = rowsOf(persist(service, "skipped", """, "reason": "dispatch_queue_full"""")).single()
        assertEquals(ResultPersistenceService.SKIPPED_EVENT, type)
        assertEquals(orgId, org)
        assertEquals("skipped", payload["status"]!!.jsonPrimitive.content)
        assertEquals("dispatch_queue_full", payload["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a writeback announces the variables it creates and changes, never their values`() {
        val service = newService()
        persist(service, "success", """, "actions": { "variables": { "count": 5 } }""")
        persist(service, "success", """, "actions": { "variables": { "count": 6 } }""")
        val rows = transaction {
            Outbox.selectAll().where { Outbox.eventType like "resource.variable.%" }
                .orderBy(Outbox.seq, SortOrder.ASC)
                .filter { it[Outbox.payload]["parentId"]?.jsonPrimitive?.content == service.toString() }
                .map { Triple(it[Outbox.eventType], it[Outbox.payload], it[Outbox.organizationId]) }
        }
        assertEquals(listOf("resource.variable.created", "resource.variable.updated"), rows.map { it.first })
        rows.forEach { (_, payload, org) ->
            assertEquals(orgId, org)
            assertEquals("service", payload["scope"]!!.jsonPrimitive.content)
            assertEquals(setOf("id", "orgId", "scope", "parentId"), payload.keys)
            assertFalse(payload.toString().contains("\"6\"") || payload.toString().contains(":5"), "A value reached the outbox: $payload")
        }
    }
}
