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
)
