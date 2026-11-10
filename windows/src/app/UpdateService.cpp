#include "app/UpdateService.h"

#include <QDesktopServices>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QUrl>

namespace aurum {
namespace {
QList<int> versionParts(QString value) {
    value.remove(QRegularExpression(QStringLiteral("^[vV]")));
    QList<int> parts;
    for (const auto& part : value.split(QLatin1Char('.'))) {
        bool ok{};
        const int number = part.toInt(&ok);
        parts.append(ok ? number : 0);
    }
    while (parts.size() < 3) parts.append(0);
    return parts;
}
bool newerThan(const QString& candidate, const QString& current) {
    const auto left = versionParts(candidate);
    const auto right = versionParts(current);
    for (int i = 0; i < 3; ++i) {
        if (left[i] != right[i]) return left[i] > right[i];
    }
    return false;
}
}

UpdateService::UpdateService(QObject* parent) : QObject(parent) {}

void UpdateService::check() {
    if (reply_) return;
    available_ = false;
    latestVersion_.clear();
    releaseUrl_.clear();
    message_ = QStringLiteral("در حال بررسی GitHub Releases…");
    QNetworkRequest request{QUrl(QStringLiteral("https://api.github.com/repos/parsapeyman10/ichimoko/releases/latest"))};
    request.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("AurumEdge-Windows/0.1"));
    request.setRawHeader("Accept", "application/vnd.github+json");
    request.setTransferTimeout(10'000);
    reply_ = network_.get(request);
    emit changed();
    connect(reply_, &QNetworkReply::finished, this, [this] {
        auto* completed = reply_;
        reply_ = nullptr;
        if (!completed) return;
        if (completed->error() == QNetworkReply::ContentNotFoundError) {
            message_ = QStringLiteral("هنوز Release عمومی برای ویندوز منتشر نشده است.");
        } else if (completed->error() != QNetworkReply::NoError) {
            message_ = QStringLiteral("بررسی بروزرسانی ناموفق: %1").arg(completed->errorString());
        } else {
            const auto root = QJsonDocument::fromJson(completed->readAll()).object();
            latestVersion_ = root.value(QStringLiteral("tag_name")).toString();
            releaseUrl_ = root.value(QStringLiteral("html_url")).toString();
            available_ = !latestVersion_.isEmpty() && newerThan(latestVersion_, currentVersion());
            message_ = available_ ? QStringLiteral("نسخهٔ جدید %1 پیدا شد؛ برای بررسی امضا و نصب، صفحهٔ Release را باز کنید.").arg(latestVersion_)
                : QStringLiteral("نسخهٔ جدیدتری پیدا نشد. آخرین Release: %1").arg(latestVersion_.isEmpty() ? QStringLiteral("—") : latestVersion_);
        }
        completed->deleteLater();
        emit changed();
    });
}

void UpdateService::openReleasePage() const {
    if (releaseUrl_.startsWith(QStringLiteral("https://github.com/"))) QDesktopServices::openUrl(QUrl(releaseUrl_));
}

} // namespace aurum
