package com.aurum.edge.ui

import com.aurum.edge.core.Interval
import com.aurum.edge.engine.Backtester
import com.aurum.edge.engine.ReplayEngine

/** State owned by the Learn tab: batch research, walk-forward and cursor replay. */
sealed interface LearnState {
    data object Idle : LearnState
    data class Loading(val step: String) : LearnState
    data class Done(val result: Backtester.Result, val interval: Interval) : LearnState
    data class Failed(val message: String) : LearnState
}

/** Cursor replay is independent from batch research but uses the same immutable dataset. */
sealed interface ReplayState {
    data object Idle : ReplayState
    data object Loading : ReplayState
    data class Ready(val snapshot: ReplayEngine.Snapshot) : ReplayState
    data class Failed(val message: String) : ReplayState
}

sealed interface WalkForwardState {
    data object Idle : WalkForwardState
    data class Loading(val step: String) : WalkForwardState
    data class Done(val result: Backtester.WalkForward, val interval: Interval, val saved: Boolean) : WalkForwardState
    data class Failed(val message: String) : WalkForwardState
}
