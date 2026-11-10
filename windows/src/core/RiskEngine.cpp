#include "core/RiskEngine.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <cmath>

namespace aurum {
namespace {

bool startsWith(const std::string& value, const std::string& prefix) {
    return value.size() >= prefix.size() && value.compare(0, prefix.size(), prefix) == 0;
}
bool endsWith(const std::string& value, const std::string& suffix) {
    return value.size() >= suffix.size() && value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}
double round2(const double value) { return std::round(value * 100.0) / 100.0; }
double round4(const double value) { return std::round(value * 10'000.0) / 10'000.0; }

} // namespace

double VenueSpec::spreadCostUsd(const double price, const double quantity) const {
    return spreadBps > 0.0 ? price * quantity * spreadBps / 10'000.0 : spreadPrice * quantity;
}
double VenueSpec::commissionUsd(const double price, const double quantity) const {
    return commissionBpsPerSide > 0.0 ? price * quantity * commissionBpsPerSide / 10'000.0
                                      : commissionPerUnit * quantity;
}
double VenueSpec::minStopDistance(const double price) const {
    const double spread = spreadBps > 0.0 ? price * spreadBps / 10'000.0 : spreadPrice;
    return std::max(2.0 * spread, 2.0 * tickSize);
}

std::string normalizeSymbol(std::string symbol) {
    symbol.erase(std::remove_if(symbol.begin(), symbol.end(), [](const unsigned char c) { return std::isspace(c) != 0; }), symbol.end());
    std::ranges::transform(symbol, symbol.begin(), [](const unsigned char c) { return static_cast<char>(std::toupper(c)); });
    return symbol;
}

AssetClass assetClassOf(std::string symbol) {
    symbol = normalizeSymbol(std::move(symbol));
    if (startsWith(symbol, "XAU") || startsWith(symbol, "XAG") || symbol == "GOLD" || symbol == "SILVER" ||
        startsWith(symbol, "WTI") || startsWith(symbol, "BRENT") || startsWith(symbol, "USOIL") ||
        startsWith(symbol, "UKOIL") || startsWith(symbol, "NATGAS") || startsWith(symbol, "COPPER")) return AssetClass::commodity;
    static constexpr std::array currencies{"USD", "EUR", "JPY", "GBP", "CAD", "CHF", "AUD", "NZD"};
    if (symbol.size() >= 6 && symbol.find('/') != std::string::npos) {
        const auto slash = symbol.find('/');
        const auto base = symbol.substr(0, slash);
        const auto quote = symbol.substr(slash + 1);
        if (std::ranges::find(currencies, base) != currencies.end() &&
            std::ranges::find(currencies, quote) != currencies.end()) return AssetClass::forex;
    }
    if (endsWith(symbol, "USDT") || endsWith(symbol, "USDC") || endsWith(symbol, "/USD") ||
        startsWith(symbol, "BTC") || startsWith(symbol, "ETH") || startsWith(symbol, "SOL")) return AssetClass::crypto;
    return AssetClass::stock;
}

VenueSpec venueSpecFor(const std::string& symbol) {
    const auto clean = normalizeSymbol(symbol);
    switch (assetClassOf(clean)) {
    case AssetClass::crypto: {
        static constexpr std::array majors{"SOL", "BNB", "XRP", "DOGE", "ADA", "AVAX", "LINK", "TON", "DOT", "TRX", "LTC", "BCH", "SUI", "NEAR"};
        const bool major = std::ranges::any_of(majors, [&](const char* prefix) { return startsWith(clean, prefix); });
        const int leverage = startsWith(clean, "BTC") || startsWith(clean, "ETH") ? 10 : major ? 7 : 5;
        const double tick = startsWith(clean, "BTC") || startsWith(clean, "ETH") || startsWith(clean, "BNB") || startsWith(clean, "SOL") ? 0.01 : 0.0001;
        return VenueSpec{leverage, tick, 0.0, 10.0, 0.0, 1.0, 0.0001, 0.00001, 5.0, 1.0};
    }
    case AssetClass::forex:
        return VenueSpec{10, endsWith(clean, "JPY") ? 0.001 : 0.00001, 0.000035, 0.0,
            endsWith(clean, "JPY") ? 0.001 : 0.00001, 0.0, 1000.0, 100.0, 10.0, 100000.0};
    case AssetClass::commodity:
        if (startsWith(clean, "XAU") || clean == "GOLD") return VenueSpec{10, 0.01, 0.035, 0.0, 0.12, 0.0, 0.01, 0.01, 10.0, 100.0};
        if (startsWith(clean, "XAG") || clean == "SILVER") return VenueSpec{10, 0.001, 0.0007, 0.0, 0.02, 0.0, 0.1, 0.01, 10.0, 5000.0};
        if (startsWith(clean, "NATGAS")) return VenueSpec{10, 0.001, 0.0, 0.0, 0.002, 0.0, 1.0, 0.1, 10.0, 10000.0};
        if (startsWith(clean, "COPPER")) return VenueSpec{10, 0.0005, 0.0, 0.0, 0.001, 0.0, 1.0, 0.1, 10.0, 25000.0};
        return VenueSpec{10, 0.01, 0.0, 0.0, 0.03, 0.0, 0.1, 0.01, 10.0, 1000.0};
    case AssetClass::stock:
        return VenueSpec{10, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.01, 1.0, 1.0};
    }
    return {};
}

double quotePnlToUsd(const std::string& symbol, const double quotePnl, const double price) {
    const auto clean = normalizeSymbol(symbol);
    const bool usdCross = startsWith(clean, "USD/") &&
        (endsWith(clean, "JPY") || endsWith(clean, "CHF") || endsWith(clean, "CAD"));
    return usdCross && price > 0.0 ? quotePnl / price : quotePnl;
}

double roundToTick(const std::string& symbol, const double price) {
    if (!std::isfinite(price)) return price;
    const double tick = std::max(venueSpecFor(symbol).tickSize, 1e-8);
    return std::round(price / tick) * tick;
}

int safeLeverage(const std::string& symbol, const OrderSide side, const double entry,
    const double stopDistance, double* liquidationPrice, double* liquidationBufferR) {
    const auto spec = venueSpecFor(symbol);
    const double safeEntry = std::isfinite(entry) && entry > 0.0 ? entry : 1.0;
    const double safeStop = std::isfinite(stopDistance) && stopDistance > 0.0 ? stopDistance : safeEntry * 0.01;
    const double maintenance = assetClassOf(symbol) == AssetClass::crypto ? 0.005 : 0.004;
    const double feePct = (spec.commissionBpsPerSide * 2.0 + spec.spreadBps) / 10000.0;
    const double denominator = safeStop / safeEntry * 2.5 + maintenance + feePct;
    const int maxByLiquidation = denominator > 0.0 ? static_cast<int>(std::floor(1.0 / denominator)) : 10;
    const int leverage = std::clamp(std::min(spec.leverageCap, std::max(1, maxByLiquidation)), 1, 10);
    const double distancePct = std::clamp(1.0 / leverage - 0.005, 0.005, 0.999);
    const double liquidation = roundToTick(symbol, side == OrderSide::sell ? safeEntry * (1.0 + distancePct)
        : std::max(0.00001, safeEntry * (1.0 - distancePct)));
    if (liquidationPrice) *liquidationPrice = liquidation;
    if (liquidationBufferR) *liquidationBufferR = round2(std::abs(liquidation - safeEntry) / safeStop);
    return leverage;
}

std::optional<RiskCalculation> calculatePosition(const std::string& symbolInput,
    const double accountBalance, const double riskPercent, const double entry,
    const double stopLoss, const double takeProfit) {
    if (!std::isfinite(accountBalance) || accountBalance <= 0.0 || !std::isfinite(riskPercent) || riskPercent <= 0.0 ||
        !std::isfinite(entry) || entry <= 0.0 || !std::isfinite(stopLoss) || stopLoss <= 0.0 ||
        !std::isfinite(takeProfit) || takeProfit <= 0.0) return std::nullopt;
    const bool buy = stopLoss < entry && takeProfit > entry;
    const bool sell = stopLoss > entry && takeProfit < entry;
    if (!buy && !sell) return std::nullopt;
    const double stopDistance = std::abs(entry - stopLoss);
    const double targetDistance = std::abs(takeProfit - entry);
    if (stopDistance <= 1e-8 || targetDistance <= 1e-8) return std::nullopt;

    const auto symbol = normalizeSymbol(symbolInput);
    const auto spec = venueSpecFor(symbol);
    const double riskBudget = accountBalance * riskPercent / 100.0;
    const double perUnitStopUsd = quotePnlToUsd(symbol, stopDistance, entry);
    if (!std::isfinite(perUnitStopUsd) || perUnitStopUsd <= 1e-9) return std::nullopt;
    const double units = round4(std::max(0.0001, riskBudget / perUnitStopUsd));
    const double notional = quotePnlToUsd(symbol, units * entry, entry);
    const OrderSide side = buy ? OrderSide::buy : OrderSide::sell;
    double liquidation{}, bufferR{};
    const int leverage = safeLeverage(symbol, side, entry, stopDistance, &liquidation, &bufferR);
    const double rr = targetDistance / stopDistance;
    const double stopPct = stopDistance / entry;
    const double targetPct = targetDistance / entry;
    const double maxStopPct = assetClassOf(symbol) == AssetClass::crypto ? 0.016 :
        assetClassOf(symbol) == AssetClass::forex ? 0.0028 :
        assetClassOf(symbol) == AssetClass::commodity ? 0.0055 : 0.005;
    const bool valid = rr >= 1.5 - 1e-6 && rr <= 3.0 + 1e-6 && stopPct <= maxStopPct * 1.02 &&
        stopDistance >= spec.minStopDistance(entry);

    RiskCalculation result{symbol, side, round2(riskBudget), stopDistance, targetDistance, round2(rr),
        units, round4(units / spec.contractSizeUnits), leverage, round2(notional), round2(notional / leverage),
        round2(spec.commissionUsd(entry, units) * 2.0), round2(spec.spreadCostUsd(entry, units)),
        round2(riskBudget * rr), round2(riskBudget), liquidation, bufferR, round4(stopPct), round4(targetPct), valid, {}};
    if (!valid) result.planNote = "R:R باید بین ۱٫۵ تا ۳ باشد و حد ضرر باید بیرون از نویز و داخل دالان نوسان ۱۵ دقیقه قرار گیرد.";
    else if (notional / leverage > accountBalance * 0.30) result.planNote = "مارجین از ۳۰٪ موجودی بیشتر است؛ حجم را کم کنید.";
    else result.planNote = "پلن از نظر جهت، R:R و محدودهٔ پایهٔ نوسان معتبر است.";
    return result;
}

double minSafeStructuralStopDistance(const std::string& symbol, const double referencePrice,
    const double triggerAtr14, const double strategyAtr14, const bool fiveMinute) {
    const auto clean = normalizeSymbol(symbol);
    const double price = std::isfinite(referencePrice) && referencePrice > 0.0 ? referencePrice : 1.0;
    const double trigger = std::isfinite(triggerAtr14) && triggerAtr14 > 0.0 ? triggerAtr14 : price * 0.0020;
    const double strategy = std::isfinite(strategyAtr14) && strategyAtr14 > 0.0 ? strategyAtr14 : trigger;
    const double venue = venueSpecFor(clean).minStopDistance(price);
    switch (assetClassOf(clean)) {
    case AssetClass::crypto: {
        const bool major = startsWith(clean, "BTC") || startsWith(clean, "ETH");
        const double floorPct = major && fiveMinute ? 0.0016 : major ? 0.0020 : fiveMinute ? 0.0020 : 0.0025;
        return std::max(venue * 3.0, std::max(price * floorPct, std::max(trigger * 0.82, strategy * 0.28)));
    }
    case AssetClass::commodity:
        return std::max(venue * 2.5, std::max(price * (startsWith(clean, "XAG") ? 0.0014 : 0.0009), std::max(trigger * 0.78, strategy * 0.25)));
    case AssetClass::stock:
        return std::max(venue * 2.5, std::max(price * 0.0009, std::max(trigger * 0.78, strategy * 0.25)));
    case AssetClass::forex:
        return std::max(venue * 2.5, std::max(price * 0.0004, std::max(trigger * 0.75, strategy * 0.24)));
    }
    return 0.0;
}

double enforceRiskReward(const double entry, const double stopLoss, const double takeProfit,
    const OrderSide side, const double minRiskReward, const double maxRiskReward) {
    const double minRr = std::clamp(minRiskReward, 1.5, 3.0);
    const double maxRr = std::max(minRr, std::clamp(maxRiskReward, 1.5, 3.0));
    const double stopDistance = std::abs(entry - stopLoss);
    if (!std::isfinite(stopDistance) || stopDistance <= 1e-8) return takeProfit;
    const double rr = std::abs(takeProfit - entry) / stopDistance;
    const bool correct = side == OrderSide::buy ? takeProfit > entry : takeProfit < entry;
    if (correct && rr >= minRr - 1e-6 && rr <= maxRr + 1e-6) return takeProfit;
    const double distance = stopDistance * std::clamp(rr, minRr, maxRr);
    return side == OrderSide::buy ? entry + distance : std::max(0.00001, entry - distance);
}

} // namespace aurum
