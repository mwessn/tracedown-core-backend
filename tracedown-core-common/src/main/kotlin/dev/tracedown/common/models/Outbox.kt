package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object Outbox : Table("outbox") {
    val id = uuid("id")
    val aggregateType = varchar("aggregate_type", 32)
    val aggregateId = uuid("aggregate_id")
    val eventType = varchar("event_type", 64)
    val payload = jsonb<JsonObject>("payload", Json.Default)
    val published = bool("published").default(false)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
