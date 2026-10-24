package com.aurum.edge.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HistorySourceAudit(val selected: String?, val checkedAt: Long, val note: String)

object HistorySourceAudits {
    private val mutable = MutableStateFlow<Map<String, HistorySourceAudit>>(emptyMap())
    val state = mutable.asStateFlow()
    internal fun record(symbol: String, selected: String?, note: String, now: Long) {
        mutable.update { it + (symbol to HistorySourceAudit(selected, now, note)) }
    }
}
