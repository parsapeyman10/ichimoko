#pragma once

#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QObject>

namespace aurum {

class UpdateService final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool loading READ loading NOTIFY changed)
    Q_PROPERTY(bool available READ available NOTIFY changed)
    Q_PROPERTY(QString currentVersion READ currentVersion CONSTANT)
    Q_PROPERTY(QString latestVersion READ latestVersion NOTIFY changed)
    Q_PROPERTY(QString releaseUrl READ releaseUrl NOTIFY changed)
    Q_PROPERTY(QString message READ message NOTIFY changed)
public:
    explicit UpdateService(QObject* parent = nullptr);
    [[nodiscard]] bool loading() const noexcept { return reply_ != nullptr; }
    [[nodiscard]] bool available() const noexcept { return available_; }
    [[nodiscard]] QString currentVersion() const { return QStringLiteral("0.1.0"); }
    [[nodiscard]] QString latestVersion() const { return latestVersion_; }
    [[nodiscard]] QString releaseUrl() const { return releaseUrl_; }
    [[nodiscard]] QString message() const { return message_; }
    Q_INVOKABLE void check();
    Q_INVOKABLE void openReleasePage() const;
signals:
    void changed();
private:
    QNetworkAccessManager network_;
    QNetworkReply* reply_{};
    bool available_{};
    QString latestVersion_;
    QString releaseUrl_;
    QString message_{QStringLiteral("بررسی دستی؛ نصب خودکار انجام نمی‌شود")};
};

} // namespace aurum
