#include "app/AppController.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QStandardPaths>
#include <QUrl>
#include <QtConcurrent/QtConcurrent>

namespace aurum {

AppController::AppController(QObject* parent) : QObject(parent) {
    const auto root = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir().mkpath(root);
    journal_ = std::make_unique<JournalStore>(root + QStringLiteral("/journal.sqlite"));
    connect(&loadWatcher_, &QFutureWatcher<LoadOutcome>::finished, this, [this] {
        const auto outcome = loadWatcher_.result();
        busy_ = false;
        if (!outcome.error.isEmpty()) {
            setStatus(outcome.error);
        } else {
            data_ = outcome.data;
            fileName_ = outcome.fileName;
            result_.reset();
            resultSummary_.clear();
            setStatus(QStringLiteral("%1 کندل معتبر | %2 | ردیف حذف‌شده: %3")
                .arg(data_->candles.size()).arg(QString::fromStdString(std::string(label(data_->inferredInterval))))
                .arg(data_->skippedRows + data_->duplicateRows + data_->flatRowsDropped));
            emit dataChanged();
            emit resultChanged();
        }
        emit stateChanged();
    });
    connect(&backtestWatcher_, &QFutureWatcher<BacktestOutcome>::finished, this, [this] {
        const auto outcome = backtestWatcher_.result();
        busy_ = false;
        if (!outcome.error.isEmpty()) {
            setStatus(outcome.error);
        } else {
            result_ = outcome.result;
            const auto& s = result_->stats;
            resultSummary_ = QStringLiteral("معامله: %1 | برد: %2% | PF: %3 | انتظار: %4R | خالص: %5$ | DD: %6%")
                .arg(s.trades).arg(s.winRate, 0, 'f', 2).arg(s.profitFactor, 0, 'f', 3)
                .arg(s.expectancyR, 0, 'f', 3).arg(s.netPnl, 0, 'f', 2).arg(s.maxDrawdownPct, 0, 'f', 2);
            setStatus(QStringLiteral("بک‌تست کامل شد؛ حساب نهایی: %1$").arg(s.finalEquity, 0, 'f', 2));
            emit resultChanged();
        }
        emit stateChanged();
    });
}

int AppController::candleCount() const noexcept {
    return data_ ? static_cast<int>(data_->candles.size()) : 0;
}

QString AppController::modelName() const {
    return result_ ? QString::fromStdString(result_->modelName)
        : QStringLiteral("Ichimoku 8/24/72 baseline — نسخهٔ مستقل، نه Peyman");
}

QVariantList AppController::chartCandles() const {
    QVariantList rows;
    if (!data_) return rows;
    constexpr std::size_t limit = 1500;
    const auto first = data_->candles.size() > limit ? data_->candles.size() - limit : 0;
    rows.reserve(static_cast<qsizetype>(data_->candles.size() - first));
    for (std::size_t i = first; i < data_->candles.size(); ++i) {
        const auto& b = data_->candles[i];
        rows.push_back(QVariantMap{{"time", QVariant::fromValue(b.timeMs)}, {"open", b.open},
            {"high", b.high}, {"low", b.low}, {"close", b.close}, {"volume", b.volume}});
    }
    return rows;
}

QVariantList AppController::backtestTrades() const {
    QVariantList rows;
    if (!result_) return rows;
    for (const auto& t : result_->trades) {
        rows.push_back(QVariantMap{{"side", t.side == Side::buy ? "BUY" : "SELL"},
            {"entryTime", QVariant::fromValue(t.entryTimeMs)}, {"entry", t.entry}, {"exit", t.exit},
            {"netPnl", t.netPnl}, {"r", t.rMultiple}, {"fees", t.fees}});
    }
    return rows;
}

QVariantList AppController::journalTrades() const {
    return journal_ ? journal_->recentTrades() : QVariantList{};
}

void AppController::setStatus(QString text) {
    status_ = std::move(text);
    emit stateChanged();
}

void AppController::loadCsv(const QString& path) {
    if (busy_) return;
    auto local = QUrl::fromUserInput(path).isLocalFile() ? QUrl::fromUserInput(path).toLocalFile() : path;
    QFileInfo info(local);
    if (!info.isFile()) { setStatus(QStringLiteral("فایل پیدا نشد")); return; }
    busy_ = true;
    setStatus(QStringLiteral("در حال خواندن CSV روی Worker Thread…"));
    emit stateChanged();
    loadWatcher_.setFuture(QtConcurrent::run([local, name = info.fileName()]() -> LoadOutcome {
        auto outcome = LoadOutcome{nullptr, name, {}};
        try {
            QFile file(local);
            if (!file.open(QIODevice::ReadOnly)) throw std::runtime_error("Cannot open CSV");
            const auto size = file.size();
            if (size <= 0) throw std::invalid_argument("CSV is empty");
            auto* mapped = file.map(0, size);
            if (!mapped) throw std::runtime_error("Cannot memory-map CSV");
            const std::string_view raw(reinterpret_cast<const char*>(mapped), static_cast<std::size_t>(size));
            outcome.data = std::make_shared<ParseResult>(parseDukascopyCsv(raw, name.toStdString()));
            file.unmap(mapped);
        } catch (const std::exception& error) {
            outcome.error = QStringLiteral("خطای CSV: %1").arg(QString::fromUtf8(error.what()));
        }
        return outcome;
    }));
}

void AppController::runBacktest() {
    if (busy_ || !data_) { if (!data_) setStatus(QStringLiteral("ابتدا CSV بارگذاری کنید")); return; }
    busy_ = true;
    setStatus(QStringLiteral("بک‌تست در حال اجرا؛ UI آزاد است…"));
    emit stateChanged();
    const auto input = data_;
    backtestWatcher_.setFuture(QtConcurrent::run([input]() -> BacktestOutcome {
        auto outcome = BacktestOutcome{nullptr, {}};
        try {
            outcome.result = std::make_shared<BacktestResult>(runIchimokuBaseline(input->candles));
        } catch (const std::exception& error) {
            outcome.error = QStringLiteral("خطای بک‌تست: %1").arg(QString::fromUtf8(error.what()));
        }
        return outcome;
    }));
}

bool AppController::addJournalTrade(const QString& symbol, const QString& side, const double entry,
    const double stop, const double target, const double exitPrice, const double netPnl) {
    if (!journal_ || !journal_->addTrade(symbol, side, entry, stop, target, exitPrice, netPnl,
        QDateTime::currentMSecsSinceEpoch())) {
        setStatus(journal_ ? journal_->lastError() : QStringLiteral("ژورنال در دسترس نیست"));
        return false;
    }
    setStatus(QStringLiteral("معامله در SQLite ثبت شد"));
    emit journalChanged();
    return true;
}

} // namespace aurum
