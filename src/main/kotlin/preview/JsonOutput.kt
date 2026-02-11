package preview

/**
 * Structured JSON output types for preview results.
 *
 * Supports both v1 (simple) and v2 (with metadata) formats.
 */
data class JsonOutput(
    val previews: List<JsonPreview>,
    val metadata: JsonMetadata? = null  // null for v1 format, present for v2
)

data class JsonPreview(
    val name: String,
    val result: String? = null,
    val error: JsonError? = null,
    val timingMs: Long? = null  // Execution time in milliseconds (null if profiling disabled)
)

data class JsonError(
    val message: String,
    val category: String,
    val code: String,
    val context: Map<String, String> = emptyMap()
)

data class JsonMetadata(
    val totalCount: Int,
    val successCount: Int,
    val errorCount: Int,
    val parameters: List<JsonParameter>
)

data class JsonParameter(
    val name: String,
    val type: String,
    val provider: String,
    val limit: Int?
)

/**
 * Convert PreviewResult to JsonPreview with structured error information.
 */
fun PreviewResult.toJsonPreview(): JsonPreview {
    val jsonError = errorType?.let { err ->
        JsonError(
            message = err.message,
            category = err.category,
            code = err.javaClass.simpleName.uppercase(),
            context = emptyMap()  // Could be extended with additional context
        )
    }
    return JsonPreview(fullDisplayName, result, jsonError, timingMs)
}

/**
 * Convert JsonOutput to JSON string representation.
 */
fun JsonOutput.toJsonString(): String {
    val previewsJson = previews.joinToString(",\n") { it.toJsonString() }

    return if (metadata == null) {
        // v1 format: simple
        "{\"previews\":[\n$previewsJson\n]}"
    } else {
        // v2 format: with metadata
        val metadataJson = metadata.toJsonString()
        "{\"previews\":[\n$previewsJson\n],$metadataJson}"
    }
}

private fun JsonPreview.toJsonString(): String = buildString {
    append("  {")
    append("\"name\":${jsonString(name)}")
    if (error != null) {
        append(",\"error\":${error.toJsonString()}")
    } else {
        append(",\"result\":${jsonString(result)}")
    }
    if (timingMs != null) {
        append(",\"timingMs\":$timingMs")
    }
    append("}")
}

private fun JsonError.toJsonString(): String = buildString {
    append("{")
    append("\"message\":${jsonString(message)}")
    append(",\"category\":${jsonString(category)}")
    append(",\"code\":${jsonString(code)}")
    if (context.isNotEmpty()) {
        val contextJson = context.entries.joinToString(",") { (k, v) ->
            "${jsonString(k)}:${jsonString(v)}"
        }
        append(",\"context\":{$contextJson}")
    }
    append("}")
}

private fun JsonMetadata.toJsonString(): String = buildString {
    append("\"metadata\":{")
    append("\"totalCount\":$totalCount")
    append(",\"successCount\":$successCount")
    append(",\"errorCount\":$errorCount")

    if (parameters.isNotEmpty()) {
        val paramsJson = parameters.joinToString(",") { it.toJsonString() }
        append(",\"parameters\":[$paramsJson]")
    } else {
        append(",\"parameters\":[]")
    }
    append("}")
}

private fun JsonParameter.toJsonString(): String = buildString {
    append("{")
    append("\"name\":${jsonString(name)}")
    append(",\"type\":${jsonString(type)}")
    append(",\"provider\":${jsonString(provider)}")
    if (limit != null) {
        append(",\"limit\":$limit")
    }
    append("}")
}
