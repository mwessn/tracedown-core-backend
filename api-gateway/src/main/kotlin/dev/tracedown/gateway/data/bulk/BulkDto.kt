package dev.tracedown.gateway.data.bulk

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
data class BulkRequest(
    val requests: List<BulkSubRequest>,
) : Validatable {
    override fun validate() = buildList {
        requests.forEach { addAll(it.validate()) }
    }
}

@Serializable
data class BulkSubRequest(
    val method: String,
    val url: String,
    val body: JsonElement? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("method", method)?.let(::add)
        Validators.maxLen("method", method, 16)?.let(::add)
        Validators.notBlank("url", url)?.let(::add)
        Validators.maxLen("url", url, 2048)?.let(::add)
    }
}

@Serializable
data class BulkResponse(
    val calls: List<BulkCallResult>,
)

@Serializable
data class BulkCallResult(
    val index: Int,
    val request: BulkSubRequest,
    val response: BulkSubResponse,
)

@Serializable
data class BulkSubResponse(
    val status: Int,
    val body: JsonElement = JsonNull,
)
