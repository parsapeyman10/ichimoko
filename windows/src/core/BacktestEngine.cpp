#include "core/BacktestEngine.h"
#include "core/Ichimoku.h"

#include <algorithm>
#include <cmath>
#include <numeric>
#include <optional>
#include <stdexcept>

namespace aurum {
namespace {

struct Position final {
    Side side;
    std::int64_t entryTime;
    double entry;
    double stop;
    double target;
    double quantity;
    double entryFee;
    double riskBudget;
};

BacktestStats statistics(std::vector<Trade>& trades, const double startingEquity) {
    BacktestStats out;
    out.trades = trades.size();
    double grossWins = 0.0, grossLosses = 0.0, rSum = 0.0, equity = startingEquity, peak = startingEquity;
    for (const auto& trade : trades) {
        out.netPnl += trade.netPnl;
        out.totalFees += trade.fees;
        rSum += trade.rMultiple;
        if (trade.netPnl > 0) { ++out.wins; grossWins += trade.netPnl; }
        else if (trade.netPnl < 0) { ++out.losses; grossLosses -= trade.netPnl; }
        equity += trade.netPnl;
        peak = std::max(peak, equity);
        if (peak > 0) out.maxDrawdownPct = std::max(out.maxDrawdownPct, (peak - equity) / peak * 100.0);
    }
    out.finalEquity = equity;
    out.winRate = out.trades ? static_cast<double>(out.wins) * 100.0 / static_cast<double>(out.trades) : 0.0;
    out.profitFactor = grossLosses > 0 ? grossWins / grossLosses : (grossWins > 0 ? 1e9 : 0.0);
    out.expectancyR = out.trades ? rSum / static_cast<double>(out.trades) : 0.0;
    return out;
}

} // namespace

BacktestResult runIchimokuBaseline(const std::vector<Candle>& bars, const BacktestConfig& config) {
    if (bars.size() < 80) throw std::invalid_argument("At least 80 bars are required");
    if (config.startingEquity <= 0 || config.riskFraction <= 0 || config.riskFraction > 0.02 ||
        config.feePerSide < 0 || config.slippagePerSide < 0 || config.targetR <= 0) {
        throw std::invalid_argument("Invalid backtest risk configuration");
    }
    const auto indicators = ichimoku(bars);
    BacktestResult result;
    result.trades.reserve(bars.size() / 100);
    double equity = config.startingEquity;
    double peak = equity;
    double maxDd = 0.0;
    std::optional<Position> position;
    Side pending{Side::buy};
    bool hasPending = false;

    const auto closePosition = [&](Position p, double rawExit, const std::int64_t time, const ExitReason reason) {
        const auto direction = p.side == Side::buy ? 1.0 : -1.0;
        const auto exit = rawExit * (1.0 - direction * config.slippagePerSide);
        const auto exitFee = exit * p.quantity * config.feePerSide;
        const auto net = direction * (exit - p.entry) * p.quantity - p.entryFee - exitFee;
        equity += net;
        result.trades.push_back(Trade{p.side, p.entryTime, time, p.entry, p.stop, p.target,
            exit, p.quantity, p.entryFee + exitFee, net, net / p.riskBudget, equity, reason});
        peak = std::max(peak, equity);
        if (peak > 0) maxDd = std::max(maxDd, (peak - equity) / peak * 100.0);
    };

    for (std::size_t i = 1; i < bars.size() && equity > 0; ++i) {
        const auto& bar = bars[i];
        if (position) {
            auto p = *position;
            const auto direction = p.side == Side::buy ? 1.0 : -1.0;
            const bool stopHit = p.side == Side::buy ? bar.low <= p.stop : bar.high >= p.stop;
            const bool targetHit = p.side == Side::buy ? bar.high >= p.target : bar.low <= p.target;
            const double adverseExit = p.side == Side::buy ? bar.low : bar.high;
            const double mtm = equity + direction * (adverseExit - p.entry) * p.quantity - p.entryFee;
            if (peak > 0) maxDd = std::max(maxDd, (peak - mtm) / peak * 100.0);
            if (stopHit) {
                const double raw = p.side == Side::buy ? std::min(bar.open, p.stop) : std::max(bar.open, p.stop);
                closePosition(p, raw, bar.timeMs, equity + p.riskBudget <= 0 ? ExitReason::ruin : ExitReason::stopLoss);
                position.reset();
                hasPending = false;
                continue;
            }
            if (targetHit) {
                const double raw = p.side == Side::buy ? std::max(bar.open, p.target) : std::min(bar.open, p.target);
                closePosition(p, raw, bar.timeMs, ExitReason::takeProfit);
                position.reset();
                hasPending = false;
                continue;
            }
        }
        if (!position && hasPending) {
            const auto direction = pending == Side::buy ? 1.0 : -1.0;
            const auto entry = bar.open * (1.0 + direction * config.slippagePerSide);
            const auto& prev = bars[i - 1];
            const auto atr = indicators[i - 1].atr14;
            double extreme = prev.low;
            if (pending == Side::sell) extreme = prev.high;
            for (std::size_t j = i > 8 ? i - 8 : 0; j < i; ++j) {
                extreme = pending == Side::buy ? std::min(extreme, bars[j].low) : std::max(extreme, bars[j].high);
            }
            const auto stop = extreme - direction * 0.10 * atr;
            const auto riskDistance = direction * (entry - stop);
            if (riskDistance > 0) {
                const auto riskBudget = equity * config.riskFraction;
                const auto stopExit = stop * (1.0 - direction * config.slippagePerSide);
                const auto unitLoss = std::abs(entry - stopExit) + config.feePerSide * (entry + stopExit);
                const auto quantity = riskBudget / unitLoss;
                const auto entryFee = entry * quantity * config.feePerSide;
                position = Position{pending, bar.timeMs, entry, stop,
                    entry + direction * config.targetR * riskDistance, quantity, entryFee, riskBudget};
                const bool entryStopHit = pending == Side::buy ? bar.low <= stop : bar.high >= stop;
                const bool entryTargetHit = pending == Side::buy ? bar.high >= position->target : bar.low <= position->target;
                if (entryStopHit) {
                    closePosition(*position, stop, bar.timeMs, ExitReason::stopLoss);
                    position.reset();
                } else if (entryTargetHit) {
                    closePosition(*position, position->target, bar.timeMs, ExitReason::takeProfit);
                    position.reset();
                }
            }
            hasPending = false;
            continue;
        }
        if (position || i < 2 || !indicators[i - 1].valid || !indicators[i - 2].valid) continue;
        const auto& now = indicators[i - 1];
        const auto& before = indicators[i - 2];
        const auto& signalBar = bars[i - 1];
        const auto cloudHigh = std::max(now.spanA, now.spanB);
        const auto cloudLow = std::min(now.spanA, now.spanB);
        const bool crossedUp = before.tenkan <= before.kijun && now.tenkan > now.kijun;
        const bool crossedDown = before.tenkan >= before.kijun && now.tenkan < now.kijun;
        if (config.allowLong && crossedUp && signalBar.close > cloudHigh) {
            pending = Side::buy;
            hasPending = true;
        } else if (config.allowShort && crossedDown && signalBar.close < cloudLow) {
            pending = Side::sell;
            hasPending = true;
        }
    }
    if (position && equity > 0) closePosition(*position, bars.back().close, bars.back().timeMs, ExitReason::endOfData);
    result.stats = statistics(result.trades, config.startingEquity);
    result.stats.maxDrawdownPct = maxDd;
    result.stats.finalEquity = equity;
    result.stats.netPnl = equity - config.startingEquity;
    return result;
}

} // namespace aurum
