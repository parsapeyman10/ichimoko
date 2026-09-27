package com.aurum.edge.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Small provider-neutral JSON path: fields, array indexes, {symbol}, and | fallbacks. */
object JsonPath {
    fun first(root: JsonElement, path: String?, symbol: String? = null): JsonElement? {
        if (path.isNullOrBlank()) return null
        path.split('|').forEach { candidate ->
            val value = read(root, candidate.trim().replace("{symbol}", symbol.orEmpty()))
            if (value != null && value !is JsonNull) return value
        }
        return null
    }

    fun number(root: JsonElement, path: String?, symbol: String? = null): Double? =
        first(root, path, symbol)?.let { element ->
            (element as? JsonPrimitive)?.contentOrNull?.let(Num::parse)
        }

    fun numbers(root: JsonElement, path: String?, symbol: String? = null): List<Double> {
        val value = first(root, path, symbol) ?: return emptyList()
        return when (value) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(Num::parse) }
            else -> listOfNotNull((value as? JsonPrimitive)?.contentOrNull?.let(Num::parse))
        }
    }

    private fun read(root: JsonElement, rawPath: String): JsonElement? {
        if (rawPath.isBlank()) return root
        var current: JsonElement? = root
        val tokens = Regex("([^\\[.]+)|\\[(\\d+)]").findAll(rawPath)
        for (match in tokens) {
            current = when {
                match.groupValues[1].isNotEmpty() -> {
                    val obj = current as? JsonObject
                    obj?.get(match.groupValues[1]) ?: if (current is JsonArray) current else null
                }
                else -> (current as? JsonArray)?.getOrNull(match.groupValues[2].toInt())
            } ?: return null
        }
        return current
    }
}
