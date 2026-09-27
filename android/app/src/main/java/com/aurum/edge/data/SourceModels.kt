package com.aurum.edge.data

import kotlinx.serialization.Serializable

@Serializable
enum class SourceKind { JSON_REST }

@Serializable
enum class ChangeMode { PERCENT, ABSOLUTE, PREV_CLOSE, NONE }

@Serializable
enum class SourceTime { NONE, UNIX_SECONDS, UTC_DATETIME }

@Serializable
data class SymbolDef(
    val code: String,
    val label: String,
    val sourceId: String = "",
)

@Serializable
data class SourceDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: SourceKind,
    val urlTemplate: String,
    val batchTemplate: String? = null,
    val pricePath: String? = null,
    val changePath: String? = null,
    val changeMode: ChangeMode = ChangeMode.NONE,
    val sparkPath: String? = null,
    val volumePath: String? = null,
    val scale: Double = 1.0,
    val unit: String = "",
    val symbols: List<SymbolDef> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val builtIn: Boolean = true,
    val requiresKey: Boolean = false,
    val timestampPath: String? = null,
    val timestampMode: SourceTime = SourceTime.NONE,
)

@Serializable
data class Quote(
    val code: String,
    val label: String,
    val price: Double? = null,
    val changePct: Double? = null,
    val volume: Double? = null,
    val unit: String = "",
    val error: String? = null,
    val ts: Long = System.currentTimeMillis(),
    val stale: Boolean = false,
    val spark: List<Double> = emptyList(),
    val sourceId: String,
    /** Provider's last-trade/update time; null means freshness cannot be verified. */
    val providerAt: Long? = null,
)

data class SourceSnapshot(
    val source: SourceDef,
    val quotes: List<Quote>,
    val fetchedAt: Long = System.currentTimeMillis(),
    val online: Boolean = true,
    val error: String? = null,
)

