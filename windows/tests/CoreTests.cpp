#include "core/BacktestEngine.h"
#include "core/CsvReader.h"
#include "core/Ichimoku.h"
#include "core/Series.h"
#include "core/RiskEngine.h"
#include "core/NeoWave.h"
#include "core/NeuralLab.h"

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

void testRiskEngine() {
    require(assetClassOf("btc/usdt") == AssetClass::crypto, "crypto classification");
    require(assetClassOf("EUR/USD") == AssetClass::forex, "forex classification");
    require(assetClassOf("XAU/USD") == AssetClass::commodity, "gold classification");
    const auto gold = calculatePosition("XAU/USD", 10'000.0, 0.5, 2'000.0, 1'990.0, 2'020.0);
    require(gold.has_value(), "valid gold risk plan");
    near(gold->riskBudgetUsd, 50.0, 1e-9, "fixed fractional risk budget");
    near(gold->positionUnits, 5.0, 1e-9, "gold ounces from stop distance");
    near(gold->standardLots, 0.05, 1e-9, "gold standard lots");
    near(gold->riskReward, 2.0, 1e-9, "risk reward");
    require(gold->leverage >= 1 && gold->leverage <= 10 && gold->liquidationBufferR >= 2.5,
        "liquidation buffer must constrain leverage");
    require(!calculatePosition("BTC/USDT", 10'000.0, 1.0, 100.0, 101.0, 102.0), "direction mismatch rejected");
    const auto usdJpy = calculatePosition("USD/JPY", 10'000.0, 1.0, 150.0, 149.5, 151.0);
    require(usdJpy.has_value(), "USD/JPY plan");
    near(usdJpy->positionUnits, 30'000.0, 1e-6, "JPY quote PnL converted to USD");
    near(enforceRiskReward(100.0, 99.0, 110.0, OrderSide::buy), 103.0, 1e-9, "RR cap");
    near(minSafeStructuralStopDistance("BTC/USDT", 100'000.0, 500.0, 500.0, true), 410.0, 1e-9, "Peyman structural ATR floor");
    near(minSafeStructuralStopDistance("EUR/USD", 1.10, 0.0002, 0.0002, true), 0.00044, 1e-12, "Peyman forex floor");
}

void testNeoWave() {
    const auto input = bars(240);
    require(neoWaveAtr(input) > 0.0, "NeoWave ATR");
    require(neoWavePivots(bars(20)).empty(), "NeoWave minimum bars");
    const auto pivots = neoWavePivots(input);
    require(pivots.size() >= 2, "ATR-filtered swing pivots");
    for (std::size_t i = 1; i < pivots.size(); ++i)
        require(pivots[i].index > pivots[i - 1].index && pivots[i].isHigh != pivots[i - 1].isHigh, "ordered alternating pivots");
    require(neoWaveRetracementRule(0.3) == 1 && neoWaveRetracementRule(0.62) == 3 &&
        neoWaveRetracementRule(1.2) == 5 && neoWaveRetracementRule(3.0) == 7, "Neely retracement boundaries");
    const double amplitude = neoWaveDynamicAmplitude(input);
    require(amplitude >= 0.35 && amplitude <= 1.2, "dynamic amplitude bounds");
    NeoWaveVerdict aligned;
    aligned.usable = aligned.complete = aligned.confirmed = true;
    aligned.expectedDirection = 1; aligned.rulesPassed = 8; aligned.rulesTotal = 10;
    aligned.confidencePct = 72.0; aligned.badge = "test";
    require(!neoWaveGate(aligned, true).veto && neoWaveGate(aligned, true).confidenceDelta == 8.0, "aligned NeoWave gate");
    require(neoWaveGate(aligned, false).veto, "opposing confirmed NeoWave veto");
    const auto confluence = neoWaveMtfConfluence(aligned, aligned, true);
    require(confluence.alignedCount == 2 && confluence.confidenceDelta == 5.0, "two-timeframe confluence");
    const std::vector<double> prices{100,110,105,125,115,140,130,125};
    std::vector<Candle> waveBars;
    for (std::size_t leg = 0; leg + 1 < prices.size(); ++leg) {
        for (int step = 0; step < 10; ++step) {
            const double close = prices[leg] + (prices[leg + 1] - prices[leg]) * (step + 1) / 10.0;
            const double open = step == 0 ? prices[leg] : waveBars.back().close;
            waveBars.push_back(Candle{static_cast<std::int64_t>((waveBars.size() + 1) * 300000), open,
                std::max(open, close) + 0.1, std::min(open, close) - 0.1, close, 10.0, true});
        }
    }
    const auto analysis = analyzeNeoWave(waveBars);
    require(analysis.has_value(), "known five-wave fixture must produce a structural verdict");
    require(analysis->usable && !analysis->hardBreach && analysis->rulesTotal >= 5, "NeoWave rule audit");
    require(analysis->monowaveCount >= 3 && analysis->structureLabel.size() > 1, "NeoWave structural label");
    require(analyzeNeoWave(waveBars)->summary == analysis->summary, "deterministic NeoWave analysis");
}

void testNeuralLab() {
    const auto input = bars(240);
    const auto backtest = runIchimokuBaseline(input);
    const auto first = trainBacktestNeuralBaseline(input, backtest.trades, "BTC/USDT");
    const auto second = trainBacktestNeuralBaseline(input, backtest.trades, "BTC/USDT");
    require(first.samples == second.samples && first.weights == second.weights, "deterministic isolated neural training");
    require(first.samples == first.wins + first.losses, "closed training sample accounting");
    if (first.samples > 0) {
        require(first.inSampleAccuracyPct >= 0.0 && first.inSampleAccuracyPct <= 100.0, "accuracy range");
        require(first.predictedWinRatePct >= 0.0 && first.predictedWinRatePct <= 100.0, "probability range");
    }
    const auto before = backtest.stats.finalEquity;
    (void)trainBacktestNeuralBaseline(input, backtest.trades, "BTC/USDT");
    near(backtest.stats.finalEquity, before, 0.0, "training must not mutate backtest result");
}

} // namespace

int main() {
    try {
        testClosedBoundary();
        testCsv();
        testIndicators();
        testBacktestDeterminism();
        testRiskEngine();
        testNeoWave();
        testNeuralLab();
        std::cout << "All Aurum core tests passed\n";
        return EXIT_SUCCESS;
    } catch (const std::exception& error) {
        std::cerr << "TEST FAILURE: " << error.what() << '\n';
        return EXIT_FAILURE;
    }
}
