package dev.tracedown.common.redis

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

object RedisFactory {

    /** Creates a standard Redis connection for commands. */
    fun createConnection(redisUrl: String): StatefulRedisConnection<String, String> {
        val client = RedisClient.create(redisUrl)
        return client.connect()
    }

    /** Creates a pub/sub Redis connection for subscribe/publish. */
    fun createPubSubConnection(redisUrl: String): StatefulRedisPubSubConnection<String, String> {
        val client = RedisClient.create(redisUrl)
        return client.connectPubSub()
    }
}
