#pragma once

#include <optional>
#include <string>

namespace aurum {

enum class OrderSide { buy, sell };
enum class AssetClass { crypto, forex, commodity, stock };

struct VenueSpec {
    int leverageCap{1};
    double tickSize{};
    double commissionPerUnit{};
    double commissionBpsPerSide{};
    double spreadPrice{};
    double spreadBps{};
    double minOrderQuantity{};
    double quantityStep{};
    double minNotionalUsd{};
    double contractSizeUnits{1.0};

    [[nodiscard]] double spreadCostUsd(double price, double quantity) const;
    [[nodiscard]] double commissionUsd(double price, double quantity) const;
    [[nodiscard]] double minStopDistance(double price) const;
};

struct TradePlanBounds {
    double minStopPct{};
    double maxStopPct{};
    double maxTargetPct{};
    double atrFactorMin{0.80};
    double atrFactorMax{2.10};
    double maxTargetAtr{3.40};
    int venueLeverageCap{};
};

struct GuardedTradePlan {
    std::string symbol;
    OrderSide side{};
    int intervalMinutes{15};
    double entry{};
    double stopLoss{};
    double takeProfit{};
    double stopDistance{};
    double targetDistance{};
    double riskReward{};
    double stopPct{};
    double targetPct{};
    double atr{};
    int recommendedLeverage{};
    double liquidationPrice{};
    double liquidationBufferR{};
    bool usable{};
    bool adjusted{};
    std::string note;
};

struct RiskCalculation {
    std::string symbol;
    OrderSide side{};
    double riskBudgetUsd{};
    double stopDistance{};
    double targetDistance{};
    double riskReward{};
    double positionUnits{};
    double standardLots{};
    int leverage{};
    double notionalUsd{};
    double marginRequiredUsd{};
    double commissionUsd{};
    double spreadCostUsd{};
    double grossProfitAtTpUsd{};
    double grossLossAtSlUsd{};
    double liquidationPrice{};
    double liquidationBufferR{};
    double stopPctOfPrice{};
    double targetPctOfPrice{};
    bool planValid{};
    std::string planNote;
};

[[nodiscard]] AssetClass assetClassOf(std::string symbol);
[[nodiscard]] std::string normalizeSymbol(std::string symbol);
[[nodiscard]] VenueSpec venueSpecFor(const std::string& symbol);
[[nodiscard]] double quotePnlToUsd(const std::string& symbol, double quotePnl, double price);
[[nodiscard]] double roundToTick(const std::string& symbol, double price);
[[nodiscard]] int safeLeverage(const std::string& symbol, OrderSide side, double entry,
    double stopDistance, double* liquidationPrice = nullptr, double* liquidationBufferR = nullptr);
[[nodiscard]] std::optional<RiskCalculation> calculatePosition(const std::string& symbol,
    double accountBalance, double riskPercent, double entry, double stopLoss, double takeProfit);
[[nodiscard]] TradePlanBounds tradePlanBounds(const std::string& symbol, int intervalMinutes = 15);
[[nodiscard]] double robustAtr(const std::string& symbol, double price, double atrValue, int intervalMinutes = 15);
[[nodiscard]] GuardedTradePlan sanitizeTradePlan(const std::string& symbol, OrderSide side,
    double entry, std::optional<double> candidateStop, std::optional<double> candidateTarget,
    double desiredRiskReward = 1.5, double referenceAtr = 0.0, int intervalMinutes = 15,
    double strategyAtr = 0.0, std::optional<double> anchorEntry = std::nullopt);
[[nodiscard]] GuardedTradePlan rebaseTradePlanToLive(const std::string& symbol, OrderSide side,
    double liveEntry, double planAnchorEntry, double planStop, double planTarget,
    double referenceAtr = 0.0, int intervalMinutes = 15, double desiredRiskReward = 1.5);
[[nodiscard]] double minSafeStructuralStopDistance(const std::string& symbol, double referencePrice,
    double triggerAtr14, double strategyAtr14 = 0.0, bool fiveMinute = true);
[[nodiscard]] double enforceRiskReward(double entry, double stopLoss, double takeProfit,
    OrderSide side, double minRiskReward = 1.5, double maxRiskReward = 3.0);

} // namespace aurum
