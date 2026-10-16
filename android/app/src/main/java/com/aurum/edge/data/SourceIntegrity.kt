package com.aurum.edge.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A fresh tick is not evidence that a previously observed publisher conflict was resolved.
 * Process-local latch, independent of UI, data receipt status and the active network route.
 * Only a NEW comparison in the same channel with >=2 agreeing publishers can clear it.
 */
internal class SourceIntegrity {
    enum class Channel { HISTORY, GOLD_QUOTE }
    data class Fault(val revision: Long = 0, val reason: String? = null)
    data class State(val revision: Long = 0, val channels: Map<Channel, Fault> = emptyMap())
    private val mutable = MutableStateFlow<Map<String, State>>(emptyMap())
    val state = mutable.asStateFlow()

    @Synchronized fun revision(symbol: String): Long = mutable.value[symbol]?.revision ?: 0L
    @Synchronized fun begin(symbol: String, channel: Channel): Long =
        mutable.value[symbol]?.channels?.get(channel)?.revision ?: 0L
    @Synchronized fun channelBlocked(symbol: String, channel: Channel): Boolean =
        mutable.value[symbol]?.channels?.get(channel)?.reason != null
    @Synchronized fun reject(symbol: String, channel: Channel, reason: String) {
        val old = mutable.value[symbol] ?: State()
        val fault = old.channels[channel] ?: Fault()
        mutable.value = mutable.value + (symbol to State(old.revision + 1,
            old.channels + (channel to Fault(fault.revision + 1, reason))))
    }
    /** Return an atomic revision stamp. An older in-flight comparison cannot clear a newer fault. */
    @Synchronized fun accept(symbol: String, channel: Channel, token: Long, confirmations: Int): Long? {
        val old = mutable.value[symbol] ?: State()
        val fault = old.channels[channel] ?: Fault()
        if (token != fault.revision) return null
        if (fault.reason == null) return old.revision
        if (confirmations < 2) return null
        val next = State(old.revision + 1, old.channels + (channel to Fault(fault.revision + 1)))
        mutable.value = mutable.value + (symbol to next)
        return next.revision
    }
    @Synchronized fun blocker(symbol: String, evaluatedRevision: Long): String? {
        val current = mutable.value[symbol] ?: State()
        return current.channels.values.firstNotNullOfOrNull { it.reason }
            ?: if (evaluatedRevision != current.revision) "اعتبار منابع تغییر کرده؛ تاریخچه و تحلیل دوباره لازم است" else null
    }
    internal fun resetForTests() { mutable.value = emptyMap() }
    companion object { val shared = SourceIntegrity() }
}

/** Typed separately from a transient socket failure; its message never includes credentials. */
internal class SourceDisagreement(message: String) : DataFeedException(message)
