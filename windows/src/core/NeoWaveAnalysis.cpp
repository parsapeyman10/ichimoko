#include "core/NeoWave.h"

#include <algorithm>
#include <array>
#include <iterator>
#include <cmath>
#include <numeric>

namespace aurum {
namespace {

struct Mono { NeoWavePivot from, to; double length{}; int bars{}, direction{}; };
struct Rule { bool passed{}, hard{}; double weight{1.0}; };
struct Candidate {
    std::string pattern;
    std::vector<Rule> rules;
    int expectedDirection{};
    bool complete{};
    std::optional<double> invalidation, target;
    double height{};
    int priority{}, recency{}, retracement{};
    int passed() const { return static_cast<int>(std::ranges::count_if(rules, [](const Rule& r) { return r.passed; })); }
    bool hardBreach() const { return std::ranges::any_of(rules, [](const Rule& r) { return r.hard && !r.passed; }); }
    double ratio() const {
        const double total = std::accumulate(rules.begin(), rules.end(), 0.0, [](double s, const Rule& r) { return s + r.weight; });
        const double got = std::accumulate(rules.begin(), rules.end(), 0.0, [](double s, const Rule& r) { return s + (r.passed ? r.weight : 0.0); });
        return total > 0.0 ? got / total : 0.0;
    }
};
using Waves = std::vector<Mono>;
Rule rule(const bool passed, const double weight = 1.0, const bool hard = false) { return {passed, hard, weight}; }
double symmetry(const double a, const double b) { const double lo = std::min(a, b); return lo <= 0.0 ? 99.0 : std::max(a, b) / lo; }
std::optional<Waves> window(const Waves& waves, const int end, const int count) {
    if (end - count < 0 || end > static_cast<int>(waves.size())) return std::nullopt;
    return Waves(waves.begin() + end - count, waves.begin() + end);
}
int expectedAfterCorrection(const Waves& waves, const int end, const int count, const int fallback) {
    const int start = end - count;
    const int direction = start > 0 ? waves[static_cast<std::size_t>(start - 1)].direction : -fallback;
    return direction > 0 ? 1 : -1;
}
Waves monowaves(const std::vector<NeoWavePivot>& pivots) {
    Waves out;
    for (std::size_t i = 1; i < pivots.size(); ++i) {
        const auto& a = pivots[i - 1]; const auto& b = pivots[i];
        const double length = std::abs(b.price - a.price);
        if (length <= 0.0) continue;
        out.push_back({a, b, length, std::max(1, b.index - a.index), b.price > a.price ? 1 : -1});
    }
    return out;
}
int channelTouches(const Waves& m) {
    const double p1 = m[0].from.price, p2 = m[0].to.price, p3 = m[2].to.price;
    if (p2 == p1) return 6;
    const double slope = (p3 - p1) / 4.0, tolerance = std::abs(p3 - p1) * 0.05;
    const std::array prices{p1, p2, m[1].to.price, p3, m[3].to.price, m[4].to.price};
    int touches{};
    for (std::size_t i = 0; i < prices.size(); ++i) if (std::abs(prices[i] - (p1 + slope * static_cast<double>(i))) <= tolerance) ++touches;
    return touches;
}
std::optional<Candidate> impulseComplete(const Waves& waves, const int end, const int recency) {
    const auto optional = window(waves, end, 5); if (!optional) return std::nullopt; const auto& m = *optional;
    const int d = m[0].direction;
    if (d == 0 || m[1].direction != -d || m[2].direction != d || m[3].direction != -d || m[4].direction != d) return std::nullopt;
    const double r2 = m[1].length / m[0].length, r4 = m[3].length / m[2].length;
    const std::array drivers{m[0].length, m[2].length, m[4].length};
    const double maximum = *std::ranges::max_element(drivers), minimum = *std::ranges::min_element(drivers);
    const int extended = static_cast<int>(std::ranges::count_if(drivers, [&](double x) { return x >= maximum * 1.618; }));
    const auto shortest = static_cast<std::size_t>(std::ranges::find(drivers, minimum) - drivers.begin());
    const auto longest = static_cast<std::size_t>(std::ranges::find(drivers, maximum) - drivers.begin());
    const std::array times{m[0].bars, m[2].bars, m[4].bars};
    const double projection = m[4].length / m[0].length;
    const int touches = channelTouches(m);
    std::vector<Rule> rules{
        rule(r2 <= 0.618, 2.0, true),
        rule(d > 0 ? m[3].to.price > m[0].to.price : m[3].to.price < m[0].to.price, 2.0, true),
        rule(d > 0 ? m[3].to.price > m[1].to.price : m[3].to.price < m[1].to.price, 1.5),
        rule(shortest != 1, 2.0, true), rule(extended >= 1, 1.5),
        rule(times[longest] >= *std::ranges::max_element(times) * 0.9, 1.2),
        rule(m[1].bars >= m[0].bars || r2 <= 0.618),
        rule(std::abs(r2 - r4) >= 0.15 || symmetry(m[1].bars, m[3].bars) >= 1.6),
        rule(projection >= 0.382 && projection <= 1.618),
        rule(d > 0 ? m[2].to.price > m[0].to.price : m[2].to.price < m[0].to.price),
        rule(touches <= 4), rule(m[2].bars >= m[0].bars * 0.9)};
    const double height = std::abs(m[2].to.price - m[0].from.price);
    return Candidate{"IMPULSE", std::move(rules), -d, true, m[4].to.price,
        m[4].to.price - d * height * 0.382, height, 50, recency, neoWaveRetracementRule(r2)};
}
std::optional<Candidate> impulseProgress(const Waves& waves, const int end, const int recency) {
    const auto optional = window(waves, end, 3); if (!optional) return std::nullopt; const auto& m = *optional;
    const int d = m[0].direction;
    if (d == 0 || m[1].direction != -d || m[2].direction != d || m[0].length <= 0.0) return std::nullopt;
    const double r2 = m[1].length / m[0].length, extension = m[2].length / m[0].length;
    if (extension < 0.95 || r2 > 0.786) return std::nullopt;
    std::vector<Rule> rules{rule(r2 <= 0.618, 2.0), rule(extension >= 1.0, 1.5), rule(m[2].bars >= m[1].bars),
        rule(d > 0 ? m[2].to.price > m[0].to.price : m[2].to.price < m[0].to.price), rule(true)};
    const double height = std::abs(m[2].to.price - m[0].from.price);
    return Candidate{"IMPULSE_IN_PROGRESS", std::move(rules), d, false, m[0].from.price,
        m[2].to.price + d * m[0].length * 0.618, height, 40, recency, neoWaveRetracementRule(r2)};
}
std::optional<Candidate> zigzag(const Waves& waves, const int end, const int recency) {
    const auto optional = window(waves, end, 3); if (!optional) return std::nullopt; const auto& m = *optional;
    const int d = m[0].direction;
    if (d == 0 || m[1].direction != -d || m[2].direction != d || m[0].length <= 0.0) return std::nullopt;
    const double r2 = m[1].length / m[0].length, ratio = m[2].length / m[0].length;
    if (r2 < 0.236 || r2 > 0.886) return std::nullopt;
    const bool beyond = d > 0 ? m[2].to.price > m[0].to.price : m[2].to.price < m[0].to.price;
    std::vector<Rule> rules{rule(r2 >= 0.33 && r2 <= 0.618, 2.0), rule(beyond, 2.0, true), rule(ratio >= 0.618, 1.5),
        rule(m[1].bars >= m[0].bars, 1.5),
        rule(std::abs(m[1].length - m[0].length) / m[0].length >= 0.2 || symmetry(m[0].bars, m[1].bars) >= 1.6),
        rule(symmetry(m[0].length, m[2].length) >= 1.05 || symmetry(m[0].bars, m[2].bars) >= 1.2)};
    const int expected = expectedAfterCorrection(waves, end, 3, d);
    return Candidate{"ZIGZAG", std::move(rules), expected, true, m[2].to.price,
        m[2].to.price + expected * m[0].length * 0.618, std::abs(m[2].to.price - m[1].from.price), 30, recency, neoWaveRetracementRule(r2)};
}
std::optional<Candidate> flat(const Waves& waves, const int end, const int recency) {
    const auto optional = window(waves, end, 3); if (!optional) return std::nullopt; const auto& m = *optional;
    const int d = m[0].direction;
    if (d == 0 || m[1].direction != -d || m[2].direction != d || m[0].length <= 0.0) return std::nullopt;
    const double r2 = m[1].length / m[0].length, ratio = m[2].length / m[0].length;
    if (r2 < 0.618 || r2 > 1.382) return std::nullopt;
    const bool close = std::abs(m[2].to.price - m[0].to.price) <= m[0].length * 0.382;
    std::vector<Rule> rules{rule(r2 >= 0.618, 2.0, true), rule(r2 <= 1.382, 1.5), rule(ratio >= 0.382, 1.5),
        rule(m[1].bars >= m[0].bars, 1.5), rule(close), rule(symmetry(m[1].length, m[2].length) >= 1.1 || symmetry(m[1].bars, m[2].bars) >= 1.3)};
    const int expected = expectedAfterCorrection(waves, end, 3, d);
    return Candidate{"FLAT", std::move(rules), expected, true, m[2].to.price, m[2].to.price + expected * m[0].length,
        std::abs(m[1].to.price - m[0].from.price), 28, recency, neoWaveRetracementRule(r2)};
}
std::optional<Candidate> triangle(const Waves& waves, const int end, const int recency) {
    const auto optional = window(waves, end, 5); if (!optional) return std::nullopt; const auto& m = *optional;
    const double a = m[0].length, b = m[1].length, c = m[2].length, d = m[3].length, e = m[4].length;
    if (a <= 0.0) return std::nullopt;
    const bool contracting = c < a * 0.95 && d < b * 0.95 && e < c;
    const bool expanding = c > a * 1.05 && d > b * 1.05 && e > c;
    const bool neutral = !contracting && !expanding && std::abs(c - a) / a <= 0.15;
    const bool extracting = c > a && e < c && d < b;
    if (!contracting && !expanding && !neutral && !extracting) return std::nullopt;
    int overlaps{};
    for (std::size_t i = 1; i < 5; ++i) {
        const double lo = std::min(m[i - 1].from.price, m[i - 1].to.price), hi = std::max(m[i - 1].from.price, m[i - 1].to.price);
        if (m[i].to.price >= lo && m[i].to.price <= hi) ++overlaps;
    }
    const double path = a + b + c + d + e, net = std::abs(m[4].to.price - m[0].from.price);
    const bool lateral = overlaps == 4;
    const bool inside = m[4].to.price >= std::min(m[0].from.price, m[0].to.price) - a * 0.25 &&
        m[4].to.price <= std::max(m[0].from.price, m[0].to.price) + a * 0.25;
    if (!lateral || !inside) return std::nullopt;
    const std::string pattern = contracting ? "TRIANGLE" : expanding ? "EXPANDING_TRIANGLE" : neutral ? "NEUTRAL_TRIANGLE" : "EXTRACTING_TRIANGLE";
    std::vector<Rule> rules{rule(true, 2.0, true), rule(lateral, 2.0, true), rule(path > 0.0 && net <= path * 0.30, 1.2),
        rule(e / a >= 0.382 && e / a <= 1.382, 1.5), rule(c / a >= 0.618, 1.5), rule(symmetry(b, d) <= 2.618),
        rule(symmetry(d, e) <= 1.618), rule(inside, 1.5, true)};
    const int expected = expectedAfterCorrection(waves, end, 5, m[4].direction);
    return Candidate{pattern, std::move(rules), expected, true, m[4].to.price, m[4].to.price + expected * a * 0.618,
        a, pattern == "TRIANGLE" ? 35 : 33, recency, neoWaveRetracementRule(b / a)};
}
std::optional<Candidate> diametric(const Waves& waves, const int end, const int recency) {
    const auto optional = window(waves, end, 7); if (!optional) return std::nullopt; const auto& m = *optional;
    std::array<double, 7> lengths{}; std::array<int, 7> times{};
    for (std::size_t i = 0; i < 7; ++i) { lengths[i] = m[i].length; times[i] = m[i].bars; }
    if (lengths[3] <= 0.0 || std::ranges::max_element(lengths) != lengths.begin() + 3) return std::nullopt;
    auto sorted = times; std::ranges::sort(sorted);
    const double median = std::max(1, sorted[3]);
    const int similar = static_cast<int>(std::ranges::count_if(times, [&](int t) { return t <= median * 1.5; }));
    std::vector<Rule> rules{rule(true, 1.5, true), rule(true, 2.0, true), rule(symmetry(m[2].bars, m[4].bars) <= 1.75),
        rule(symmetry(m[1].bars, m[5].bars) <= 1.75), rule(symmetry(m[0].bars, m[6].bars) <= 2.0),
        rule(symmetry(m[1].length, m[5].length) <= 1.8), rule(similar >= 5, 1.5)};
    const int expected = expectedAfterCorrection(waves, end, 7, m[6].direction);
    const double height = std::accumulate(lengths.begin(), lengths.end(), 0.0);
    return Candidate{"DIAMETRIC", std::move(rules), expected, true, m[6].to.price, m[6].to.price + expected * lengths[3] * 0.618,
        height, 60, recency, neoWaveRetracementRule(m[1].length / m[0].length)};
}
std::optional<Candidate> symmetrical(const Waves& waves, const int end, const int recency) {
    const auto optional = window(waves, end, 9); if (!optional) return std::nullopt; const auto& m = *optional;
    std::array<double, 9> lengths{};
    for (std::size_t i = 0; i < 9; ++i) lengths[i] = m[i].length;
    if (lengths[4] <= 0.0 || std::ranges::max_element(lengths) != lengths.begin() + 4) return std::nullopt;
    std::vector<Rule> rules{rule(true, 1.5, true), rule(true, 2.0, true), rule(symmetry(m[3].bars, m[5].bars) <= 1.75),
        rule(symmetry(m[2].bars, m[6].bars) <= 1.75), rule(symmetry(m[1].bars, m[7].bars) <= 1.75),
        rule(symmetry(m[0].bars, m[8].bars) <= 2.0), rule(symmetry(m[1].length, m[7].length) <= 1.8, 1.5)};
    const int expected = expectedAfterCorrection(waves, end, 9, m[8].direction);
    const double height = std::accumulate(lengths.begin(), lengths.end(), 0.0);
    return Candidate{"SYMMETRICAL", std::move(rules), expected, true, m[8].to.price, m[8].to.price + expected * lengths[4] * 0.618,
        height, 65, recency, neoWaveRetracementRule(m[1].length / m[0].length)};
}
std::string labelFor(const std::string& pattern, const int retracement, const bool complete) {
    if (pattern == "IMPULSE") return complete ? ":L5" : ":5";
    if (pattern == "IMPULSE_IN_PROGRESS") return ":5";
    if (pattern == "ZIGZAG" || pattern == "FLAT") return complete ? ":L3" : retracement >= 4 ? ":F3" : ":c3";
    return complete ? ":L3" : ":c3";
}
void addCandidate(std::vector<Candidate>& candidates, std::optional<Candidate> candidate) { if (candidate) candidates.push_back(std::move(*candidate)); }

} // namespace

std::optional<NeoWaveVerdict> analyzeNeoWave(const std::vector<Candle>& candles, const double amplitudeAtr) {
    std::vector<Candle> bars;
    std::ranges::copy_if(candles, std::back_inserter(bars), [](const Candle& c) { return c.closed; });
    if (bars.empty()) bars = candles;
    if (bars.size() > 240) bars.erase(bars.begin(), bars.end() - 240);
    if (bars.size() < 45) return std::nullopt;
    const auto pivots = neoWavePivots(bars, amplitudeAtr);
    if (pivots.size() < 4) return std::nullopt;
    const auto waves = monowaves(pivots);
    if (waves.size() < 3) return std::nullopt;
    std::vector<Candidate> candidates;
    for (int recency = 0; recency <= 2; ++recency) {
        const int end = static_cast<int>(waves.size()) - recency;
        if (end < 3) break;
        addCandidate(candidates, impulseComplete(waves, end, recency));
        addCandidate(candidates, impulseProgress(waves, end, recency));
        addCandidate(candidates, zigzag(waves, end, recency));
        addCandidate(candidates, flat(waves, end, recency));
        addCandidate(candidates, triangle(waves, end, recency));
        addCandidate(candidates, diametric(waves, end, recency));
        addCandidate(candidates, symmetrical(waves, end, recency));
    }
    std::erase_if(candidates, [](const Candidate& c) { return c.hardBreach() || c.passed() < 3 || c.ratio() < 0.60; });
    const auto best = std::ranges::max_element(candidates, [](const Candidate& a, const Candidate& b) {
        if (a.recency != b.recency) return a.recency > b.recency;
        if (a.priority != b.priority) return a.priority < b.priority;
        return a.ratio() < b.ratio();
    });
    if (best == candidates.end()) return std::nullopt;

    const auto& last = pivots.back();
    const double livePrice = bars.back().close, liveLength = std::abs(livePrice - last.price);
    const int liveDirection = livePrice > last.price ? 1 : livePrice < last.price ? -1 : 0;
    const int liveBars = static_cast<int>(bars.size()) - 1 - last.index;
    bool confirmed{};
    std::string confirmation;
    if (!best->complete || best->height <= 0.0) confirmation = "ساختار هنوز کامل نشده است.";
    else if (liveDirection == best->expectedDirection && liveLength >= best->height * 0.236) { confirmed = true; confirmation = "حرکت پس از الگو جهت مورد انتظار را تأیید کرد."; }
    else if (liveDirection != best->expectedDirection && liveDirection != 0 && liveBars <= 3) confirmation = "حرکت پس از الگو هنوز شروع نشده است.";
    else if (liveDirection == -best->expectedDirection) confirmation = "حرکت پس از الگو خلاف جهت مورد انتظار است؛ شمارش در خطر ابطال است.";
    else confirmation = "جهش تأییدی به ۲۳٫۶٪ ارتفاع الگو نرسیده است.";
    double confidence = best->ratio() * 100.0 - (best->complete ? 0.0 : 8.0) - (confirmed ? 0.0 : 10.0);
    confidence = std::round(std::clamp(confidence, 0.0, 100.0) * 100.0) / 100.0;
    const auto label = labelFor(best->pattern, best->retracement, best->complete);
    NeoWaveVerdict result;
    result.usable = true; result.complete = best->complete; result.confirmed = confirmed; result.hardBreach = best->hardBreach();
    result.expectedDirection = best->expectedDirection; result.rulesPassed = best->passed(); result.rulesTotal = static_cast<int>(best->rules.size());
    result.confidencePct = confidence; result.badge = best->pattern + " " + label; result.pattern = best->pattern;
    result.invalidationPrice = best->invalidation; result.nextTarget = best->target; result.monowaveCount = static_cast<int>(waves.size());
    result.structureHeight = best->height; result.structureLabel = label; result.retracementRule = best->retracement;
    result.postConstructiveNote = confirmation;
    result.summary = best->pattern + " | " + std::to_string(result.rulesPassed) + "/" + std::to_string(result.rulesTotal) +
        " rules | " + (confirmed ? "confirmed" : "unconfirmed") + " | " + (best->expectedDirection > 0 ? "BUY" : "SELL");
    return result;
}

} // namespace aurum
