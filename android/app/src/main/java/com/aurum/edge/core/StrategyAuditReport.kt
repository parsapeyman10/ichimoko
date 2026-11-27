package com.aurum.edge.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Read-only snapshot: a current default is a reference, not a reconstructed historical value. */
object StrategyAuditReport {
    private val json = Json { encodeDefaults = true; prettyPrint = true }

    @Serializable
    data class Parameter(val key: String, val reference: String?, val saved: String?, val resolved: String?, val neuralProfile: String?)

    @Serializable
    data class Strategy(
        val id: String,
        val titleFa: String,
        val enabled: Boolean,
        val custom: Boolean,
        val baselineKnown: Boolean = false,
        val referenceDefaults: BaseStrategyConfig?,
        val savedConfig: BaseStrategyConfig,
        val resolvedConfig: BaseStrategyConfig,
        val neuralProfile: PerStrategyNeuralTuningProfile?,
        val parameters: List<Parameter>,
    )

    private fun fields(config: BaseStrategyConfig): Map<String, String> =
        json.encodeToJsonElement(BaseStrategyConfig.serializer(), config).jsonObject
            .filterValues { it is JsonPrimitive }.mapValues { it.value.toString() }

    private fun profileFields(profile: PerStrategyNeuralTuningProfile?): Map<String, String> =
        profile?.let { p -> json.encodeToJsonElement(PerStrategyConfigOverride.serializer(), p.toConfigOverride()).jsonObject
            .filterValues { it is JsonPrimitive }.mapValues { it.value.toString() } }.orEmpty()

    fun build(settings: AppSettings, summary: NeuralLearningStateSummary): List<Strategy> {
        val profiles = summary.autoTunedParameters.perStrategyProfiles
        val models = BitcoinStrategyModel.entries.map { model ->
            val config = settings.baseStrategyConfig
            entry(model.id, model.titleFa, false, config, profiles[model.id], settings)
        }
        val customs = settings.customStrategies.map { slot ->
            entry(slot.id, slot.titleFa, true, slot.config, profiles[slot.id], settings)
        }
        return models + customs
    }

    private fun entry(id: String, title: String, custom: Boolean, saved: BaseStrategyConfig,
                      profile: PerStrategyNeuralTuningProfile?, settings: AppSettings): Strategy {
        // Historical pre-tuning settings were not saved. Current code defaults are only a reference.
        val reference = if (custom) null else BaseStrategyConfig()
        val resolved = saved.resolveForStrategy(id)
        val old = reference?.let(::fields).orEmpty()
        val now = fields(saved)
        val effective = fields(resolved)
        val proposal = profileFields(profile)
        val keys = (old.keys + now.keys + effective.keys + proposal.keys).toSortedSet()
        val rows = keys.map { Parameter(it, old[it], now[it], effective[it], proposal[it]) }
        val enabled = if (settings.runAllStrategiesSimultaneously) {
            val knownIds = BitcoinStrategyModel.entries.map { it.id }.toSet() + settings.customStrategies.map { it.id }
            if (settings.enabledStrategyIds.none { it in knownIds }) id in BitcoinStrategyModel.activeStrategyIds || custom
            else id in settings.enabledStrategyIds
        } else settings.selectedBitcoinStrategyId == id
        return Strategy(id, title, enabled, custom, false, reference, saved, resolved, profile, rows)
    }
}
