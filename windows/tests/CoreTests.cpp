#include "core/BacktestEngine.h"
#include "core/CsvReader.h"
#include "core/Ichimoku.h"
#include "core/Series.h"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

using namespace aurum;

namespace {

void require(const bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

void near(const double actual, const double expected, const double tolerance, const char* message) {
    require(std::abs(actual - expected) <= tolerance, message);
}

std::vector<Candle> bars(const std::size_t count) {
    std::vector<Candle> out;
    out.reserve(count);
    double price = 100.0;
    for (std::size_t i = 0; i < count; ++i) {
        const double wave = i < count / 2 ? 0.35 : -0.30;
        const double open = price;
        price += wave + (i % 7 == 0 ? 0.25 : -0.03);
        out.push_back(Candle{static_cast<std::int64_t>((i + 1) * 300'000), open,
            std::max(open, price) + 0.2, std::min(open, price) - 0.2, price, 10.0 + static_cast<double>(i), true});
    }
    return out;
}

void testClosedBoundary() {
    const auto input = bars(5);
    require(closedCountUpTo(input, Interval::m5, 900'000) == 2, "two bars must be closed at boundary");
    require(closedCountUpTo(input, Interval::m5, 899'999) == 1, "forming bar must remain invisible");
    const auto hourly = resample(input, Interval::h1);
    require(hourly.size() == 1 && !hourly.front().closed, "partial resample must not become closed");
}

void testCsv() {
    const std::string csv =
        "timestamp,open,high,low,close,volume\n"
        "300000,100,102,99,101,12\n"
        "300000,100,102,99,101,12\n"
        "600000,101,103,100,102,0\n"
        "900000,102,101,99,100,8\n";
    const auto result = parseDukascopyCsv(csv, "eurusd-m5-bid-test.csv", true);
    require(result.candles.size() == 1, "duplicate, zero-volume and invalid OHLC must be filtered");
    require(result.duplicateRows == 1 && result.flatRowsDropped == 1 && result.skippedRows == 1, "CSV counters");
    require(result.suggestedSymbol == "EUR/USD" && result.inferredInterval == Interval::m5, "CSV metadata");
}

void testIndicators() {
    const auto input = bars(100);
    const auto points = ichimoku(input);
    require(!points[70].valid && points[71].valid, "Ichimoku warmup must be exactly 72 bars");
    require(points[71].spanA > 0 && points[71].spanB > 0 && points[71].atr14 > 0, "indicator values");
}

void testBacktestDeterminism() {
    const auto input = bars(240);
    BacktestConfig config;
    config.feePerSide = 0.001;
    const auto first = runIchimokuBaseline(input, config);
    const auto second = runIchimokuBaseline(input, config);
    require(first.trades.size() == second.trades.size(), "trade count must be deterministic");
    near(first.stats.finalEquity, second.stats.finalEquity, 1e-9, "equity must be deterministic");
    require(first.stats.maxDrawdownPct >= 0 && first.stats.maxDrawdownPct <= 1000, "drawdown must be real equity percentage");
    require(first.stats.finalEquity > 0, "test account must respect ruin handling");
    for (const auto& trade : first.trades) {
        require(trade.entryTimeMs <= trade.exitTimeMs && trade.quantity > 0 && trade.fees >= 0, "valid trade ledger");
    }
}

} // namespace

int main() {
    try {
        testClosedBoundary();
        testCsv();
        testIndicators();
        testBacktestDeterminism();
        std::cout << "All Aurum core tests passed\n";
        return EXIT_SUCCESS;
    } catch (const std::exception& error) {
        std::cerr << "TEST FAILURE: " << error.what() << '\n';
        return EXIT_FAILURE;
    }
}
