#pragma once

#include <cstdint>

namespace aurum {

struct Candle final {
    std::int64_t timeMs{};
    double open{};
    double high{};
    double low{};
    double close{};
    double volume{};
    bool closed{true};

    [[nodiscard]] bool valid() const noexcept {
        return timeMs > 0 && open > 0.0 && close > 0.0 && high >= low &&
            open <= high && open >= low && close <= high && close >= low && volume >= 0.0;
    }
};

} // namespace aurum
