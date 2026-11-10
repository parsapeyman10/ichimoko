#pragma once

#include <cstdint>
#include <string_view>

namespace aurum {

enum class Interval : std::uint8_t { m1, m3, m5, m15, m30, h1, h4, d1 };

[[nodiscard]] constexpr std::int64_t millis(Interval value) noexcept {
    constexpr std::int64_t minute = 60'000;
    switch (value) {
        case Interval::m1: return minute;
        case Interval::m3: return 3 * minute;
        case Interval::m5: return 5 * minute;
        case Interval::m15: return 15 * minute;
        case Interval::m30: return 30 * minute;
        case Interval::h1: return 60 * minute;
        case Interval::h4: return 240 * minute;
        case Interval::d1: return 1'440 * minute;
    }
    return 5 * minute;
}

[[nodiscard]] constexpr std::string_view label(Interval value) noexcept {
    switch (value) {
        case Interval::m1: return "1m";
        case Interval::m3: return "3m";
        case Interval::m5: return "5m";
        case Interval::m15: return "15m";
        case Interval::m30: return "30m";
        case Interval::h1: return "1H";
        case Interval::h4: return "4H";
        case Interval::d1: return "1D";
    }
    return "5m";
}

[[nodiscard]] Interval nearestInterval(std::int64_t stepMs) noexcept;

} // namespace aurum
