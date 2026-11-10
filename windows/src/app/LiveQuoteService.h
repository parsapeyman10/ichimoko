#pragma once

#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QObject>
#include <QTimer>

namespace aurum {

class LiveQuoteService final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool running READ running NOTIFY changed)
    Q_PROPERTY(bool loading READ loading NOTIFY changed)
    Q_PROPERTY(QString symbol READ symbol NOTIFY changed)
    Q_PROPERTY(double price READ price NOTIFY changed)
    Q_PROPERTY(QString provider READ provider NOTIFY changed)
    Q_PROPERTY(QString message READ message NOTIFY changed)
    Q_PROPERTY(qint64 receivedAtMs READ receivedAtMs NOTIFY changed)
public:
    explicit LiveQuoteService(QObject* parent = nullptr);
    [[nodiscard]] bool running() const noexcept { return timer_.isActive(); }
    [[nodiscard]] bool loading() const noexcept { return reply_ != nullptr; }
    [[nodiscard]] QString symbol() const { return symbol_; }
    [[nodiscard]] double price() const noexcept { return price_; }
    [[nodiscard]] QString provider() const { return provider_; }
    [[nodiscard]] QString message() const { return message_; }
    [[nodiscard]] qint64 receivedAtMs() const noexcept { return receivedAtMs_; }
    Q_INVOKABLE void start(const QString& symbol);
    Q_INVOKABLE void stop();
    Q_INVOKABLE void refresh();
signals:
    void changed();
private:
    QNetworkAccessManager network_;
    QNetworkReply* reply_{};
    QTimer timer_;
    QString symbol_{QStringLiteral("BTC/USDT")};
    QString provider_;
    QString message_{QStringLiteral("دادهٔ زنده خاموش است")};
    double price_{};
    qint64 receivedAtMs_{};
};

} // namespace aurum
