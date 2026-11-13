#include "core/NeoWave.h"

#include <algorithm>
#include <cmath>
#include <iterator>

namespace aurum {
namespace {
std::vector<Candle> closedBars(const std::vector<Candle>& input) {
    std::vector<Candle> bars;
    std::ranges::copy_if(input, std::back_inserter(bars), [](const Candle& c) { return c.closed; });
    if (bars.empty()) bars = input;
    if (bars.size() > 240) bars.erase(bars.begin(), bars.end() - 240);
    return bars;
}
bool aligned(const NeoWaveVerdict& verdict, const bool isLong) {
    return isLong ? verdict.expectedDirection > 0 : verdict.expectedDirection < 0;
}
}

double neoWaveAtr(const std::vector<Candle>& candles, const int period) {
    if (candles.empty()) return 0.0;
    if (candles.size() == 1) return std::max(0.0, candles[0].high - candles[0].low);
    const int p = std::clamp(period, 2, 50);
    const auto start = std::max<std::size_t>(1, candles.size() - static_cast<std::size_t>(p));
    double sum{};
    int count{};
    for (std::size_t i = start; i < candles.size(); ++i) {
        const double previous = candles[i - 1].close;
        const double tr = std::max(candles[i].high - candles[i].low,
            std::max(std::abs(candles[i].high - previous), std::abs(candles[i].low - previous)));
        if (std::isfinite(tr) && tr >= 0.0) { sum += tr; ++count; }
    }
    return count == 0 ? 0.0 : sum / count;
}

std::vector<NeoWavePivot> neoWavePivots(const std::vector<Candle>& candles,
    const double amplitudeAtr, const int window) {
    const auto bars = closedBars(candles);
    if (bars.size() < 45) return {};
    const double threshold = std::max(neoWaveAtr(bars) * std::clamp(amplitudeAtr, 0.20, 2.00), bars.back().close * 0.0005);
    if (!std::isfinite(threshold) || threshold <= 0.0) return {};
    const int w = std::clamp(window, 1, 5);
    std::vector<NeoWavePivot> raw;
    for (std::size_t i = static_cast<std::size_t>(w); i + static_cast<std::size_t>(w) < bars.size(); ++i) {
        bool high = true, low = true;
        for (std::size_t j = i - static_cast<std::size_t>(w); j <= i + static_cast<std::size_t>(w); ++j) {
            if (j == i) continue;
            if (bars[j].high > bars[i].high) high = false;
            if (bars[j].low < bars[i].low) low = false;
            if (!high && !low) break;
        }
        if (high) raw.push_back({static_cast<int>(i), bars[i].timeMs, bars[i].high, true});
        if (low) raw.push_back({static_cast<int>(i), bars[i].timeMs, bars[i].low, false});
    }
    std::ranges::stable_sort(raw, {}, &NeoWavePivot::index);
    std::vector<NeoWavePivot> merged;
    for (const auto& pivot : raw) {
        if (merged.empty() || merged.back().isHigh != pivot.isHigh) merged.push_back(pivot);
        else if (pivot.isHigh ? pivot.price >= merged.back().price : pivot.price <= merged.back().price) merged.back() = pivot;
    }
    std::vector<NeoWavePivot> cleaned;
    for (const auto& pivot : merged) {
        if (cleaned.empty()) { cleaned.push_back(pivot); continue; }
        if (std::abs(pivot.price - cleaned.back().price) < threshold) {
            if (pivot.isHigh && cleaned.back().isHigh && pivot.price > cleaned.back().price) cleaned.back() = pivot;
            else if (!pivot.isHigh && !cleaned.back().isHigh && pivot.price < cleaned.back().price) cleaned.back() = pivot;
        } else cleaned.push_back(pivot);
    }
    std::vector<NeoWavePivot> result;
    for (const auto& pivot : cleaned) {
        if (!result.empty() && result.back().isHigh == pivot.isHigh) {
            if (pivot.isHigh ? pivot.price >= result.back().price : pivot.price <= result.back().price) result.back() = pivot;
        } else result.push_back(pivot);
    }
    if (!result.empty() && result.front().index > 1) {
        const auto& first = result.front();
        int index{};
        double price = first.isHigh ? bars[0].low : bars[0].high;
        for (int i = 0; i < first.index; ++i) {
            if (first.isHigh ? bars[static_cast<std::size_t>(i)].low <= price : bars[static_cast<std::size_t>(i)].high >= price) {
                price = first.isHigh ? bars[static_cast<std::size_t>(i)].low : bars[static_cast<std::size_t>(i)].high;
                index = i;
            }
        }
        if (std::abs(price - first.price) >= threshold) result.insert(result.begin(), {index, bars[static_cast<std::size_t>(index)].timeMs, price, !first.isHigh});
    }
    return result;
}

double neoWaveDynamicAmplitude(const std::vector<Candle>& candles, const double baseAmplitude) {
    const auto bars = closedBars(candles);
    if (bars.size() < 60) return std::clamp(baseAmplitude, 0.35, 1.2);
    const auto tail = [&](const std::size_t count) {
        return std::vector<Candle>(bars.end() - static_cast<std::ptrdiff_t>(std::min(count, bars.size())), bars.end());
    };
    const double shortAtr = neoWaveAtr(tail(40));
    const double longAtr = neoWaveAtr(tail(160));
    if (shortAtr <= 0.0 || longAtr <= 0.0) return std::clamp(baseAmplitude, 0.35, 1.2);
    return std::clamp(baseAmplitude * std::clamp(shortAtr / longAtr, 0.85, 1.20), 0.35, 1.2);
}

int neoWaveRetracementRule(const double ratio) noexcept {
    if (ratio < 0.382) return 1;
    if (ratio < 0.618) return 2;
    if (ratio < 0.66) return 3;
    if (ratio < 1.0) return 4;
    if (ratio < 1.618) return 5;
    if (ratio <= 2.618) return 6;
    return 7;
}

NeoWaveGate neoWaveGate(const std::optional<NeoWaveVerdict>& verdict, const bool isLong,
    const int minRulesPassed, const double minConfidencePct) {
    if (!verdict || !verdict->usable || verdict->hardBreach || verdict->rulesTotal <= 0)
        return {false, 0.0, "ساختار نئوویو معتبر نیست؛ داور خنثی است."};
    const bool strong = verdict->rulesPassed >= std::clamp(minRulesPassed, 1, 12) &&
        verdict->confidencePct >= std::clamp(minConfidencePct, 35.0, 95.0);
    const bool isAligned = aligned(*verdict, isLong);
    if (verdict->complete && isAligned && strong && verdict->confirmed) return {false, 8.0, "نئوویو تأییدشده و هم‌جهت است."};
    if (verdict->complete && isAligned) return {false, 2.0, "نئوویو هم‌جهت است اما تأیید کافی ندارد."};
    if (verdict->complete && strong && verdict->confirmed) return {true, -12.0, "الگوی تأییدشدهٔ نئوویو مخالف ورود است."};
    if (verdict->complete) return {false, -3.0, "نئوویو مخالف است اما تأیید کافی ندارد."};
    return {false, isAligned ? 3.0 : -4.0, isAligned ? "الگوی در حال تکمیل هم‌جهت است." : "الگوی در حال تکمیل مخالف است."};
}

MtfConfluence neoWaveMtfConfluence(const std::optional<NeoWaveVerdict>& strategy,
    const std::optional<NeoWaveVerdict>& higher, const bool isLong) {
    if (!higher || !higher->usable || higher->hardBreach) return {0.0, false, 0, "ساختار تایم‌فریم بالاتر در دسترس نیست."};
    const bool higherAligned = aligned(*higher, isLong);
    const bool higherConfirmed = higher->complete && higher->confirmed;
    if (higherConfirmed && !higherAligned) return {-6.0, true, 0, "الگوی تأییدشدهٔ تایم‌فریم بالاتر مخالف ورود است."};
    double delta{};
    int count{};
    if (higherConfirmed && higherAligned) { delta += 3.0; ++count; }
    else if (higherAligned) delta += 1.0;
    if (strategy && strategy->usable && !strategy->hardBreach && strategy->complete && strategy->confirmed && aligned(*strategy, isLong)) {
        if (count > 0) delta += 2.0;
        ++count;
    }
    return {delta, false, count, count >= 2 ? "هم‌گرایی دو تایم‌فریمی شکل گرفت." : count == 1 ? "یک تایم‌فریم هم‌جهت است." : "هم‌گرایی هنوز شکل نگرفته است."};
}

} // namespace aurum
