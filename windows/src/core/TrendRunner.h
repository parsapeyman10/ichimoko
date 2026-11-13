#pragma once

#include "core/BacktestEngine.h"
#include "core/Ichimoku.h"

namespace aurum {

struct TrendRunnerDecision {
    bool active{};
    bool keepOpen{};
    double trailingStop{};
    double unrealizedR{};
};

// Evaluated only on a closed bar; the resulting stop applies from the next bar.
[[nodiscard]] TrendRunnerDecision evaluateTrendRunner(Side side, double entry, double initialRisk,
    double currentStop, const Candle& closedBar, const IchimokuPoint& now,
    const IchimokuPoint& before, double activationR = 2.85, double atrMultiplier = 1.20);

} // namespace aurum
