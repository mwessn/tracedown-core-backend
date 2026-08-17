package dev.tracedown.metrics.listeners

import dev.tracedown.metrics.cache.MetricsWriter
import dev.tracedown.metrics.cache.MetricsWriterTest.FakeRedis
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class NudgeListenerTest {

    private lateinit var redis: FakeRedis
    private lateinit var writer: MetricsWriter
    private lateinit var listener: NudgeListener
    private lateinit var testScope: CoroutineScope

    /** Captured listener from addListener call, used to simulate pub/sub messages. */
    private var capturedListener: io.lettuce.core.pubsub.RedisPubSubListener<String, String>? = null

    @BeforeEach
    fun setUp() {
        redis = FakeRedis()
        writer = MetricsWriter(redis.commands(), 86400L, 172800L, 604800L)
        testScope = CoroutineScope(UnconfinedTestDispatcher())

        val pubSubConnection = createFakePubSubConnection()
        listener = NudgeListener(pubSubConnection, writer, testScope)
        listener.start()
    }

    @Test
    fun `valid nudge JSON calls MetricsWriter record with correct params`() {
        val serviceId = UUID.randomUUID()
        val json = """{"serviceId":"$serviceId","status":"success","totalResponseMs":142}"""

        simulateMessage(json)

        val counters = redis.hashes["metrics:svc:$serviceId:counters"]
        assertNotNull(counters)
        assertEquals("1", counters!!["probes_total"])
        assertEquals("1", counters["probes_success"])

        val state = redis.hashes["metrics:svc:$serviceId:state"]
        assertNotNull(state)
        assertEquals("success", state!!["last_status"])
        assertEquals("142", state["last_response_ms"])
    }

    @Test
    fun `empty message is ignored`() {
        simulateMessage("")
        simulateMessage("   ")
        simulateMessage(null)

        assertTrue(redis.hashes.isEmpty(), "no data should be written for empty messages")
    }

    @Test
    fun `malformed JSON is handled gracefully`() {
        assertDoesNotThrow {
            simulateMessage("not-json-at-all")
            simulateMessage("{broken")
            simulateMessage("12345")
        }
        assertTrue(redis.hashes.isEmpty(), "no data should be written for malformed JSON")
    }

    @Test
    fun `missing fields are handled gracefully`() {
        assertDoesNotThrow {
            // Missing serviceId
            simulateMessage("""{"status":"success","elapsedMs":100}""")
            // Missing status
            simulateMessage("""{"serviceId":"${UUID.randomUUID()}","elapsedMs":100}""")
        }
        assertTrue(redis.hashes.isEmpty(), "no data should be written when required fields are missing")
    }

    @Test
    fun `missing elapsedMs defaults to 0`() {
        val serviceId = UUID.randomUUID()
        val json = """{"serviceId":"$serviceId","status":"timeout"}"""

        simulateMessage(json)

        val state = redis.hashes["metrics:svc:$serviceId:state"]
        assertNotNull(state)
        assertEquals("0", state!!["last_response_ms"])
    }

    private fun simulateMessage(message: String?) {
        capturedListener?.message("notify:nudge", message)
    }

    @Suppress("UNCHECKED_CAST")
    private fun createFakePubSubConnection(): StatefulRedisPubSubConnection<String, String> {
        val syncCommands = Proxy.newProxyInstance(
            RedisPubSubCommands::class.java.classLoader,
            arrayOf(RedisPubSubCommands::class.java),
        ) { _, method: Method, _ ->
            when (method.name) {
                "subscribe" -> null
                "unsubscribe" -> null
                else -> null
            }
        } as RedisPubSubCommands<String, String>

        return Proxy.newProxyInstance(
            StatefulRedisPubSubConnection::class.java.classLoader,
            arrayOf(StatefulRedisPubSubConnection::class.java),
        ) { _, method: Method, args: Array<Any>? ->
            when (method.name) {
                "addListener" -> {
                    capturedListener = args!![0] as io.lettuce.core.pubsub.RedisPubSubListener<String, String>
                    null
                }
                "sync" -> syncCommands
                else -> null
            }
        } as StatefulRedisPubSubConnection<String, String>
    }
}
