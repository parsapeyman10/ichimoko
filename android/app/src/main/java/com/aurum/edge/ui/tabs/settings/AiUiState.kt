package com.aurum.edge.ui

/** Connectivity and model catalogue state used by the API settings tab. */
data class AiProbeState(val running: Boolean = false, val message: String? = null)

data class AiModelsState(
    val loading: Boolean = false,
    val models: List<String> = emptyList(),
    val error: String? = null,
)
