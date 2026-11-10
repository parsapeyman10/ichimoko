#pragma once

#include "app/JournalStore.h"
#include "core/BacktestEngine.h"
#include "core/CsvReader.h"

#include <QFutureWatcher>
#include <QObject>
#include <QVariantList>
#include <memory>

namespace aurum {

class AppController final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool busy READ busy NOTIFY stateChanged)
    Q_PROPERTY(bool hasData READ hasData NOTIFY stateChanged)
    Q_PROPERTY(QString status READ status NOTIFY stateChanged)
    Q_PROPERTY(QString fileName READ fileName NOTIFY stateChanged)
    Q_PROPERTY(QString resultSummary READ resultSummary NOTIFY stateChanged)
    Q_PROPERTY(QString modelName READ modelName NOTIFY stateChanged)
    Q_PROPERTY(int candleCount READ candleCount NOTIFY stateChanged)
    Q_PROPERTY(QVariantList chartCandles READ chartCandles NOTIFY dataChanged)
    Q_PROPERTY(QVariantList backtestTrades READ backtestTrades NOTIFY resultChanged)
    Q_PROPERTY(QVariantList journalTrades READ journalTrades NOTIFY journalChanged)
public:
    explicit AppController(QObject* parent = nullptr);
    [[nodiscard]] bool busy() const noexcept { return busy_; }
    [[nodiscard]] bool hasData() const noexcept { return static_cast<bool>(data_); }
    [[nodiscard]] QString status() const { return status_; }
    [[nodiscard]] QString fileName() const { return fileName_; }
    [[nodiscard]] QString resultSummary() const { return resultSummary_; }
    [[nodiscard]] QString modelName() const;
    [[nodiscard]] int candleCount() const noexcept;
    [[nodiscard]] QVariantList chartCandles() const;
    [[nodiscard]] QVariantList backtestTrades() const;
    [[nodiscard]] QVariantList journalTrades() const;

    Q_INVOKABLE void loadCsv(const QString& path);
    Q_INVOKABLE void runBacktest();
    Q_INVOKABLE QVariantMap calculateRisk(const QString& symbol, double accountBalance,
        double riskPercent, double entry, double stopLoss, double takeProfit) const;
    Q_INVOKABLE bool exportJournal(const QString& path);
    Q_INVOKABLE bool addJournalTrade(const QString& symbol, const QString& side, double entry,
        double stop, double target, double exitPrice, double netPnl);
signals:
    void stateChanged();
    void dataChanged();
    void resultChanged();
    void journalChanged();
private:
    struct LoadOutcome {
        std::shared_ptr<ParseResult> data;
        QString fileName;
        QString error;
    };
    struct BacktestOutcome {
        std::shared_ptr<BacktestResult> result;
        QString error;
    };
    void setStatus(QString text);
    bool busy_{};
    QString status_{QStringLiteral("آماده")};
    QString fileName_;
    QString resultSummary_;
    std::shared_ptr<ParseResult> data_;
    std::shared_ptr<BacktestResult> result_;
    QFutureWatcher<LoadOutcome> loadWatcher_;
    QFutureWatcher<BacktestOutcome> backtestWatcher_;
    std::unique_ptr<JournalStore> journal_;
};

} // namespace aurum
