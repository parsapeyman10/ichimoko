#pragma once

#include "core/Candle.h"

#include <cstddef>
#include <vector>

namespace aurum {

struct IchimokuPoint final {
    double tenkan{};
    double kijun{};
    double spanA{};
    double spanB{};
    double atr14{};
    bool valid{};
};

// Linear-time rolling indicators, with no future values in any point.
[[nodiscard]] std::vector<IchimokuPoint> ichimoku(
    const std::vector<Candle>& bars,
    std::size_t tenkanPeriod = 8,
    std::size_t kijunPeriod = 24,
    std::size_t spanBPeriod = 72);

} // namespace aurum
