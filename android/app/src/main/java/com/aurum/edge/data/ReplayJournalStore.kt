package com.aurum.edge.data

import android.content.Context
import android.util.AtomicFile
import com.aurum.edge.core.ReplayDecision
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

/** Durable educational cursor decisions, separate from live-price paper fills. */
class ReplayJournalStore(
    context: Context,
    private val file: File = File(context.filesDir, "replay_decisions.json"),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private var loaded = false
    private val _entries = MutableStateFlow<List<ReplayDecision>>(emptyList())
    val entries: StateFlow<List<ReplayDecision>> = _entries.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded) return@withLock
            val list = try {
                if (file.exists() || File(file.path + ".bak").exists()) {
                    json.decodeFromString(
                        ListSerializer(ReplayDecision.serializer()),
                        AtomicFile(file).openRead().bufferedReader().use { it.readText() },
                    )
                } else emptyList()
            } catch (error: Exception) {
                _loadError.value = "ژورنال تصمیم‌های replay خوانده نشد؛ فایل قبلی بازنویسی نمی‌شود"
                throw IllegalStateException(_loadError.value, error)
            }
            _entries.value = list.sortedByDescending { it.recordedAt }.take(MAX_ENTRIES)
            _loadError.value = null
            loaded = true
        }
    }

    suspend fun append(entry: ReplayDecision) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(loaded && _loadError.value == null) {
                "ژورنال replay بارگذاری نشده/آسیب‌دیده است؛ فایل موجود پاک نمی‌شود"
            }
            val next = (_entries.value + entry).sortedByDescending { it.recordedAt }.take(MAX_ENTRIES)
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(json.encodeToString(ListSerializer(ReplayDecision.serializer()), next)
                    .toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
            _entries.value = next
        }
    }

    suspend fun updateOutcome(
        id: String,
        outcomeStatus: String,
        fillBarTime: Long?,
        fillPrice: Double?,
        outcomeBarTime: Long?,
        outcomePrice: Double?,
        outcomeReason: String?,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(loaded && _loadError.value == null) {
                "ژورنال replay بارگذاری نشده/آسیب‌دیده است؛ فایل موجود پاک نمی‌شود"
            }
            val current = _entries.value.firstOrNull { it.id == id } ?: return@withLock
            val nextValue = current.copy(
                outcomeStatus = outcomeStatus,
                fillBarTime = fillBarTime,
                fillPrice = fillPrice,
                outcomeBarTime = outcomeBarTime,
                outcomePrice = outcomePrice,
                outcomeReason = outcomeReason,
            )
            if (nextValue == current) return@withLock
            val next = _entries.value.map { if (it.id == id) nextValue else it }
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(json.encodeToString(ListSerializer(ReplayDecision.serializer()), next)
                    .toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
            _entries.value = next
        }
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}
