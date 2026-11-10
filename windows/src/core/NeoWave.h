#pragma once

#include "core/Candle.h"

#include <optional>
#include <string>
#include <vector>

namespace aurum {

struct NeoWavePivot {
    int index{};
    std::int64_t timeMs{};
    double price{};
    bool isHigh{};
};

struct NeoWaveVerdict {
    bool usable{};
    bool complete{};
    bool confirmed{};
    bool hardBreach{};
    int expectedDirection{}; // +1 buy, -1 sell, 0 none
    int rulesPassed{};
    int rulesTotal{};
    double confidencePct{};
    std::string badge;
};

struct NeoWaveGate {
    bool veto{};
    double confidenceDelta{};
    std::string note;
};

struct MtfConfluence {
    double confidenceDelta{};
    bool veto{};
    int alignedCount{};
    std::string note;
};

[[nodiscard]] double neoWaveAtr(const std::vector<Candle>& candles, int period = 14);
[[nodiscard]] std::vector<NeoWavePivot> neoWavePivots(const std::vector<Candle>& candles,
    double amplitudeAtr = 0.55, int window = 3);
[[nodiscard]] double neoWaveDynamicAmplitude(const std::vector<Candle>& candles, double baseAmplitude = 0.55);
[[nodiscard]] int neoWaveRetracementRule(double ratio) noexcept;
[[nodiscard]] NeoWaveGate neoWaveGate(const std::optional<NeoWaveVerdict>& verdict, bool isLong,
    int minRulesPassed = 6, double minConfidencePct = 55.0);
[[nodiscard]] MtfConfluence neoWaveMtfConfluence(const std::optional<NeoWaveVerdict>& strategy,
    const std::optional<NeoWaveVerdict>& higher, bool isLong);

} // namespace aurum
