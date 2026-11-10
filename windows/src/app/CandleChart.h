#pragma once

#include <QQuickPaintedItem>
#include <QVariantList>
#include <vector>

namespace aurum {

class CandleChart : public QQuickPaintedItem {
    Q_OBJECT
    Q_PROPERTY(QVariantList candles READ candles WRITE setCandles NOTIFY candlesChanged)
public:
    explicit CandleChart(QQuickItem* parent = nullptr);
    [[nodiscard]] QVariantList candles() const { return candles_; }
    void setCandles(const QVariantList& rows);
    void paint(QPainter* painter) override;
signals:
    void candlesChanged();
private:
    struct Point { double open, high, low, close; };
    QVariantList candles_;
    std::vector<Point> points_;
};

} // namespace aurum
