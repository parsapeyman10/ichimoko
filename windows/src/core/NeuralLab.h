#pragma once

#include "core/BacktestEngine.h"

#include <array>
#include <cstddef>
#include <string>
#include <vector>

namespace aurum {

struct NeuralTrainingReport {
    std::size_t samples{};
    std::size_t wins{};
    std::size_t losses{};
    double averageR{};
    double predictedWinRatePct{};
    double inSampleAccuracyPct{};
    std::array<double, 16> weights{};
    std::string note{"آزمایشگاه جداگانهٔ Baseline؛ دقت In-sample، نه آزمون Out-of-sample و نه Peyman/Neural Android parity."};
};

// Deterministic logistic baseline for the isolated backtest training room.
// It never changes backtest results, live journal, or strategy parameters.
[[nodiscard]] NeuralTrainingReport trainBacktestNeuralBaseline(const std::vector<Candle>& candles,
    const std::vector<Trade>& trades, const std::string& symbol);

} // namespace aurum
