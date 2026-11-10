#pragma once

#include <QSqlDatabase>
#include <QString>
#include <QVariantList>

namespace aurum {

class JournalStore final {
public:
    explicit JournalStore(QString databasePath);
    ~JournalStore();
    [[nodiscard]] bool valid() const noexcept { return valid_; }
    [[nodiscard]] QString lastError() const { return lastError_; }
    bool addTrade(const QString& symbol, const QString& side, double entry, double stop,
        double target, double exitPrice, double netPnl, std::int64_t closedAtMs);
    [[nodiscard]] QVariantList recentTrades(int limit = 200) const;

private:
    QSqlDatabase db_;
    bool valid_{};
    QString lastError_;
};

} // namespace aurum
