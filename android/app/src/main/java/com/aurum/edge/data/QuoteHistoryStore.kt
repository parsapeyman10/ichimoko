package com.aurum.edge.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Indexed on-device history of actual successful provider observations, never generated bars.
 * No artificial count cap; pages are loaded on demand. Clearing it is an explicit user action.
 */
class QuoteHistoryStore(context: Context) : SQLiteOpenHelper(context, "watch_quotes.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE quote_history (
            symbol_id TEXT NOT NULL, source_id TEXT NOT NULL, observed_at INTEGER NOT NULL,
            provider_code TEXT NOT NULL, label TEXT NOT NULL, price REAL NOT NULL,
            change_pct REAL, volume REAL, unit TEXT NOT NULL, provider_at INTEGER,
            PRIMARY KEY(symbol_id, source_id, observed_at)
        )""".trimIndent())
        db.execSQL("CREATE INDEX history_recent ON quote_history(symbol_id, observed_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future versions must add non-destructive migrations: market observations are user data.
        error("No migration from $oldVersion to $newVersion")
    }

    suspend fun append(symbolId: String, quote: Quote) = withContext(Dispatchers.IO) {
        val price = quote.price ?: return@withContext
        if (!price.isFinite() || price <= 0.0 || quote.stale || quote.error != null) return@withContext
        val values = ContentValues().apply {
            put("symbol_id", symbolId)
            put("source_id", quote.sourceId)
            put("observed_at", quote.ts)
            put("provider_code", quote.code)
            put("label", quote.label)
            put("price", price)
            quote.changePct?.let { put("change_pct", it) }
            quote.volume?.let { put("volume", it) }
            put("unit", quote.unit)
            quote.providerAt?.let { put("provider_at", it) }
        }
        writableDatabase.insertWithOnConflict("quote_history", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    suspend fun latest(symbolId: String, sourceId: String): Quote? = withContext(Dispatchers.IO) {
        val rows = query(symbolId, sourceId, null, 1)
        rows.firstOrNull()
    }

    suspend fun page(symbolId: String, sourceId: String, before: Long? = null, limit: Int = 50): List<Quote> =
        withContext(Dispatchers.IO) { query(symbolId, sourceId, before, limit.coerceIn(1, 100)) }

    suspend fun count(symbolId: String, sourceId: String): Long = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM quote_history WHERE symbol_id=? AND source_id=?",
            arrayOf(symbolId, sourceId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { writableDatabase.delete("quote_history", null, null) }

    private fun query(symbolId: String, sourceId: String, before: Long?, limit: Int): List<Quote> {
        val where = "symbol_id=? AND source_id=?" + if (before != null) " AND observed_at<?" else ""
        val args = if (before != null) arrayOf(symbolId, sourceId, before.toString()) else arrayOf(symbolId, sourceId)
        return readableDatabase.query(
            "quote_history", null, where, args, null, null, "observed_at DESC", limit.toString(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toQuote()) } }
    }

    private fun Cursor.doubleOrNull(name: String): Double? = getColumnIndexOrThrow(name).let { index ->
        if (isNull(index)) null else getDouble(index)
    }

    private fun Cursor.longOrNull(name: String): Long? = getColumnIndexOrThrow(name).let { index ->
        if (isNull(index)) null else getLong(index)
    }

    private fun Cursor.toQuote(): Quote = Quote(
        code = getString(getColumnIndexOrThrow("provider_code")),
        label = getString(getColumnIndexOrThrow("label")),
        price = getDouble(getColumnIndexOrThrow("price")),
        changePct = doubleOrNull("change_pct"),
        volume = doubleOrNull("volume"),
        unit = getString(getColumnIndexOrThrow("unit")),
        sourceId = getString(getColumnIndexOrThrow("source_id")),
        ts = getLong(getColumnIndexOrThrow("observed_at")),
        providerAt = longOrNull("provider_at"),
        stale = true, // until a new provider fetch succeeds; never turn old cache into a live quote
    )
}
