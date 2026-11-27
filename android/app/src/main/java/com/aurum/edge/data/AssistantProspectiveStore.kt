package com.aurum.edge.data

import android.content.Context
import com.aurum.edge.core.AssistantProspectiveExperiment
import com.aurum.edge.core.PaperTrade
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Private durable pre-outcome evidence; writes only when observations or outcomes change. */
object AssistantProspectiveStore {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(AssistantProspectiveExperiment.Record.serializer())
    @Synchronized fun update(context: Context, trades: List<PaperTrade>, now: Long): List<AssistantProspectiveExperiment.Record> {
        val prefs = context.getSharedPreferences("assistant_prospective", Context.MODE_PRIVATE)
        val raw = prefs.getString("records", null)
        val old = if (raw == null) emptyList() else json.decodeFromString(serializer, raw)
        val next = AssistantProspectiveExperiment.update(old, trades, now)
        if (next != old) check(prefs.edit().putString("records", json.encodeToString(serializer, next)).commit())
        return next
    }
    @Synchronized fun export(context: Context): String =
        context.getSharedPreferences("assistant_prospective", Context.MODE_PRIVATE).getString("records", "[]") ?: "[]"
}
