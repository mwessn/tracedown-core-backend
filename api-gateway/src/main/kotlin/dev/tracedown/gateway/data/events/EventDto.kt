package dev.tracedown.gateway.data.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** What an event is about. */
@Serializable
data class EventResource(
    /** `workspace`, `project`, `service`, `variable` or `alert`. */
    val type: String,
    val id: String,
)

/** One event of the feed. */
@Serializable
data class FeedEvent(
    /** Unique per event; the same event read twice has the same id. */
    val id: String,
    /** See [dev.tracedown.gateway.controllers.events.EventTypes]. */
    val type: String,
    /** When it happened: for a result, when its run started. ISO-8601. */
    val occurredAt: String,
    val resource: EventResource,
    /** What the type carries; never a variable's value or any other secret. */
    val data: JsonObject,
)

/** A read of the feed: the events after the cursor, and the cursor to read on from. */
@Serializable
data class EventPage(
    val items: List<FeedEvent>,
    /** Pass as `after` to read on. Moves even when [items] is empty. */
    val next: String,
)
