package com.aurum.edge.core

/** Applies the user-controlled neural veto only to eligible automatic entries. */
object NeuralAutomaticEntryGate {
    fun blockReason(automatic: Boolean, neuralEligible: Boolean, initialized: Boolean,
                    active: Boolean, hasMatchingSnapshot: Boolean, evaluation: NeuralSignalEvaluation?): String? {
        if (!automatic || !neuralEligible) return null
        if (!initialized) return "تنظیم منع شبکه هنوز آماده نشده؛ ورود خودکار بعداً ارزیابی می‌شود."
        if (!active) return null
        if (!hasMatchingSnapshot || evaluation == null) return "گزارش عصبی هم‌زمان و معتبر موجود نیست؛ ورود خودکار تا ارزیابی معتبر متوقف است."
        return if (evaluation.isBlockingTradeNow) evaluation.noTradeWarningFa ?: evaluation.summaryFa else null
    }
}
