package com.aurum.edge.data

import android.content.Context
import com.aurum.edge.AurumApplication
import com.aurum.edge.core.PaperTrade
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

object DailyBackupSnapshot {
    /** Secrets are never included, even inside the encrypted backup. */
    internal fun allowedSettingKey(key: String): Boolean {
        val k = key.lowercase()
        return listOf("key", "token", "password", "secret", "credential").none { it in k }
    }
    suspend fun make(context: Context): ByteArray {
        val app = context.applicationContext as AurumApplication
        val container = app.container
        // Never export the partial startup view before the persisted journal shards have merged.
        if (withTimeoutOrNull(120_000L) { container.journalStore.shardsMerged.first { it } } == null)
            throw IOException("ادغام ژورنال هنوز کامل نشده است")
        val json = Json { encodeDefaults = true }
        val tradesJson = json.encodeToString(ListSerializer(PaperTrade.serializer()), container.journalStore.trades.value)
        val neuralJson = container.neuralEngine.exportFullStateJson()
        val preferences = JSONObject()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).all
            .filterKeys(::allowedSettingKey).forEach { (k, v) ->
                preferences.put(k, when (v) { is Set<*> -> JSONArray(v.toList()); else -> v })
            }
        val auxiliary = JSONObject()
        for (path in listOf("ai_trader/trader_memory.json", "session_exit_learning_v1.json")) {
            val file = File(context.filesDir, path)
            if (file.exists()) auxiliary.put(path, JSONObject(file.readText()))
        }
        val snapshot = JSONObject().put("schema", 1).put("createdAtUtcMs", System.currentTimeMillis())
            .put("consistency", "Component snapshots are independently captured, not a global atomic transaction")
            .put("trades", JSONArray(tradesJson)).put("neural", JSONObject(neuralJson))
            .put("settings", preferences).put("otherLearning", auxiliary)
            .put("assistantReviewAudit", JSONArray(AssistantReviewStore.history(context)))
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(snapshot.toString().toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }
}
