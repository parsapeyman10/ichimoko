package com.aurum.edge.data

import android.content.Context
import android.util.AtomicFile
import com.aurum.edge.core.PaperOpportunity
import com.aurum.edge.core.PaperTrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Durable dedup for 9/9 alerts; not part of the executed-paper-trade journal statistics. */
class PaperOpportunityStore(context: Context,
                            private val file: File = File(context.filesDir, "paper_opportunities.json")) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private var loaded = false
    private val _items = MutableStateFlow<List<PaperOpportunity>>(emptyList())
    val items: StateFlow<List<PaperOpportunity>> = _items.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded) return@withLock
            val parsed = try {
                if (file.exists() || File(file.path + ".bak").exists()) {
                    json.decodeFromString(ListSerializer(PaperOpportunity.serializer()),
                        AtomicFile(file).openRead().bufferedReader().use { it.readText() })
                } else emptyList()
            } catch (error: Exception) {
                _loadError.value = "فایل فرصت‌های آموزشی خوانده نشد؛ هشدار تازه برای جلوگیری از تکرار متوقف است"
                throw IllegalStateException(_loadError.value, error)
            }
            _items.value = parsed.sortedByDescending { it.alertedAt }
            _loadError.value = null
            loaded = true
        }
    }

    private suspend fun save(next: List<PaperOpportunity>) = withContext(Dispatchers.IO) {
        check(loaded && _loadError.value == null) { "فرصت‌های آموزشی بارگذاری نشده‌اند؛ هشدار تکراری ممنوع است" }
        val kept = next.sortedByDescending { it.alertedAt }.take(200)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.encodeToString(ListSerializer(PaperOpportunity.serializer()), kept).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
        _items.value = kept
    }

    /** Persist before posting the notification. A restart cannot alert the same bar twice. */
    suspend fun record(opportunity: PaperOpportunity): Boolean = mutex.withLock {
        check(loaded && _loadError.value == null) { "ثبت فرصت‌های آموزشی امکان‌پذیر نیست" }
        if (_items.value.any { it.key == opportunity.key }) return@withLock false
        save(_items.value + opportunity)
        true
    }

    suspend fun linkTrade(trade: PaperTrade) = mutex.withLock {
        check(loaded && _loadError.value == null) { "فرصت‌های آموزشی بارگذاری نشده‌اند" }
        val key = "${trade.symbol}|${trade.interval.label}|${trade.signalBarTime}|${trade.action}"
        if (trade.signalBarTime == null || _items.value.none { it.key == key && it.paperTradeId == null })
            return@withLock
        save(_items.value.map { if (it.key == key) it.copy(paperTradeId = trade.id) else it })
    }

    suspend fun clear() = mutex.withLock { save(emptyList()) }
}
