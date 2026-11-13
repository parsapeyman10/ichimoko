#pragma once

#include "core/Candle.h"
#include "core/Interval.h"

#include <cstddef>
#include <string>
#include <string_view>
#include <vector>

namespace aurum {

enum class SourceKind : std::uint8_t { candles, ticks };

struct ParseResult final {
    std::vector<Candle> candles;
    SourceKind kind{SourceKind::candles};
    std::size_t totalRows{};
    std::size_t skippedRows{};
    std::size_t duplicateRows{};
    std::size_t flatRowsDropped{};
    Interval inferredInterval{Interval::m15};
    std::int64_t dominantStepMs{};
    std::string suggestedSymbol;
};

// One-pass, allocation-bounded parser for dukascopy-node candle/tick CSV.
// Throws std::invalid_argument for an unknown/empty schema or no valid rows.
[[nodiscard]] ParseResult parseDukascopyCsv(
    std::string_view raw,
    std::string_view fileName = {},
    bool dropFlatBars = true);

} // namespace aurum
