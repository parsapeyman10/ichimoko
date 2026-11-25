package com.aurum.edge.data

import android.content.Context
import com.aurum.edge.core.AssistantEvidenceReview
import org.json.JSONArray
import org.json.JSONObject

/** Diagnostic choice + bounded, durable audit; contains no trading configuration. */
object AssistantReviewStore {
    @Synchronized fun record(context: Context, report: AssistantEvidenceReview.Report, now: Long): String {
        val prefs = context.getSharedPreferences("assistant_review", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", true)
        val selected = if (enabled) report.selected.name else AssistantEvidenceReview.Source.BASELINE.name
        val previous = prefs.getString("selected", AssistantEvidenceReview.Source.BASELINE.name) ?: AssistantEvidenceReview.Source.BASELINE.name
        if (previous != selected) {
            val log = runCatching { JSONArray(prefs.getString("audit", "[]")) }.getOrDefault(JSONArray())
            log.put(JSONObject().put("at", now).put("from", previous).put("to", selected)
                .put("samples", report.eligible).put("folds", JSONArray().apply {
                    report.folds.forEach { f -> put(JSONObject().put("train", f.train).put("test", f.test)
                        .put("baseline", f.baseline).put("neuralBrier", f.neuralBrier).put("baselineBrier", f.baselineBrier)) }
                }).put("reason", if (enabled) report.reasonFa else "بازگشت دستی به پایه"))
            val retained = JSONArray()
            for (i in maxOf(0, log.length() - 50) until log.length()) retained.put(log.getJSONObject(i))
            prefs.edit().putString("selected", selected).putString("audit", retained.toString()).commit()
        }
        return selected
    }
    @Synchronized fun recordProspective(context: Context, records: List<com.aurum.edge.core.AssistantProspectiveExperiment.Record>, now: Long): com.aurum.edge.core.AssistantProspectiveExperiment.Result {
        val prefs = context.getSharedPreferences("assistant_review", Context.MODE_PRIVATE)
        val before = runCatching { AssistantEvidenceReview.Source.valueOf(prefs.getString("selected", "BASELINE") ?: "BASELINE") }
            .getOrDefault(AssistantEvidenceReview.Source.BASELINE)
        val result = com.aurum.edge.core.AssistantProspectiveExperiment.evaluate(records, before)
        val selected = if (prefs.getBoolean("enabled", true)) result.proposed else AssistantEvidenceReview.Source.BASELINE
        if (before != selected) {
            val log = runCatching { JSONArray(prefs.getString("audit", "[]")) }.getOrDefault(JSONArray())
            log.put(JSONObject().put("at", now).put("from", before.name).put("to", selected.name)
                .put("samples", result.count).put("evaluation", "prospective_rolling")
                .put("neuralBrier", result.neuralBrier).put("baselineBrier", result.baselineBrier)
                .put("reason", if (prefs.getBoolean("enabled", true)) result.reasonFa else "بازگشت دستی به پایه"))
            val retained = JSONArray()
            for (i in maxOf(0, log.length() - 50) until log.length()) retained.put(log.getJSONObject(i))
            check(prefs.edit().putString("selected", selected.name).putString("audit", retained.toString()).commit())
        }
        return result.copy(proposed = selected)
    }
    fun enabled(context: Context) = context.getSharedPreferences("assistant_review", Context.MODE_PRIVATE).getBoolean("enabled", true)
    @Synchronized fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("assistant_review", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).commit()
    }
    fun history(context: Context): String = context.getSharedPreferences("assistant_review", Context.MODE_PRIVATE).getString("audit", "[]") ?: "[]"
}
