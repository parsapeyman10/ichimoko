#include "core/TrendRunner.h"

#include <algorithm>
#include <cmath>

namespace aurum {

TrendRunnerDecision evaluateTrendRunner(const Side side, const double entry, const double initialRisk,
    const double currentStop, const Candle& closedBar, const IchimokuPoint& now,
    const IchimokuPoint& before, const double activationR, const double atrMultiplier) {
    const double direction = side == Side::buy ? 1.0 : -1.0;
    const double unrealizedR = initialRisk > 1e-9 ? direction * (closedBar.close - entry) / initialRisk : 0.0;
    if (!closedBar.closed || !now.valid || !before.valid || now.atr14 <= 0.0 || unrealizedR < activationR)
        return {false, false, currentStop, unrealizedR};
    const double cloudHigh = std::max(now.spanA, now.spanB);
    const double cloudLow = std::min(now.spanA, now.spanB);
    const bool healthy = side == Side::buy
        ? closedBar.close > cloudHigh && closedBar.close > now.kijun && now.tenkan >= now.kijun && now.tenkan >= before.tenkan
        : closedBar.close < cloudLow && closedBar.close < now.kijun && now.tenkan <= now.kijun && now.tenkan <= before.tenkan;
    if (!healthy) return {false, false, currentStop, unrealizedR};
    const double volatilityStop = closedBar.close - direction * std::max(0.1, atrMultiplier) * now.atr14;
    const double structureStop = now.kijun - direction * 0.20 * now.atr14;
    const double candidate = side == Side::buy ? std::max(volatilityStop, structureStop) : std::min(volatilityStop, structureStop);
    const double trailing = side == Side::buy ? std::max(currentStop, candidate) : std::min(currentStop, candidate);
    const bool beyondClose = side == Side::buy ? trailing >= closedBar.close : trailing <= closedBar.close;
    return {true, true, beyondClose ? currentStop : trailing, unrealizedR};
}

} // namespace aurum
