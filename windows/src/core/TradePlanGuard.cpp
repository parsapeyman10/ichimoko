#include "core/RiskEngine.h"

#include <algorithm>
#include <cmath>

namespace aurum {
namespace {
double round2(const double value) { return std::isfinite(value) ? std::round(value * 100.0) / 100.0 : 0.0; }
double clampRr(const double value) { return std::isfinite(value) ? std::clamp(value, 1.5, 3.0) : 1.5; }
double stopCeiling(const std::string& symbol, const double price, const double atr, const int minutes) {
    const auto bounds = tradePlanBounds(symbol, minutes);
    return std::max(bounds.minStopPct * price, std::min(bounds.maxStopPct * price, bounds.atrFactorMax * atr));
}
GuardedTradePlan rejected(const std::string& symbol, const OrderSide side, const int minutes,
    const double entry, std::string note) {
    GuardedTradePlan result;
    result.symbol = symbol; result.side = side; result.intervalMinutes = minutes; result.entry = entry;
    result.recommendedLeverage = 1; result.note = std::move(note);
    return result;
}
}

TradePlanBounds tradePlanBounds(const std::string& symbol, const int intervalMinutes) {
    const auto asset = assetClassOf(symbol);
    const double scale = std::sqrt(static_cast<double>(std::max(1, intervalMinutes)) / 15.0);
    double rawMin{}, rawMax{};
    switch (asset) {
    case AssetClass::crypto: rawMin = 0.0016; rawMax = 0.0160; break;
    case AssetClass::forex: rawMin = 0.0003; rawMax = 0.0028; break;
    case AssetClass::commodity: rawMin = 0.0007; rawMax = 0.0055; break;
    case AssetClass::stock: rawMin = 0.0006; rawMax = 0.0050; break;
    }
    TradePlanBounds result;
    result.maxStopPct = std::clamp(rawMax * scale, 0.00036, 0.065);
    result.minStopPct = std::clamp(rawMin * scale, 0.00018, result.maxStopPct * 0.40);
    result.maxTargetPct = result.maxStopPct * 1.80;
    result.venueLeverageCap = std::clamp(venueSpecFor(symbol).leverageCap, 1, 10);
    return result;
}

double robustAtr(const std::string& symbol, const double price, const double atrValue, const int intervalMinutes) {
    if (!std::isfinite(price) || price <= 0.0) return 0.0;
    const auto bounds = tradePlanBounds(symbol, intervalMinutes);
    const double floor = price * bounds.maxStopPct / 9.0;
    const double ceiling = price * bounds.maxStopPct / 1.6;
    const double fallback = price * bounds.maxStopPct / 2.6;
    const double raw = std::isfinite(atrValue) && atrValue > 0.0 ? atrValue : fallback;
    return std::clamp(raw, floor, ceiling);
}

GuardedTradePlan sanitizeTradePlan(const std::string& symbolInput, const OrderSide side,
    const double entry, const std::optional<double> candidateStop, const std::optional<double> candidateTarget,
    const double desiredRiskReward, const double referenceAtr, const int intervalMinutes,
    const double strategyAtr, const std::optional<double> anchorEntry) {
    const auto symbol = normalizeSymbol(symbolInput);
    if (!std::isfinite(entry) || entry <= 0.0) return rejected(symbol, side, intervalMinutes, entry, "قیمت ورود نامعتبر است.");
    const auto bounds = tradePlanBounds(symbol, intervalMinutes);
    const double atrInput = std::isfinite(referenceAtr) && referenceAtr > 0.0 ? referenceAtr : strategyAtr;
    const double atr = robustAtr(symbol, entry, atrInput, intervalMinutes);
    const double strategy = robustAtr(symbol, entry, std::isfinite(strategyAtr) && strategyAtr > 0.0 ? strategyAtr : atr, intervalMinutes);
    const double structural = minSafeStructuralStopDistance(symbol, entry, atr, strategy, intervalMinutes == 5);
    const double corridor = entry * bounds.maxStopPct;
    const double venue = std::min(venueSpecFor(symbol).minStopDistance(entry) * 2.0, corridor);
    const double floor = std::min(corridor, std::max({std::min(structural, corridor), bounds.minStopPct * entry, bounds.atrFactorMin * atr, venue}));
    const double ceiling = std::max(floor, stopCeiling(symbol, entry, atr, intervalMinutes));
    const double anchor = anchorEntry && std::isfinite(*anchorEntry) && *anchorEntry > 0.0 ? *anchorEntry : entry;
    const int direction = side == OrderSide::sell ? -1 : 1;
    double rawStop{};
    if (candidateStop && std::isfinite(*candidateStop) && *candidateStop > 0.0 &&
        (direction > 0 ? *candidateStop < anchor : *candidateStop > anchor)) rawStop = std::abs(anchor - *candidateStop);
    const double desired = clampRr(desiredRiskReward);
    constexpr double safety = 0.995;
    const double targetCeiling = std::max(floor * 1.5,
        std::min(bounds.maxTargetPct * entry, bounds.maxTargetAtr * atr) * safety);
    const double stopUpper = std::max(floor, std::min(ceiling, targetCeiling / desired) * safety);
    const double resolved = rawStop > 0.0 ? rawStop : floor;
    const double stopDistance = std::clamp(resolved, floor, stopUpper);
    double rawTarget{};
    if (candidateTarget && std::isfinite(*candidateTarget) && *candidateTarget > 0.0 &&
        (direction > 0 ? *candidateTarget > anchor : *candidateTarget < anchor)) rawTarget = std::abs(*candidateTarget - anchor);
    const double structured = rawTarget > 0.0 && rawTarget / stopDistance >= 1.5 - 1e-9 ? rawTarget : stopDistance * desired;
    const double targetUpper = std::max(stopDistance * 1.5, std::min(stopDistance * 3.0, targetCeiling));
    const double targetDistance = std::clamp(structured, stopDistance * 1.5, targetUpper);
    const double stop = roundToTick(symbol, std::max(0.00001, entry - direction * stopDistance));
    const double target = roundToTick(symbol, std::max(0.00001, entry + direction * targetDistance));
    const double finalStop = std::max(1e-9, std::abs(entry - stop));
    const double finalTarget = std::max(1e-9, std::abs(target - entry));
    const double rr = std::clamp(finalTarget / finalStop, 1.5, 3.0);
    std::string note;
    bool adjusted{};
    if (std::abs(finalStop - resolved) / std::max(resolved, 1e-9) > 0.01) {
        adjusted = true;
        note += finalStop > resolved ? "حد ضرر از سقف نوسان دورتر بود و جمع شد. " : "حد ضرر داخل نویز بود و به کف ایمن رسید. ";
    }
    if (std::abs(finalTarget - structured) / std::max(structured, 1e-9) > 0.03) {
        adjusted = true; note += "حد سود به محدودهٔ قابل‌دسترس برگشت. ";
    }
    if (std::abs(rr - desired) > 0.05) { adjusted = true; note += "R:R اصلاح شد. "; }
    if (std::isfinite(referenceAtr) && referenceAtr > 0.0 && std::abs(referenceAtr - atr) / atr > 0.25) {
        adjusted = true; note += "ATR غیرعادی برش خورد. ";
    }
    double liquidation{}, buffer{};
    const int leverage = safeLeverage(symbol, side, entry, finalStop, &liquidation, &buffer);
    GuardedTradePlan result;
    result.symbol = symbol; result.side = side; result.intervalMinutes = intervalMinutes;
    result.entry = roundToTick(symbol, entry); result.stopLoss = stop; result.takeProfit = target;
    result.stopDistance = finalStop; result.targetDistance = finalTarget; result.riskReward = round2(rr);
    result.stopPct = finalStop / entry; result.targetPct = finalTarget / entry; result.atr = atr;
    result.recommendedLeverage = leverage; result.liquidationPrice = liquidation; result.liquidationBufferR = buffer;
    result.usable = true; result.adjusted = adjusted;
    result.note = note.empty() ? "پلن داخل دالان نوسان و R:R معتبر است." : note;
    return result;
}

GuardedTradePlan rebaseTradePlanToLive(const std::string& symbol, const OrderSide side,
    const double liveEntry, const double planAnchorEntry, const double planStop, const double planTarget,
    const double referenceAtr, const int intervalMinutes, const double desiredRiskReward) {
    if (!std::isfinite(liveEntry) || liveEntry <= 0.0)
        return sanitizeTradePlan(symbol, side, liveEntry, planStop, planTarget, desiredRiskReward, referenceAtr, intervalMinutes);
    const double anchor = std::isfinite(planAnchorEntry) && planAnchorEntry > 0.0 ? planAnchorEntry : liveEntry;
    const double atr = robustAtr(symbol, liveEntry, referenceAtr, intervalMinutes);
    const auto bounds = tradePlanBounds(symbol, intervalMinutes);
    const double limit = std::max(1.25 * atr, 3.0 * bounds.minStopPct * liveEntry);
    const double drift = std::abs(liveEntry - anchor);
    if (drift > limit) return rejected(normalizeSymbol(symbol), side, intervalMinutes, liveEntry,
        "پلن کهنه است؛ قیمت زنده بیش از حد از لنگر فاصله گرفته و باید تحلیل تازه شود.");
    const double shift = liveEntry - anchor;
    return sanitizeTradePlan(symbol, side, liveEntry, planStop + shift, planTarget + shift,
        desiredRiskReward, referenceAtr, intervalMinutes, referenceAtr, liveEntry);
}

} // namespace aurum
