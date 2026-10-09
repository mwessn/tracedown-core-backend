package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * How far the outbox purge has reached: one row, `id = 1`.
 *
 * [purgedThrough] is the highest `seq` the purge has ever deleted. The purge
 * does not trim a clean prefix — it keeps a probe result's row until that row
 * is published, however old — so the lowest `seq` still present says nothing
 * about what is gone. This does: a reader positioned below it may have missed
 * a deleted row, and one at or above it has missed nothing. It only ever
 * rises.
 */
object OutboxRetention : Table("outbox_retention") {
    val id = short("id")
    val purgedThrough = long("purged_through").default(0)
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
