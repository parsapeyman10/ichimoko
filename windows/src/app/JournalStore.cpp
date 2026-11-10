#include "app/JournalStore.h"

#include <QSqlError>
#include <QSqlQuery>
#include <QUuid>

namespace aurum {

JournalStore::JournalStore(QString databasePath) {
    const auto connection = QStringLiteral("aurum_journal_%1").arg(QUuid::createUuid().toString(QUuid::WithoutBraces));
    db_ = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), connection);
    db_.setDatabaseName(databasePath);
    if (!db_.open()) {
        lastError_ = db_.lastError().text();
        return;
    }
    QSqlQuery query(db_);
    valid_ = query.exec(QStringLiteral(
        "CREATE TABLE IF NOT EXISTS journal_trades ("
        "id INTEGER PRIMARY KEY AUTOINCREMENT, symbol TEXT NOT NULL, side TEXT NOT NULL,"
        "entry REAL NOT NULL, stop REAL NOT NULL, target REAL NOT NULL, exit REAL NOT NULL,"
        "net_pnl REAL NOT NULL, closed_at_ms INTEGER NOT NULL)"));
    if (!valid_) lastError_ = query.lastError().text();
}

JournalStore::~JournalStore() {
    const auto connection = db_.connectionName();
    db_.close();
    db_ = {};
    QSqlDatabase::removeDatabase(connection);
}

bool JournalStore::addTrade(const QString& symbol, const QString& side, const double entry,
    const double stop, const double target, const double exitPrice, const double netPnl,
    const std::int64_t closedAtMs) {
    if (!valid_ || symbol.trimmed().isEmpty() || entry <= 0 || exitPrice <= 0 || closedAtMs <= 0) return false;
    QSqlQuery query(db_);
    query.prepare(QStringLiteral("INSERT INTO journal_trades"
        "(symbol,side,entry,stop,target,exit,net_pnl,closed_at_ms) VALUES(?,?,?,?,?,?,?,?)"));
    query.addBindValue(symbol.trimmed().toUpper());
    query.addBindValue(side == QStringLiteral("SELL") ? QStringLiteral("SELL") : QStringLiteral("BUY"));
    query.addBindValue(entry);
    query.addBindValue(stop);
    query.addBindValue(target);
    query.addBindValue(exitPrice);
    query.addBindValue(netPnl);
    query.addBindValue(QVariant::fromValue(closedAtMs));
    if (!query.exec()) {
        lastError_ = query.lastError().text();
        return false;
    }
    return true;
}

QVariantList JournalStore::recentTrades(const int limit) const {
    QVariantList rows;
    if (!valid_) return rows;
    QSqlQuery query(db_);
    query.prepare(QStringLiteral("SELECT symbol,side,entry,stop,target,exit,net_pnl,closed_at_ms "
        "FROM journal_trades ORDER BY closed_at_ms DESC LIMIT ?"));
    query.addBindValue(std::max(1, std::min(limit, 1000)));
    if (!query.exec()) return rows;
    while (query.next()) {
        rows.push_back(QVariantMap{
            {QStringLiteral("symbol"), query.value(0)}, {QStringLiteral("side"), query.value(1)},
            {QStringLiteral("entry"), query.value(2)}, {QStringLiteral("stop"), query.value(3)},
            {QStringLiteral("target"), query.value(4)}, {QStringLiteral("exit"), query.value(5)},
            {QStringLiteral("netPnl"), query.value(6)}, {QStringLiteral("closedAt"), query.value(7)},
        });
    }
    return rows;
}

QVariantList JournalStore::tradesBetween(const std::int64_t startMs, const std::int64_t endMs) const {
    QVariantList rows;
    if (!valid_ || startMs < 0 || endMs <= startMs) return rows;
    QSqlQuery query(db_);
    query.prepare(QStringLiteral("SELECT symbol,side,entry,stop,target,exit,net_pnl,closed_at_ms "
        "FROM journal_trades WHERE closed_at_ms >= ? AND closed_at_ms < ? ORDER BY closed_at_ms DESC"));
    query.addBindValue(QVariant::fromValue(startMs));
    query.addBindValue(QVariant::fromValue(endMs));
    if (!query.exec()) return rows;
    while (query.next()) {
        rows.push_back(QVariantMap{
            {QStringLiteral("symbol"), query.value(0)}, {QStringLiteral("side"), query.value(1)},
            {QStringLiteral("entry"), query.value(2)}, {QStringLiteral("stop"), query.value(3)},
            {QStringLiteral("target"), query.value(4)}, {QStringLiteral("exit"), query.value(5)},
            {QStringLiteral("netPnl"), query.value(6)}, {QStringLiteral("closedAt"), query.value(7)},
        });
    }
    return rows;
}

} // namespace aurum
