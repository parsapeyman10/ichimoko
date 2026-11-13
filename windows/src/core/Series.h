#pragma once

#include "core/Candle.h"
#include "core/Interval.h"

#include <cstddef>
#include <vector>

namespace aurum {

[[nodiscard]] std::vector<Candle> resample(const std::vector<Candle>& input, Interval target);

// Binary-search boundary: bars with time + size <= now are visible; all others are future.
[[nodiscard]] std::size_t closedCountUpTo(
    const std::vector<Candle>& ordered,
    Interval source,
    std::int64_t nowMs) noexcept;

[[nodiscard]] std::int64_t dominantStepMs(const std::vector<Candle>& ordered) noexcept;

} // namespace aurum
