package com.aurum.edge.core

import kotlinx.serialization.Serializable

/** A user's educational decision at a historical cursor; it is not a broker fill or live trade. */
@Serializable
data class ReplayDecision(
    val id: String,
    val symbol: String,
    val interval: String,
    val barTime: Long,
    val action: String,
    val entry: Double?,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val confidence: Double,
    val profile: String,
    val dataSource: String,
    val recordedAt: Long,
    /** DECISION_ONLY/PENDING_ENTRY/OPEN/OPEN_AT_END/WIN/LOSS/DATA_GAP/NO_LEVELS; computed only from bars revealed by replay. */
    val outcomeStatus: String = "DECISION_ONLY",
    val fillBarTime: Long? = null,
    val fillPrice: Double? = null,
    val outcomeBarTime: Long? = null,
    val outcomePrice: Double? = null,
    val outcomeReason: String? = null,
)
