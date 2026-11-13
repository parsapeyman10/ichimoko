#include "app/LiveQuoteService.h"

#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QUrlQuery>
#include <cmath>

namespace aurum {

LiveQuoteService::LiveQuoteService(QObject* parent) : QObject(parent) {
    timer_.setInterval(10'000);
    connect(&timer_, &QTimer::timeout, this, &LiveQuoteService::refresh);
}

void LiveQuoteService::start(const QString& symbol) {
    const auto clean = symbol.trimmed().toUpper();
    symbol_ = clean.isEmpty() ? QStringLiteral("BTC/USDT") : clean;
    price_ = 0.0;
    provider_.clear();
    message_ = QStringLiteral("در حال اتصال به فید عمومی…");
    timer_.start();
    emit changed();
    refresh();
}

void LiveQuoteService::stop() {
    timer_.stop();
    if (reply_) { reply_->abort(); reply_->deleteLater(); reply_ = nullptr; }
    message_ = QStringLiteral("دادهٔ زنده خاموش است");
    emit changed();
}

void LiveQuoteService::refresh() {
    if (reply_) return;
    QString url;
    QString provider;
    const auto compact = QString(symbol_).remove(QLatin1Char('/'));
    if (symbol_.endsWith(QStringLiteral("USDT")) || symbol_.endsWith(QStringLiteral("USDC")) ||
        symbol_.startsWith(QStringLiteral("BTC")) || symbol_.startsWith(QStringLiteral("ETH")) || symbol_.startsWith(QStringLiteral("SOL"))) {
        const auto pair = symbol_.endsWith(QStringLiteral("/USD")) ? compact + QStringLiteral("T") : compact;
        url = QStringLiteral("https://data-api.binance.vision/api/v3/ticker/price?symbol=%1").arg(pair);
        provider = QStringLiteral("Binance public market data");
    } else {
        QString yahoo = compact;
        if (symbol_ == QStringLiteral("XAU/USD")) yahoo = QStringLiteral("GC=F");
        else if (symbol_ == QStringLiteral("XAG/USD")) yahoo = QStringLiteral("SI=F");
        else if (symbol_ == QStringLiteral("EUR/USD")) yahoo = QStringLiteral("EURUSD=X");
        else if (symbol_ == QStringLiteral("USD/JPY")) yahoo = QStringLiteral("JPY=X");
        url = QStringLiteral("https://query1.finance.yahoo.com/v8/finance/chart/%1?interval=1m&range=1d").arg(QString::fromLatin1(QUrl::toPercentEncoding(yahoo)));
        provider = QStringLiteral("Yahoo public chart");
    }
    QNetworkRequest request{QUrl(url)};
    request.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("AurumEdge-Windows/0.1"));
    request.setTransferTimeout(8'000);
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    reply_ = network_.get(request);
    emit changed();
    connect(reply_, &QNetworkReply::finished, this, [this, provider] {
        auto* completed = reply_;
        reply_ = nullptr;
        if (!completed) return;
        double parsed{};
        if (completed->error() == QNetworkReply::NoError) {
            const auto json = QJsonDocument::fromJson(completed->readAll());
            if (json.isObject() && json.object().contains(QStringLiteral("price"))) {
                parsed = json.object().value(QStringLiteral("price")).toString().toDouble();
            } else if (json.isObject()) {
                const auto result = json.object().value(QStringLiteral("chart")).toObject().value(QStringLiteral("result")).toArray();
                if (!result.isEmpty()) parsed = result.first().toObject().value(QStringLiteral("meta")).toObject().value(QStringLiteral("regularMarketPrice")).toDouble();
            }
        }
        if (std::isfinite(parsed) && parsed > 0.0) {
            price_ = parsed;
            provider_ = provider;
            receivedAtMs_ = QDateTime::currentMSecsSinceEpoch();
            message_ = QStringLiteral("Quote دریافت شد؛ فقط مشاهده و Paper Trading");
        } else {
            message_ = completed->error() == QNetworkReply::NoError ? QStringLiteral("پاسخ فید قابل خواندن نبود")
                : QStringLiteral("خطای فید: %1").arg(completed->errorString());
        }
        completed->deleteLater();
        emit changed();
    });
}

} // namespace aurum
