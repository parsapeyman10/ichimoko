#include "app/CandleChart.h"

#include <QPainter>
#include <QtMath>

namespace aurum {

CandleChart::CandleChart(QQuickItem* parent) : QQuickPaintedItem(parent) {
    setAntialiasing(true);
    setOpaquePainting(true);
}

void CandleChart::setCandles(const QVariantList& rows) {
    if (candles_ == rows) return;
    candles_ = rows;
    points_.clear();
    points_.reserve(rows.size());
    for (const auto& row : rows) {
        const auto map = row.toMap();
        points_.push_back(Point{map[QStringLiteral("open")].toDouble(), map[QStringLiteral("high")].toDouble(),
            map[QStringLiteral("low")].toDouble(), map[QStringLiteral("close")].toDouble()});
    }
    emit candlesChanged();
    update();
}

void CandleChart::paint(QPainter* painter) {
    painter->fillRect(boundingRect(), QColor(QStringLiteral("#080d18")));
    if (points_.empty()) {
        painter->setPen(QColor(QStringLiteral("#8290aa")));
        painter->drawText(boundingRect(), Qt::AlignCenter, QStringLiteral("CSV را بارگذاری کنید"));
        return;
    }
    double high = points_.front().high, low = points_.front().low;
    for (const auto& p : points_) { high = std::max(high, p.high); low = std::min(low, p.low); }
    const double range = std::max(high - low, 1e-9);
    const double left = 12.0, right = width() - 70.0, top = 12.0, bottom = height() - 18.0;
    painter->setPen(QPen(QColor(255, 255, 255, 18), 1.0));
    for (int i = 0; i <= 4; ++i) {
        const double y = top + (bottom - top) * i / 4.0;
        painter->drawLine(QPointF(left, y), QPointF(right, y));
        painter->setPen(QColor(QStringLiteral("#8290aa")));
        painter->drawText(QRectF(right + 6, y - 10, 62, 20), Qt::AlignVCenter,
            QString::number(high - range * i / 4.0, 'f', range < 5 ? 4 : 2));
        painter->setPen(QPen(QColor(255, 255, 255, 18), 1.0));
    }
    const double step = (right - left) / static_cast<double>(points_.size());
    const double bodyWidth = std::max(1.0, std::min(12.0, step * 0.68));
    const auto yOf = [&](const double price) { return bottom - (price - low) / range * (bottom - top); };
    for (std::size_t i = 0; i < points_.size(); ++i) {
        const auto& p = points_[i];
        const double x = left + (static_cast<double>(i) + 0.5) * step;
        const auto color = p.close >= p.open ? QColor(QStringLiteral("#20c997")) : QColor(QStringLiteral("#ff5c72"));
        painter->setPen(QPen(color, std::max(1.0, step * 0.08)));
        painter->drawLine(QPointF(x, yOf(p.high)), QPointF(x, yOf(p.low)));
        const double y1 = yOf(std::max(p.open, p.close));
        const double y2 = yOf(std::min(p.open, p.close));
        painter->fillRect(QRectF(x - bodyWidth / 2.0, y1, bodyWidth, std::max(1.0, y2 - y1)), color);
    }
}

} // namespace aurum
