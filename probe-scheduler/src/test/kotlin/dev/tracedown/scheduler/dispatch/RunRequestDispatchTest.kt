package dev.tracedown.scheduler.dispatch

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.common.runs.RunTrigger
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.scheduler.config.SchedulerConfig
import dev.tracedown.scheduler.results.ResultPublisher
import dev.tracedown.scheduler.scheduling.QuartzManager
import dev.tracedown.scheduler.scheduling.ScheduleSyncService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A run asked for under an id is filed under that id — its result, or the
 * skipped row that says why it was not made — and a scheduled tick is not.
 *
 * The id rides on the envelope as its `resultId`, which every ingestor, old or
 * new, takes as the row's primary key; `trigger` is new, and an ingestor that
 * predates it simply files the run as scheduled.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunRequestDispatchTest {

    companion object {
        private const val AES_KEY = "0000000000000000000000000000000000000000000000000000000000000000"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_run_request_test")
            .withUsername("test")
            .withPassword("test")

        @Container
        @JvmStatic
        val redis = GenericContainer("redis:8-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort())
    }

    /** Answers every request with one result per agent in [agents]; [hold] keeps it waiting first. */
    private class Backend(private val agents: Int = 1, val hold: CompletableDeferred<Unit>? = null) : ProbeExecutionBackend {
        val requests = CopyOnWriteArrayList<ProbeExecutionBackend.Request>()

        override suspend fun execute(request: ProbeExecutionBackend.Request): List<ProbeExecutionBackend.Execution> {
            requests.add(request)
            hold?.await()
            return (1..agents).map {
                ProbeExecutionBackend.Execution(
                    agentId = null,
                    result = buildJsonObject { put("outcome", "success"); put("elapsedMs", 5) },
                )
            }
        }
    }

    private lateinit var redisSync: io.lettuce.core.api.sync.RedisCommands<String, String>
    private lateinit var quartzManager: QuartzManager
    private lateinit var queuePolicy: QueuePolicyManager
    private lateinit var resultPublisher: ResultPublisher

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
        redisSync = RedisFactory.createConnection("redis://${redis.host}:${redis.getMappedPort(6379)}").sync()
        VariableCrypto.init(AES_KEY)
        quartzManager = QuartzManager(1)
        queuePolicy = QueuePolicyManager(redisSync)
        resultPublisher = ResultPublisher(redisSync)

        transaction {
            val userId = UUID.randomUUID()
            Users.insert {
                it[id] = userId
                it[email] = "run-request-test@tracedown.dev"
                it[passwordHash] = "x"
                it[displayName] = "Run Request Test"
                it[createdAt] = Instant.now()
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Run Request Org"
                it[ownerId] = userId
                it[createdAt] = Instant.now()
            }
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "Run Request Workspace"
                it[createdAt] = Instant.now()
            }
            Projects.insert {
                it[id] = projectId
                it[Projects.workspaceId] = this@RunRequestDispatchTest.workspaceId
                it[name] = "Run Request Project"
                it[createdAt] = Instant.now()
            }
        }
    }

    @AfterAll
    fun shutdown() = quartzManager.shutdown()

    @BeforeEach
    fun clearQueue() {
        redisSync.del(ResultPublisher.QUEUE_KEY)
    }

    private fun seedService(queuePolicy: String = "skip", probeMode: String = "consecutive", active: Boolean = true): UUID {
        val serviceId = UUID.randomUUID()
        transaction {
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = this@RunRequestDispatchTest.projectId
                it[name] = "svc-${serviceId.toString().take(6)}"
                it[script] = """get("https://testbin.tracedown.dev/status/200").expect(status: 200)"""
                it[schedule] = "*/5 * * * *"
                it[isActive] = active
                it[Services.queuePolicy] = queuePolicy
                it[Services.probeMode] = probeMode
                it[createdAt] = Instant.now()
            }
        }
        return serviceId
    }

    private fun queue(backend: ProbeExecutionBackend, capacity: Int = 4, workers: Int = 1) = DispatchQueue(
        capacity = capacity,
        workers = workers,
        quartzManager = quartzManager,
        executionBackend = backend,
        queuePolicy = queuePolicy,
        resultPublisher = resultPublisher,
        probeConfig = SchedulerConfig.ProbeConfig(defaultTimeoutMs = 30_000, maxTimeoutMs = 30_000, maxRedirects = 5),
        trustedDomainMode = true,
    )

    /** The envelopes queued for the ingestor, oldest first. */
    private fun envelopes(): List<JsonObject> =
        redisSync.lrange(ResultPublisher.QUEUE_KEY, 0, -1).reversed().map { Json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    /** Runs [items] through a started queue until [count] envelopes are queued, and returns them. */
    private fun run(backend: ProbeExecutionBackend, count: Int, vararg items: DispatchItem): List<JsonObject> = runBlocking {
        val queue = queue(backend)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            queue.start(scope)
            items.forEach { queue.enqueue(it) }
            val deadline = System.currentTimeMillis() + 10_000
            while (envelopes().size < count && System.currentTimeMillis() < deadline) delay(50)
            // Long enough for a row that should not be there to show up.
            delay(300)
            envelopes()
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun `a run asked for under an id is filed under it, as a manual run`() {
        val serviceId = seedService()
        val runId = UUID.randomUUID()
        val queued = run(Backend(), 1, DispatchItem(serviceId, manual = true, runId = runId))
        val envelope = queued.single()
        assertEquals(runId.toString(), envelope.str("resultId"))
        assertEquals(RunTrigger.MANUAL, envelope.str("trigger"))
        assertEquals("success", envelope["rawResult"]!!.jsonObject.str("outcome"))
    }

    @Test
    fun `a scheduled tick and a run with no id are filed under ids of their own`() {
        val serviceId = seedService()
        val scheduled = run(Backend(), 1, DispatchItem.scheduled(serviceId)).single()
        assertEquals(RunTrigger.SCHEDULE, scheduled.str("trigger"))
        redisSync.del(ResultPublisher.QUEUE_KEY)
        val bare = run(Backend(), 1, DispatchItem(serviceId, manual = true)).single()
        assertEquals(RunTrigger.MANUAL, bare.str("trigger"))
        assertNotEquals(scheduled.str("resultId"), bare.str("resultId"))
    }

    @Test
    fun `in simultaneous mode the run's id goes to one result, and its siblings share the job`() {
        val serviceId = seedService(probeMode = "simultaneous")
        val runId = UUID.randomUUID()
        val queued = run(Backend(agents = 2), 2, DispatchItem(serviceId, manual = true, runId = runId))
        assertEquals(2, queued.size)
        assertEquals(1, queued.count { it.str("resultId") == runId.toString() })
        assertEquals(1, queued.map { it.str("jobId") }.distinct().size)
        assertTrue(queued.all { it.str("trigger") == RunTrigger.MANUAL })
    }

    @Test
    fun `a run that is not made is answered under its id, where a scheduled tick leaves nothing`() {
        val inactive = seedService(active = false)
        val runId = UUID.randomUUID()
        val skipped = run(Backend(), 1, DispatchItem(inactive, manual = true, runId = runId)).single()
        assertEquals(runId.toString(), skipped.str("resultId"))
        assertEquals("skipped", skipped["rawResult"]!!.jsonObject.str("outcome"))
        assertEquals(RunTrigger.SKIP_SERVICE_INACTIVE, skipped["rawResult"]!!.jsonObject.str("reason"))
        redisSync.del(ResultPublisher.QUEUE_KEY)
        assertTrue(run(Backend(), 0, DispatchItem.scheduled(inactive)).isEmpty(), "a scheduled tick of a switched-off service writes nothing")

        redisSync.del(ResultPublisher.QUEUE_KEY)
        val busy = seedService(queuePolicy = "skip")
        redisSync.set("probe_active:$busy", "someone-else")
        try {
            val busyRun = UUID.randomUUID()
            val answer = run(Backend(), 1, DispatchItem(busy, manual = true, runId = busyRun)).single()
            assertEquals(busyRun.toString(), answer.str("resultId"))
            assertEquals(RunTrigger.SKIP_ALREADY_RUNNING, answer["rawResult"]!!.jsonObject.str("reason"))
        } finally {
            redisSync.del("probe_active:$busy")
        }

        redisSync.del(ResultPublisher.QUEUE_KEY)
        val noScript = seedService()
        transaction { Services.update({ Services.id eq noScript }) { it[script] = "" } }
        val noScriptRun = UUID.randomUUID()
        val missing = run(Backend(), 1, DispatchItem(noScript, manual = true, runId = noScriptRun)).single()
        assertEquals(RunTrigger.SKIP_SCRIPT_MISSING, missing["rawResult"]!!.jsonObject.str("reason"))
    }

    @Test
    fun `a run that has to wait for the running one is filed under its id when it runs`() {
        val serviceId = seedService(queuePolicy = "enqueue_once")
        redisSync.set("probe_active:$serviceId", "someone-else")
        val runId = UUID.randomUUID()
        val other = UUID.randomUUID()
        try {
            val acquired = queuePolicy.tryAcquire(serviceId, "enqueue_once", 30_000, runId)
            assertEquals(QueuePolicyManager.AcquireResult.ENQUEUED, acquired.result)
            // A second run cannot also be the one that follows.
            assertEquals(QueuePolicyManager.AcquireResult.SKIPPED, queuePolicy.tryAcquire(serviceId, "enqueue_once", 30_000, other).result)
            // The holder takes the pending run's id with it.
            assertEquals(runId, queuePolicy.takePendingRun(serviceId))
            assertEquals(null, queuePolicy.takePendingRun(serviceId), "handed to one dispatch only")
        } finally {
            redisSync.del("probe_active:$serviceId", "probe_pending:$serviceId", "probe_pending_run:$serviceId")
        }

        // Through the queue: a second worker finds the lock held and leaves the
        // run pending; the lock's owner re-enqueues it under its id.
        val hold = CompletableDeferred<Unit>()
        val backend = Backend(hold = hold)
        val queued = runBlocking {
            val queue = queue(backend, workers = 2)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                queue.start(scope)
                queue.enqueue(DispatchItem.scheduled(serviceId))
                val deadline = System.currentTimeMillis() + 10_000
                while (backend.requests.isEmpty() && System.currentTimeMillis() < deadline) delay(20)
                // The scheduled tick holds the lock; this run waits behind it.
                queue.enqueue(DispatchItem(serviceId, manual = true, runId = runId))
                delay(300)
                assertEquals(1, backend.requests.size, "the run waits behind the one holding the lock")
                assertEquals(runId.toString(), redisSync.get("probe_pending_run:$serviceId"))
                hold.complete(Unit)
                while (envelopes().size < 2 && System.currentTimeMillis() < deadline) delay(50)
                envelopes()
            } finally {
                queue.close()
                scope.cancel()
            }
        }
        assertEquals(listOf(RunTrigger.SCHEDULE, RunTrigger.MANUAL), queued.map { it.str("trigger") })
        assertEquals(runId.toString(), queued[1].str("resultId"))
    }

    @Test
    fun `every replica hears a run, and one runs it`() {
        val serviceId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val enqueued = CopyOnWriteArrayList<DispatchItem>()
        val replicas = (1..3).map { ScheduleSyncService(quartzManager, 60, pubSubConnection = null, claims = redisSync) { item -> enqueued.add(item) } }
        replicas.forEach { it.onMessage(RunTrigger.RUN_CHANNEL, RunTrigger.encodeRun(serviceId, runId)) }
        assertEquals(listOf(DispatchItem(serviceId, manual = true, runId = runId)), enqueued)
    }
}
