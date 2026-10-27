package dev.agenthub.app.approvals.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ArgLine(val label: String, val value: String)

/**
 * Turns the exact stored arguments of an approval into readable lines. Nothing is shortened or hidden:
 * the user must see the full recipient, subject, body or attendee list they are approving (architecture 2.1).
 */
object ArgsFormatter {
    private val pretty = Json { prettyPrint = true }

    fun format(args: JsonObject): List<ArgLine> = args.entries.map { (key, value) -> ArgLine(label(key), render(value)) }

    fun label(key: String): String =
        key.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }.ifBlank { key }

    private fun render(element: JsonElement): String = when (element) {
        is JsonNull -> EMPTY
        is JsonPrimitive -> element.content.ifEmpty { EMPTY }
        is JsonArray ->
            if (element.isEmpty()) EMPTY
            else if (element.all { it is JsonPrimitive && it !is JsonNull }) element.joinToString(", ") { (it as JsonPrimitive).content }
            else pretty.encodeToString(JsonElement.serializer(), element)
        is JsonObject -> if (element.isEmpty()) EMPTY else pretty.encodeToString(JsonElement.serializer(), element)
    }

    const val EMPTY = "(empty)"
}
