#include "core/Series.h"

#include <algorithm>
#include <array>
#include <cstdlib>
#include <map>

namespace aurum {

Interval nearestInterval(const std::int64_t stepMs) noexcept {
    constexpr std::array values{Interval::m1, Interval::m3, Interval::m5, Interval::m15,
        Interval::m30, Interval::h1, Interval::h4, Interval::d1};
    return *std::ranges::min_element(values, [stepMs](const auto a, const auto b) {
        return std::llabs(millis(a) - stepMs) < std::llabs(millis(b) - stepMs);
    });
}

std::vector<Candle> resample(const std::vector<Candle>& input, const Interval target) {
    std::vector<Candle> output;
    if (input.empty()) return output;
    output.reserve(input.size() / 2 + 1);
    const auto size = millis(target);
    const auto sourceStep = dominantStepMs(input);
    Candle current{};
    bool started = false;
    for (const auto& bar : input) {
        const auto bucket = bar.timeMs - ((bar.timeMs % size) + size) % size;
        if (!started || bucket != current.timeMs) {
            if (started) output.push_back(current);
            current = Candle{bucket, bar.open, bar.high, bar.low, bar.close, bar.volume, false};
            started = true;
        } else {
            current.high = std::max(current.high, bar.high);
            current.low = std::min(current.low, bar.low);
            current.close = bar.close;
            current.volume += bar.volume;
        }
        // A bucket is closed only when all source bars through its end are present.
        current.closed = sourceStep > 0 && bar.timeMs + sourceStep >= current.timeMs + size;
    }
    if (started) output.push_back(current);
    return output;
}

std::size_t closedCountUpTo(
    const std::vector<Candle>& ordered,
    const Interval source,
    const std::int64_t nowMs) noexcept {
    const auto size = millis(source);
    const auto it = std::ranges::upper_bound(ordered, nowMs,
        [](const auto now, const auto closeTime) { return now < closeTime; },
        [size](const auto& bar) { return bar.timeMs + size; });
    return static_cast<std::size_t>(std::distance(ordered.begin(), it));
}

std::int64_t dominantStepMs(const std::vector<Candle>& ordered) noexcept {
    if (ordered.size() < 2) return 0;
    std::map<std::int64_t, std::size_t> counts;
    for (std::size_t i = 1; i < ordered.size(); ++i) {
        const auto delta = ordered[i].timeMs - ordered[i - 1].timeMs;
        if (delta > 0) ++counts[delta];
    }
    if (counts.empty()) return 0;
    return std::ranges::max_element(counts, [](const auto& a, const auto& b) {
        return a.second < b.second;
    })->first;
}

} // namespace aurum
