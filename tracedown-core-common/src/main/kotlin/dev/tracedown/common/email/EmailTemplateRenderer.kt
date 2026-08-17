package dev.tracedown.common.email

import java.util.concurrent.ConcurrentHashMap

object EmailTemplateRenderer {

    private val cache = ConcurrentHashMap<String, String>()

    fun render(templateName: String, variables: Map<String, String>): String {
        val template = cache.getOrPut(templateName) {
            val path = "/email-templates/$templateName.html"
            val stream = this::class.java.getResourceAsStream(path)
                ?: throw IllegalArgumentException("Email template not found: $path")
            stream.bufferedReader().readText()
        }

        var result = template
        for ((key, value) in variables) {
            result = result.replace("{{$key}}", value)
        }
        return result
    }

    fun clearCache() {
        cache.clear()
    }
}
