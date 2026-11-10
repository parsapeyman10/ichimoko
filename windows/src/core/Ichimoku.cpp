#include "core/Ichimoku.h"

#include <algorithm>
#include <cmath>
#include <deque>

namespace aurum {
namespace {

class RollingExtreme final {
public:
    explicit RollingExtreme(const bool maximum) : maximum_(maximum) {}
    void add(const std::size_t index, const double value) {
        while (!values_.empty() && (maximum_ ? values_.back().second <= value : values_.back().second >= value)) values_.pop_back();
        values_.emplace_back(index, value);
    }
    void removeOlderThan(const std::size_t first) {
        while (!values_.empty() && values_.front().first < first) values_.pop_front();
    }
    [[nodiscard]] double value() const { return values_.front().second; }
private:
    bool maximum_;
    std::deque<std::pair<std::size_t, double>> values_;
};

} // namespace

std::vector<IchimokuPoint> ichimoku(
    const std::vector<Candle>& bars,
    const std::size_t tenkanPeriod,
    const std::size_t kijunPeriod,
    const std::size_t spanBPeriod) {
    std::vector<IchimokuPoint> out(bars.size());
    RollingExtreme high8(true), low8(false), high24(true), low24(false), high72(true), low72(false);
    double atr = 0.0;
    for (std::size_t i = 0; i < bars.size(); ++i) {
        const auto first8 = i + 1 >= tenkanPeriod ? i + 1 - tenkanPeriod : 0;
        const auto first24 = i + 1 >= kijunPeriod ? i + 1 - kijunPeriod : 0;
        const auto first72 = i + 1 >= spanBPeriod ? i + 1 - spanBPeriod : 0;
        high8.removeOlderThan(first8); low8.removeOlderThan(first8);
        high24.removeOlderThan(first24); low24.removeOlderThan(first24);
        high72.removeOlderThan(first72); low72.removeOlderThan(first72);
        high8.add(i, bars[i].high); low8.add(i, bars[i].low);
        high24.add(i, bars[i].high); low24.add(i, bars[i].low);
        high72.add(i, bars[i].high); low72.add(i, bars[i].low);
        const double previousClose = i > 0 ? bars[i - 1].close : bars[i].open;
        const double tr = std::max({bars[i].high - bars[i].low,
            std::abs(bars[i].high - previousClose), std::abs(bars[i].low - previousClose)});
        const auto smoothing = static_cast<double>(std::min<std::size_t>(i, 13));
        atr = i == 0 ? tr : ((atr * smoothing) + tr) / (smoothing + 1.0);
        auto& point = out[i];
        point.tenkan = (high8.value() + low8.value()) / 2.0;
        point.kijun = (high24.value() + low24.value()) / 2.0;
        point.spanB = (high72.value() + low72.value()) / 2.0;
        point.spanA = (point.tenkan + point.kijun) / 2.0;
        point.atr14 = atr;
        point.valid = i + 1 >= spanBPeriod;
    }
    return out;
}

} // namespace aurum
