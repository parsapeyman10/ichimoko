#pragma once

#include "core/Candle.h"

#include <cstddef>
#include <string>
#include <vector>

namespace aurum {

enum class Side : std::int8_t { buy = 1, sell = -1 };
enum class ExitReason : std::uint8_t { stopLoss, takeProfit, endOfData, ruin };

struct Trade final {
    Side side{Side::buy};
    std::int64_t entryTimeMs{};
    std::int64_t exitTimeMs{};
    double entry{};
    double stop{};
    double target{};
    double exit{};
    double quantity{};
    double fees{};
    double netPnl{};
    double rMultiple{};
    double equityAfter{};
    ExitReason reason{ExitReason::endOfData};
};

struct BacktestConfig final {
    double startingEquity{1000.0};
    double riskFraction{0.0025};
    double feePerSide{0.001};
    double slippagePerSide{0.0001};
    double targetR{2.0};
    bool allowLong{true};
    bool allowShort{true};
};

struct BacktestStats final {
    std::size_t trades{};
    std::size_t wins{};
    std::size_t losses{};
    double winRate{};
    double netPnl{};
    double profitFactor{};
    double expectancyR{};
    double finalEquity{};
    double maxDrawdownPct{};
    double totalFees{};
};

struct BacktestResult final {
    std::vector<Trade> trades;
    BacktestStats stats;
    std::string modelName{"Ichimoku 8/24/72 TK-cross baseline (not Peyman parity)"};
};

// Deterministic next-open execution. Same-bar SL/TP collision resolves to SL.
[[nodiscard]] BacktestResult runIchimokuBaseline(
    const std::vector<Candle>& bars,
    const BacktestConfig& config = {});

} // namespace aurum
