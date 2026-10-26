package dev.agenthub.app.core.network

import dev.agenthub.app.core.error.DataError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads `{"error": {"code": "..."}}`. Only the code is used; message text from the server is ignored. */
object ApiErrorParser {
    fun code(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
    }

    fun toError(httpStatus: Int, body: String?): DataError {
        val fromCode = DataError.fromCode(code(body))
        return when {
            fromCode != DataError.Unknown -> fromCode
            httpStatus == 401 -> DataError.Unauthorized
            else -> DataError.Unknown
        }
    }
}
