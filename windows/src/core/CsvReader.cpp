#include "core/CsvReader.h"
#include "core/Series.h"

#include <algorithm>
#include <array>
#include <charconv>
#include <cctype>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <unordered_map>
#include <unordered_set>

namespace aurum {
namespace {

std::string lower(std::string_view value) {
    std::string out(value);
    std::ranges::transform(out, out.begin(), [](const unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return out;
}

std::vector<std::string_view> splitLine(const std::string_view line) {
    std::vector<std::string_view> cells;
    std::size_t begin = 0;
    bool quoted = false;
    for (std::size_t i = 0; i <= line.size(); ++i) {
        if (i < line.size() && line[i] == '"') quoted = !quoted;
        if (i == line.size() || (line[i] == ',' && !quoted)) {
            auto cell = line.substr(begin, i - begin);
            while (!cell.empty() && (cell.front() == ' ' || cell.front() == '\t' || cell.front() == '\r')) cell.remove_prefix(1);
            while (!cell.empty() && (cell.back() == ' ' || cell.back() == '\t' || cell.back() == '\r')) cell.remove_suffix(1);
            if (cell.size() >= 2 && cell.front() == '"' && cell.back() == '"') {
                cell.remove_prefix(1);
                cell.remove_suffix(1);
            }
            cells.push_back(cell);
            begin = i + 1;
        }
    }
    return cells;
}

double number(const std::vector<std::string_view>& cells, const int index, const double fallback = std::numeric_limits<double>::quiet_NaN()) {
    if (index < 0 || static_cast<std::size_t>(index) >= cells.size()) return fallback;
    const auto text = cells[static_cast<std::size_t>(index)];
    double result{};
    const auto* first = text.data();
    const auto* last = first + text.size();
    const auto parsed = std::from_chars(first, last, result, std::chars_format::general);
    return parsed.ec == std::errc{} && parsed.ptr == last && std::isfinite(result) ? result : fallback;
}

std::int64_t timestamp(const std::vector<std::string_view>& cells, const int index) {
    const double value = number(cells, index);
    if (!std::isfinite(value) || value <= 0) return 0;
    // Epoch seconds are accepted as a convenience; normal Dukascopy values are milliseconds.
    return static_cast<std::int64_t>(value < 10'000'000'000.0 ? value * 1000.0 : value);
}

std::string symbolFromName(std::string_view fileName) {
    const auto dash = fileName.find('-');
    const auto head = lower(fileName.substr(0, dash));
    if (head.size() != 6 || !std::ranges::all_of(head, [](const unsigned char c) { return std::isalpha(c) != 0; })) return {};
    auto out = head;
    std::ranges::transform(out, out.begin(), [](const unsigned char c) { return static_cast<char>(std::toupper(c)); });
    return out.substr(0, 3) + "/" + out.substr(3);
}

Interval intervalFromName(std::string_view fileName, const Interval fallback) {
    static const std::unordered_map<std::string, Interval> tags{
        {"m1", Interval::m1}, {"m3", Interval::m3}, {"m5", Interval::m5},
        {"m15", Interval::m15}, {"m30", Interval::m30}, {"h1", Interval::h1},
        {"h4", Interval::h4}, {"d1", Interval::d1},
    };
    const auto name = lower(fileName);
    for (const auto& [tag, interval] : tags) {
        if (name.find("-" + tag + "-") != std::string::npos) return interval;
    }
    return fallback;
}

} // namespace

ParseResult parseDukascopyCsv(const std::string_view raw, const std::string_view fileName, const bool dropFlatBars) {
    if (raw.empty()) throw std::invalid_argument("CSV is empty");
    const auto firstEnd = raw.find('\n');
    const auto headerLine = raw.substr(0, firstEnd);
    const auto header = splitLine(headerLine);
    std::unordered_map<std::string, int> columns;
    for (std::size_t i = 0; i < header.size(); ++i) columns[lower(header[i])] = static_cast<int>(i);
    const auto find = [&columns](const std::string& key) -> int {
        const auto it = columns.find(key);
        return it == columns.end() ? -1 : it->second;
    };
    const int ts = columns.contains("timestamp") ? find("timestamp") : find("time");
    const bool isCandles = find("open") >= 0 && find("close") >= 0;
    const bool isTicks = find("askprice") >= 0 || find("bidprice") >= 0;
    if (ts < 0 || (!isCandles && !isTicks)) throw std::invalid_argument("Unsupported Dukascopy CSV header");

    ParseResult result;
    result.kind = isCandles ? SourceKind::candles : SourceKind::ticks;
    std::unordered_set<std::int64_t> seen;
    std::size_t cursor = firstEnd == std::string_view::npos ? raw.size() : firstEnd + 1;
    while (cursor < raw.size()) {
        const auto end = raw.find('\n', cursor);
        const auto line = raw.substr(cursor, end == std::string_view::npos ? raw.size() - cursor : end - cursor);
        cursor = end == std::string_view::npos ? raw.size() : end + 1;
        if (line.empty() || line == "\r") continue;
        ++result.totalRows;
        const auto cells = splitLine(line);
        const auto time = timestamp(cells, ts);
        Candle bar{};
        bool valid = time > 0;
        if (valid && isCandles) {
            bar = Candle{time, number(cells, find("open")), number(cells, find("high")),
                number(cells, find("low")), number(cells, find("close")), number(cells, find("volume"), 0.0), true};
            valid = bar.valid();
        } else if (valid) {
            const auto ask = number(cells, find("askprice"));
            const auto bid = number(cells, find("bidprice"));
            const double mid = ask > 0.0 && bid > 0.0 ? (ask + bid) / 2.0 : std::max(ask, bid);
            const auto av = number(cells, find("askvolume"), 0.0);
            const auto bv = number(cells, find("bidvolume"), 0.0);
            bar = Candle{time, mid, mid, mid, mid, av + bv, true};
            valid = bar.valid();
        }
        if (!valid) {
            ++result.skippedRows;
            continue;
        }
        if (!seen.insert(time).second) {
            ++result.duplicateRows;
            continue;
        }
        result.candles.push_back(bar);
    }
    if (result.candles.empty()) throw std::invalid_argument("No valid CSV rows");
    std::ranges::stable_sort(result.candles, [](const auto& a, const auto& b) { return a.timeMs < b.timeMs; });
    if (dropFlatBars && find("volume") >= 0) {
        const auto oldSize = result.candles.size();
        const auto [first, last] = std::ranges::remove_if(result.candles, [](const auto& bar) { return bar.volume <= 0.0; });
        result.candles.erase(first, last);
        result.flatRowsDropped = oldSize - result.candles.size();
        if (result.candles.empty()) throw std::invalid_argument("All rows have zero volume");
    }
    result.dominantStepMs = aurum::dominantStepMs(result.candles);
    result.inferredInterval = intervalFromName(fileName, nearestInterval(result.dominantStepMs));
    result.suggestedSymbol = symbolFromName(fileName);
    return result;
}

} // namespace aurum
