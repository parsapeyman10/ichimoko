#include "core/NeuralLab.h"

#include "core/Ichimoku.h"
#include "core/RiskEngine.h"

#include <algorithm>
#include <cmath>

namespace aurum {
namespace {
using Features = std::array<double, 16>;
double sigmoid(const double value) { return 1.0 / (1.0 + std::exp(-std::clamp(value, -20.0, 20.0))); }
double clampUnit(const double value) { return std::clamp(value, -1.0, 1.0); }
}

NeuralTrainingReport trainBacktestNeuralBaseline(const std::vector<Candle>& candles,
    const std::vector<Trade>& trades, const std::string& symbol) {
    NeuralTrainingReport report;
    if (candles.empty() || trades.empty()) return report;
    const auto points = ichimoku(candles);
    const auto asset = assetClassOf(symbol);
    std::vector<Features> samples;
    std::vector<int> targets;
    samples.reserve(trades.size());
    targets.reserve(trades.size());
    for (const auto& trade : trades) {
        const auto it = std::ranges::lower_bound(candles, trade.entryTimeMs, {}, &Candle::timeMs);
        if (it == candles.begin() || it == candles.end()) continue;
        const auto index = static_cast<std::size_t>(std::distance(candles.begin(), it));
        const auto& bar = candles[index];
        const auto& point = points[index];
        const auto erStart = index >= 14 ? index - 14 : 0;
        double path{};
        for (std::size_t i = erStart + 1; i <= index; ++i) path += std::abs(candles[i].close - candles[i - 1].close);
        const double er = path > 0.0 ? std::abs(bar.close - candles[erStart].close) / path : 0.0;
        const double range = std::max(1e-9, bar.high - bar.low);
        const double upperWick = (bar.high - std::max(bar.open, bar.close)) / range;
        const double lowerWick = (std::min(bar.open, bar.close) - bar.low) / range;
        const double rr = std::abs(trade.entry - trade.stop) > 1e-9 ? std::abs(trade.target - trade.entry) / std::abs(trade.entry - trade.stop) : 2.0;
        Features f{};
        f[0] = asset == AssetClass::crypto ? 1.0 : -1.0;
        f[1] = asset == AssetClass::commodity ? 1.0 : -1.0;
        f[2] = asset == AssetClass::forex ? 1.0 : -1.0;
        f[3] = asset == AssetClass::stock ? 1.0 : -1.0;
        f[4] = 1.0;
        f[5] = trade.side == Side::buy ? 1.0 : -1.0;
        f[6] = f[7] = f[8] = f[9] = -1.0;
        f[10] = clampUnit(er * 2.0 - 1.0);
        f[11] = point.valid && point.atr14 > 0.0 ? clampUnit(std::abs(point.spanA - point.spanB) / point.atr14 - 0.8) : 0.15;
        f[12] = point.valid && point.atr14 > 0.0 ? clampUnit((std::abs(bar.close - point.kijun) / point.atr14 - 1.2) / 1.5) : -0.20;
        f[13] = clampUnit(((trade.side == Side::buy ? upperWick : lowerWick) - 0.25) * 2.5);
        f[14] = clampUnit((std::max(rr, 1.5) - 2.0) / 1.5);
        f[15] = 0.0;
        samples.push_back(f);
        const bool win = trade.netPnl > 0.0;
        targets.push_back(win ? 1 : 0);
        win ? ++report.wins : ++report.losses;
        report.averageR += trade.rMultiple;
    }
    report.samples = samples.size();
    if (samples.empty()) return report;
    report.averageR /= static_cast<double>(samples.size());
    double bias{};
    constexpr double learningRate = 0.035;
    constexpr double weightDecay = 0.0005;
    for (int epoch = 0; epoch < 12; ++epoch) {
        for (std::size_t n = 0; n < samples.size(); ++n) {
            double logit = bias;
            for (std::size_t i = 0; i < report.weights.size(); ++i) logit += report.weights[i] * samples[n][i];
            const double error = sigmoid(logit) - static_cast<double>(targets[n]);
            for (std::size_t i = 0; i < report.weights.size(); ++i) {
                const double gradient = error * samples[n][i] + weightDecay * report.weights[i];
                report.weights[i] -= learningRate * gradient;
            }
            bias -= learningRate * error;
        }
    }
    std::size_t correct{};
    double probabilitySum{};
    for (std::size_t n = 0; n < samples.size(); ++n) {
        double logit = bias;
        for (std::size_t i = 0; i < report.weights.size(); ++i) logit += report.weights[i] * samples[n][i];
        const double probability = sigmoid(logit);
        probabilitySum += probability;
        if ((probability >= 0.5) == (targets[n] == 1)) ++correct;
    }
    report.predictedWinRatePct = probabilitySum * 100.0 / static_cast<double>(samples.size());
    report.inSampleAccuracyPct = static_cast<double>(correct) * 100.0 / static_cast<double>(samples.size());
    return report;
}

} // namespace aurum
