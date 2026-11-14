package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * # ماشهٔ «فشردگی → انبساط» برای استراتژی بیس
 *
 * ## چه مسئله‌ای را حل می‌کند
 * استراتژی بیس ورود را در «میانهٔ حرکت» می‌گیرد: پولبک به تعادل، شکست معتبر کومو، ادامهٔ
 * سانیاکو. اما شروع حرکت‌های انفجاری (Pump/Dump) با یک فشردگی واقعی و بعد شکست آن مشخص
 * می‌شود، و بیس برای این لحظه هیچ ماشهٔ مستقیمی نداشت.
 *
 * ## سه اصل طراحی
 * ۱. **بدون نگاه به آینده.** سقف/کف ناحیه فقط از کندل‌های بستهٔ *قبل از* کندل شکست ساخته
 *    می‌شود و پرسنتایل عرض بولینگر فقط از تاریخچهٔ قبل از همان کندل. هیچ جست‌وجوی
 *    «بهترین پنجره به‌صورت hindsight» انجام نمی‌شود: پنجره ثابت [lookbackBars] است.
 * ۲. **بدون حالت.** این موتور stateless است و در هر فراخوانی همه‌چیز را از تاریخچه
 *    بازمی‌سازد؛ بنابراین بک‌تست و اجرای زنده نتیجهٔ یکسان می‌دهند و rehydrate برنامه
 *    قید ضدتکرار را از بین نمی‌برد. تکرار با [CompressionExpansionAnalysis.setupId]
 *    کنترل می‌شود، نه با حافظهٔ پنهان.
 * ۳. **بی‌اثر وقتی خاموش است.** تمام محاسبات فقط وقتی انجام می‌شود که
 *    `CompressionExpansionConfig.enabled` روشن باشد؛ با خاموش‌بودن، موتور بیس بیت‌به‌بیت
 *    رفتار قبلی را دارد.
 *
 * ## چه چیزی **نیست**
 * Runner (بستن ۶۵٪ پوزیشن و رهاکردن بقیه) عمداً پیاده نشده است، چون `PaperTrade` پوزیشن
 * جزئی ندارد و شبیه‌سازی ظاهری آن ممنوع است. حد سود و خروج در همان دالان معمول موتور است.
 */
object CompressionExpansionEngine {

    // ─────────────────────────── تنظیمات ───────────────────────────

    /**
     * تنظیمات ماشهٔ فشردگی–انبساط.
     *
     * پیش‌فرض `enabled = false` است تا رفتار هیچ کاربری بدون درخواست صریح عوض نشود و
     * مقایسهٔ A/B (روشن/خاموش روی دادهٔ یکسان) ممکن باشد.
     *
     * `@Serializable` لازم است چون `BaseStrategyConfig` سریال می‌شود و هر فیلد تازه باید
     * سریالایزر داشته باشد؛ نبودِ آن کامپایل اندروید را می‌شکند (خطای ۶۶۸ در CI).
     */
    @Serializable
    data class Config(
        val enabled: Boolean = false,
        val lookbackBars: Int = 20,
        val minCompressionBars: Int = 10,
        val historyBars: Int = 100,
        val bbPeriod: Int = 20,
        val bbWidthPercentile: Double = 20.0,
        val maxRangeRatio: Double = 0.70,
        val maxAtrRatio: Double = 0.80,
        val maxTkGapAtr: Double = 0.30,
        val minCompressionScorePct: Double = 70.0,
        val breakoutBufferAtr: Double = 0.10,
        val minBreakoutVolumeRatio: Double = 1.50,
        val minBreakoutBodyRatio: Double = 0.55,
        val minBreakoutClvLong: Double = 0.70,
        val maxBreakoutClvShort: Double = 0.30,
        val minAtrExpansionRatio: Double = 1.15,
        val maxAdverseWickRatio: Double = 0.35,
        val maxDirectEntryDistanceAtr: Double = 0.80,
        val maxBreakoutCandleRangeAtr: Double = 2.50,
        val maxRetestWaitBars: Int = 5,
        val minRetestBodyRatio: Double = 0.45,
        val minRetestVolumeRatio: Double = 0.80,
        val failedExpansionBars: Int = 4,
        /** اجرای Runner — تا وقتی پوزیشن جزئی واقعی نداریم، روشن‌کردنش خطاست. */
        val runnerEnabled: Boolean = false,
        val runnerCloseFraction: Double = 0.65,
    ) {
        init {
            require(lookbackBars >= 2) { "lookbackBars باید دست‌کم ۲ باشد" }
            require(bbPeriod >= 5) { "bbPeriod باید دست‌کم ۵ باشد" }
        }

        /** حداقل کندل لازم برای تحلیل کامل. */
        val minBarsRequired: Int
            get() = max(historyBars, lookbackBars + 30) + bbPeriod + 2

        /**
         * آیا اجرا مجاز است؟
         *
         * Runner خواستهٔ سند بود، ولی موتور معامله پوزیشن جزئی ندارد؛ تا زمانی که نداشته
         * باشد روشن‌کردن آن فقط ادعاست، پس رد می‌شود.
         */
        fun withRunnerDisabled(): Config =
            if (runnerEnabled) copy(runnerEnabled = false, runnerCloseFraction = 0.0) else this
    }

    // ─────────────────────────── وضعیت‌ها ───────────────────────────

    enum class State(val labelFa: String) {
        OFF("خاموش"),
        FORMING("در حال شکل‌گیری"),
        READY("فشردگی آماده"),
        BREAKOUT_CONFIRMED("شکست تأییدشده"),
        WAITING_RETEST("انتظار بازآزمایی"),
        EXPANSION_ACTIVE("انبساط فعال"),
        FAILED_BREAKOUT("شکست ناموفق"),
        BLOCKED("مسدود"),
    }

    enum class EntryMode(val labelFa: String) {
        NONE("بدون ورود"),
        DIRECT_BREAKOUT("ورود مستقیم روی شکست"),
        WAIT_RETEST("انتظار بازآزمایی"),
        RETEST_CONFIRMED("ورود روی بازآزمایی"),
    }

    /**
     * دروازه‌های ساختاری که از بیرون (موتور بیس) تزریق می‌شوند.
     *
     * این موتور خودش ابر آینده، چیکو، رژیم تایم بالاتر و اقتصاد اجرا را نمی‌شناسد؛ همان
     * مقادیری را می‌گیرد که در `IchimokuStrategyEngine` سنجیده شده‌اند. این‌طور منطق
     * فشردگی خالص و آزمون‌شدنی می‌ماند و هیچ وتویی دور زده نمی‌شود.
     */
    data class Gates(
        /** رژیم تایم بالاتر آشکارا مخالف جهت معامله است. */
        val higherTfOpposes: Boolean = false,
        /** چیکو در مسیر معامله مسدود است. */
        val chikouBlocked: Boolean = false,
        /** ابر آیندهٔ مخالف شدید. */
        val futureKumoOpposes: Boolean = false,
        /** فاز روند: پایان روند (LATE_EXHAUSTION) — شروع انبساط با آن اشتباه نشود. */
        val lateExhaustion: Boolean = false,
        /** فضای باز تا نزدیک‌ترین مانع ساختاری بر حسب R (null = مانعی نیست). */
        val spaceToObstacleR: Double? = null,
        /** R:R پس از کسر هزینه‌ها (null = پلن ساخته نشده). */
        val riskRewardAfterCosts: Double? = null,
        /** پلن `TradePlanGuard` قابل‌اجراست. */
        val planUsable: Boolean = false,
    ) {
        companion object {
            val PERMISSIVE = Gates(planUsable = true, riskRewardAfterCosts = 2.0)
        }
    }

    // ─────────────────────────── خروجی ───────────────────────────

    data class Analysis(
        val state: State,
        val direction: SignalAction,
        val compressionScorePct: Double,
        val compressionHigh: Double?,
        val compressionLow: Double?,
        val compressionBars: Int,
        val breakoutPrice: Double?,
        val breakoutBarTime: Long?,
        val breakoutAtr: Double?,
        val distanceFromBreakoutAtr: Double?,
        val volumeRatio: Double?,
        val atrExpansionRatio: Double,
        val entryMode: EntryMode,
        val blockedReasonsFa: List<String>,
        val diagnosticsFa: String,
        /** حجم معتبر برای این نماد در دسترس است؟ */
        val volumeAvailable: Boolean,
        /** شناسهٔ یکتای این شکست؛ برای جلوگیری از ورود تکراری. */
        val setupId: String?,
        /** اگر ورود مجاز است، حد ضرر ساختاری پیشنهادی. */
        val suggestedStopLoss: Double?,
        /** بهای مقاومت/حمایت ناحیه برای ساخت پلن. */
        val breakoutLevel: Double?,
        val isEntryAllowed: Boolean,
    ) {
        val stateFa: String get() = state.labelFa
        val entryModeFa: String get() = entryMode.labelFa
    }

    // ─────────────────────────── نقطهٔ ورود ───────────────────────────

    /**
     * تحلیل کامل یک کندل.
     *
     * @param closedTriggerBars کندل‌های بستهٔ تایم ماشه، به ترتیب زمان؛ آخرین عضو **کندل
     *   جاری بسته‌شده** است. هیچ کندلی بعد از آن دیده نمی‌شود.
     * @param snapshot اسنپ‌شات همان کندل جاری (برای تنکان/کیجون/ATR ایچیموکو).
     * @param isLong جهت نامزد معامله.
     * @param noiseFloorPrice کف نویز اجرایی بر حسب قیمت (همان مبنای `TradePlanGuard`).
     * @param alreadyEnteredSetupIds شناسه‌های شکستی که قبلاً معامله شده‌اند.
     */
    fun analyze(
        closedTriggerBars: List<Candle>,
        snapshot: IchimokuBarSnapshot,
        config: Config,
        isLong: Boolean,
        gates: Gates = Gates(),
        noiseFloorPrice: Double = 0.0,
        alreadyEnteredSetupIds: Set<String> = emptySet(),
        symbol: String = "",
        strategyId: String = "",
        triggerIntervalLabel: String = "",
    ): Analysis {
        val cfg = config.withRunnerDisabled()
        val direction = if (isLong) SignalAction.BUY else SignalAction.SELL
        if (!cfg.enabled) {
            return off(direction)
        }
        val bars = closedTriggerBars
        val n = bars.size
        if (n < cfg.minBarsRequired) {
            return blank(
                state = State.FORMING,
                direction = direction,
                reason = "دادهٔ کافی نیست: ${n} کندل بسته، حداقل ${cfg.minBarsRequired} لازم است.",
            )
        }
        val atr = snapshot.atr14
        if (!atr.isFinite() || atr <= 0.0) {
            return blank(State.BLOCKED, direction, "ATR معتبر نیست؛ تشخیص فشردگی ممکن نیست.")
        }
        val currentIdx = n - 1
        val volumeAvailable = hasUsableVolume(bars, currentIdx)

        // ── ۱. ناحیهٔ فشردگی پیش از کندل جاری (کندل جاری در ناحیه شرکت نمی‌کند) ──
        val zone = zoneEndingBefore(bars, currentIdx, cfg.lookbackBars)
            ?: return blank(State.FORMING, direction, "تاریخچه برای ساخت ناحیهٔ فشردگی کافی نیست.")
        // امتیاز فشردگی مربوط به **ناحیهٔ پیش از کندل جاری** است، نه خود کندل جاری.
        // اگر کندل شکست (که طبعاً بزرگ است) داخل محاسبه بیاید، عرض بولینگر و دامنهٔ آن
        // «انبساط» را نشان می‌دهند و امتیاز فشردگی را می‌کشند — یعنی ماشه خودش را نفی
        // می‌کرد. پس امتیاز روی پنجره‌ای حساب می‌شود که به کندل قبل از جاری ختم می‌شود.
        val score = compressionScore(bars, currentIdx - 1, cfg, snapshot, zone)
        val compressionBars = compressionBars(bars, currentIdx, cfg)
        val zoneReady = score >= cfg.minCompressionScorePct &&
            compressionBars >= cfg.minCompressionBars &&
            zone.high > zone.low

        val baseAtr = meanTrueRange(bars, currentIdx - 1, 14)

        // ── ۲. شکست روی همین کندل جاری؟ ──
        val buffer = max(cfg.breakoutBufferAtr * atr, noiseFloorPrice.coerceAtLeast(0.0))
        val level = if (isLong) zone.high + buffer else zone.low - buffer
        val currentBar = bars[currentIdx]
        val currentRange = currentBar.high - currentBar.low
        val bodyRatio = bodyRatio(currentBar)
        val clv = closeLocationValue(currentBar)
        val atrExpansion = if (baseAtr > 0.0) atr / baseAtr else 1.0
        val volRatio = volumeRatio(bars, currentIdx, 20)
        val adverseWick = if (isLong) upperWickRatio(currentBar) else lowerWickRatio(currentBar)

        val closeBeyond = if (isLong) currentBar.close > level else currentBar.close < level
        val directionOk = if (isLong) currentBar.close > currentBar.open else currentBar.close < currentBar.open
        val bodyOk = bodyRatio >= cfg.minBreakoutBodyRatio
        val clvOk = if (isLong) clv >= cfg.minBreakoutClvLong else clv <= cfg.maxBreakoutClvShort
        val volumeOk = volumeAvailable && volRatio != null && volRatio >= cfg.minBreakoutVolumeRatio
        val expansionOk = atrExpansion >= cfg.minAtrExpansionRatio || currentRange >= 1.50 * zone.medianRange
        val wickOk = adverseWick <= cfg.maxAdverseWickRatio

        val reasons = ArrayList<String>()
        if (!directionOk) reasons += "کندل شکست در جهت معامله بسته نشده"
        if (!bodyOk) reasons += "بدنهٔ کندل شکست ضعیف است (${pct(bodyRatio)} < ${pct(cfg.minBreakoutBodyRatio)})"
        if (!clvOk) reasons += "کلوز در انتهای کندل نیست (CLV=${pct(clv)})"
        if (!volumeAvailable) reasons += "حجم معتبر در دسترس نیست؛ ورود بدون حجم مجاز نیست"
        else if (volRatio != null && volRatio < cfg.minBreakoutVolumeRatio) {
            reasons += "حجم کافی نیست (${num(volRatio)}× < ${num(cfg.minBreakoutVolumeRatio)}×)"
        }
        if (!expansionOk) reasons += "گسترش نوسان تأیید نشد (ATR ${num(atrExpansion)}× )"
        if (!wickOk) reasons += "شدوی مخالف بزرگ است (${pct(adverseWick)})"

        val structuralBlock = structuralBlocks(gates, isLong)
        reasons += structuralBlock

        val breakoutNow = closeBeyond && directionOk && bodyOk && clvOk && volumeOk && expansionOk && wickOk

        if (breakoutNow) {
            val distance = abs(currentBar.close - level) / atr
            val setupId = setupId(symbol, strategyId, triggerIntervalLabel, currentBar.time, isLong, zone)
            if (setupId != null && setupId in alreadyEnteredSetupIds) {
                return blank(
                    State.BLOCKED, direction,
                    "این شکست قبلاً معامله شده است (شناسهٔ تکراری).",
                ).copy(
                    compressionScorePct = score,
                    compressionHigh = zone.high,
                    compressionLow = zone.low,
                    compressionBars = compressionBars,
                    volumeAvailable = volumeAvailable,
                    setupId = setupId,
                    breakoutLevel = level,
                )
            }
            val tooBig = currentRange / atr > cfg.maxBreakoutCandleRangeAtr
            val tooFar = distance > cfg.maxDirectEntryDistanceAtr
            val rrOk = (gates.riskRewardAfterCosts ?: 0.0) >= 1.5
            val spaceOk = gates.spaceToObstacleR == null || gates.spaceToObstacleR >= 1.5

            val canEnterDirect = !tooBig && !tooFar && gates.planUsable && rrOk && spaceOk &&
                structuralBlock.isEmpty()
            val extraBlock = buildList {
                if (tooBig) add("کندل شکست بیش‌ازحد بزرگ است (${num(currentRange / atr)} ATR)؛ تعقیب ممنوع")
                if (tooFar) add("قیمت از سطح شکست دور شده (${num(distance)} ATR)؛ تعقیب ممنوع")
                if (!gates.planUsable) add("پلن `TradePlanGuard` قابل‌اجرا نیست")
                if (!rrOk) add("R:R پس از هزینه‌ها کمتر از ۱:۱٫۵ است")
                if (!spaceOk) add("فضای تا مانع کمتر از ۱٫۵R است (${num(gates.spaceToObstacleR ?: 0.0)}R)")
            }

            val stop = if (isLong) {
                zone.high - buffer
            } else {
                zone.low + buffer
            }
            val stopValid = if (isLong) stop < currentBar.close else stop > currentBar.close

            return Analysis(
                state = if (canEnterDirect && stopValid) State.BREAKOUT_CONFIRMED else State.WAITING_RETEST,
                direction = direction,
                compressionScorePct = score,
                compressionHigh = zone.high,
                compressionLow = zone.low,
                compressionBars = compressionBars,
                breakoutPrice = currentBar.close,
                breakoutBarTime = currentBar.time,
                breakoutAtr = atr,
                distanceFromBreakoutAtr = distance,
                volumeRatio = volRatio,
                atrExpansionRatio = atrExpansion,
                entryMode = if (canEnterDirect && stopValid) EntryMode.DIRECT_BREAKOUT else EntryMode.WAIT_RETEST,
                blockedReasonsFa = if (canEnterDirect && stopValid) emptyList() else extraBlock + reasons,
                diagnosticsFa = diagnostics(
                    State.BREAKOUT_CONFIRMED, score, zone, compressionBars, volRatio, atrExpansion,
                    distance, cfg,
                ),
                volumeAvailable = volumeAvailable,
                setupId = setupId,
                suggestedStopLoss = if (stopValid) round2(stop) else null,
                breakoutLevel = level,
                isEntryAllowed = canEnterDirect && stopValid,
            )
        }

        // ── ۳. شکست قبلی و بازآزمایی ──
        val previous = findRecentBreakout(bars, currentIdx, cfg, isLong, noiseFloorPrice)
        if (previous != null) {
            val ageBars = currentIdx - previous.index
            val retestLevel = if (isLong) previous.zone.high else previous.zone.low
            val closedBackInside = if (isLong) {
                currentBar.close < retestLevel - buffer * 0.5
            } else {
                currentBar.close > retestLevel + buffer * 0.5
            }
            val deepInside = if (isLong) {
                currentBar.close < previous.zone.high - buffer
            } else {
                currentBar.close > previous.zone.low + buffer
            }
            val touched = if (isLong) currentBar.low <= retestLevel else currentBar.high >= retestLevel
            val retestBodyOk = bodyRatio >= cfg.minRetestBodyRatio
            val retestDirOk = if (isLong) currentBar.close > currentBar.open else currentBar.close < currentBar.open
            val retestVolume = volumeRatio(bars, currentIdx, 20)
            val retestVolumeOk = volumeAvailable && retestVolume != null &&
                retestVolume >= cfg.minRetestVolumeRatio
            val sid = setupId(symbol, strategyId, triggerIntervalLabel, previous.bar.time, isLong, previous.zone)

            val alreadyUsed = sid != null && sid in alreadyEnteredSetupIds
            val rrOk = (gates.riskRewardAfterCosts ?: 0.0) >= 1.5
            val spaceOk = gates.spaceToObstacleR == null || gates.spaceToObstacleR >= 1.5

            if (ageBars > cfg.maxRetestWaitBars || deepInside) {
                return Analysis(
                    state = State.FAILED_BREAKOUT,
                    direction = direction,
                    compressionScorePct = score,
                    compressionHigh = zone.high,
                    compressionLow = zone.low,
                    compressionBars = compressionBars,
                    breakoutPrice = previous.bar.close,
                    breakoutBarTime = previous.bar.time,
                    breakoutAtr = previous.atr,
                    distanceFromBreakoutAtr = abs(currentBar.close - retestLevel) / atr,
                    volumeRatio = retestVolume,
                    atrExpansionRatio = atrExpansion,
                    entryMode = EntryMode.NONE,
                    blockedReasonsFa = listOf(
                        if (ageBars > cfg.maxRetestWaitBars) {
                            "حرکت بدون ورود به پایان رسید؛ تعقیب ممنوع (${faDigits(ageBars)} کندل گذشته)"
                        } else {
                            "بازگشت عمیق داخل ناحیه؛ شکست ناموفق"
                        },
                    ),
                    diagnosticsFa = "شکست در کندل ${faDigits(ageBars)} قبل رخ داد و تأیید نشد.",
                    volumeAvailable = volumeAvailable,
                    setupId = sid,
                    suggestedStopLoss = null,
                    breakoutLevel = retestLevel,
                    isEntryAllowed = false,
                )
            }

            val confirmed = !alreadyUsed && touched && !closedBackInside &&
                retestBodyOk && retestDirOk && retestVolumeOk && rrOk && spaceOk &&
                gates.planUsable && structuralBlock.isEmpty()
            val stop = if (isLong) min(currentBar.low, retestLevel) - buffer
            else max(currentBar.high, retestLevel) + buffer
            val stopValid = if (isLong) stop < currentBar.close else stop > currentBar.close
            val why = buildList {
                if (alreadyUsed) add("این شکست قبلاً معامله شده است")
                if (!touched) add("کندل به سطح شکست برنگشته است")
                if (closedBackInside) add("کلوز به داخل ناحیه برگشته است")
                if (!retestBodyOk) add("بدنهٔ کندل بازآزمایی ضعیف است")
                if (!retestVolumeOk) add("حجم کندل بازآزمایی کافی نیست")
                if (!rrOk) add("R:R پس از هزینه‌ها کمتر از ۱:۱٫۵ است")
                if (!spaceOk) add("فضا تا مانع کمتر از ۱٫۵R است")
            } + structuralBlock

            return Analysis(
                state = if (confirmed && stopValid) State.BREAKOUT_CONFIRMED else State.WAITING_RETEST,
                direction = direction,
                compressionScorePct = score,
                compressionHigh = zone.high,
                compressionLow = zone.low,
                compressionBars = compressionBars,
                breakoutPrice = currentBar.close,
                breakoutBarTime = previous.bar.time,
                breakoutAtr = previous.atr,
                distanceFromBreakoutAtr = abs(currentBar.close - retestLevel) / atr,
                volumeRatio = retestVolume,
                atrExpansionRatio = atrExpansion,
                entryMode = if (confirmed && stopValid) EntryMode.RETEST_CONFIRMED else EntryMode.WAIT_RETEST,
                blockedReasonsFa = if (confirmed && stopValid) emptyList() else why,
                diagnosticsFa = "شکست ${faDigits(ageBars)} کندل قبل؛ بازآزمایی سطح ${num(retestLevel)}.",
                volumeAvailable = volumeAvailable,
                setupId = sid,
                suggestedStopLoss = if (stopValid) round2(stop) else null,
                breakoutLevel = retestLevel,
                isEntryAllowed = confirmed && stopValid,
            )
        }

        // ── ۴. نه شکست، نه بازآزمایی: فقط آماده‌بودن ناحیه ──
        val state = when {
            structuralBlock.isNotEmpty() -> State.BLOCKED
            zoneReady -> State.READY
            else -> State.FORMING
        }
        return Analysis(
            state = state,
            direction = direction,
            compressionScorePct = score,
            compressionHigh = zone.high,
            compressionLow = zone.low,
            compressionBars = compressionBars,
            breakoutPrice = null,
            breakoutBarTime = null,
            breakoutAtr = null,
            distanceFromBreakoutAtr = if (currentBar.close != 0.0) abs(currentBar.close - level) / atr else null,
            volumeRatio = volRatio,
            atrExpansionRatio = atrExpansion,
            entryMode = EntryMode.NONE,
            blockedReasonsFa = buildList {
                // نبود حجم معتبر یک هشدار همیشگی است، حتی وقتی ناحیه «آماده» است:
                // با این داده هیچ ورودی مجاز نخواهد بود و کاربر باید بداند چرا.
                if (!volumeAvailable) add("حجم معتبر در دسترس نیست؛ ورود بدون حجم مجاز نیست")
                if (state != State.READY) {
                    if (!zoneReady) {
                        add("امتیاز فشردگی کافی نیست (${pct(score)} از ${pct(cfg.minCompressionScorePct)})")
                        if (compressionBars < cfg.minCompressionBars) {
                            add("تداوم فشردگی کافی نیست (${faDigits(compressionBars)} از ${faDigits(cfg.minCompressionBars)} کندل)")
                        }
                    }
                    addAll(structuralBlock)
                }
            },
            diagnosticsFa = diagnostics(state, score, zone, compressionBars, volRatio, atrExpansion, null, cfg),
            volumeAvailable = volumeAvailable,
            setupId = null,
            suggestedStopLoss = null,
            breakoutLevel = level,
            isEntryAllowed = false,
        )
    }

    // ─────────────────────────── اجزای تحلیل ───────────────────────────

    internal data class Zone(val high: Double, val low: Double, val medianRange: Double, val bars: Int)

    /**
     * سقف/کف ناحیه از کندل‌های `[endIdx - lookback, endIdx - 1]` — یعنی کندل `endIdx`
     * خودش در ناحیه شرکت نمی‌کند. این تابع هیچ داده‌ای بعد از `endIdx` نمی‌بیند.
     */
    private fun zoneEndingBefore(bars: List<Candle>, endIdx: Int, lookback: Int): Zone? {
        val from = endIdx - lookback
        val to = endIdx - 1
        if (from < 0 || to > bars.lastIndex || to < from) return null
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        val ranges = ArrayList<Double>(to - from + 1)
        for (i in from..to) {
            val c = bars[i]
            if (c.high > hi) hi = c.high
            if (c.low < lo) lo = c.low
            ranges += (c.high - c.low)
        }
        if (!hi.isFinite() || !lo.isFinite() || hi <= lo) return null
        ranges.sort()
        val median = if (ranges.isEmpty()) 0.0 else ranges[ranges.size / 2]
        return Zone(hi, lo, median, to - from + 1)
    }

    /** امتیاز فشردگی ۰..۱۰۰ طبق سند (۲۵ + ۲۵ + ۲۰ + ۱۵ + ۱۵). */
    private fun compressionScore(
        bars: List<Candle>,
        regionEndIdx: Int,
        cfg: Config,
        snapshot: IchimokuBarSnapshot,
        zone: Zone,
    ): Double {
        val currentIdx = regionEndIdx.coerceIn(cfg.bbPeriod, bars.lastIndex)
        var score = 0.0

        // ۱. پرسنتایل عرض بولینگر (۲۵)
        val widths = ArrayList<Double>()
        val oldest = max(cfg.bbPeriod, currentIdx - cfg.historyBars + 1)
        for (end in oldest..currentIdx) {
            widths += bbWidth(bars, end, cfg.bbPeriod) ?: continue
        }
        if (widths.size >= 10) {
            val now = widths.last()
            val hist = widths.dropLast(1)
            val below = hist.count { it < now }
            val percentile = below * 100.0 / hist.size
            if (percentile <= cfg.bbWidthPercentile) score += 25.0
        }

        // ۲. کوچک‌شدن دامنه (۲۵)
        val recentRange = zone.high - zone.low
        val previousZone = zoneEndingBefore(bars, max(1, currentIdx - cfg.lookbackBars), cfg.lookbackBars)
        if (previousZone != null) {
            val previousRange = previousZone.high - previousZone.low
            if (previousRange > 0.0 && recentRange / previousRange <= cfg.maxRangeRatio) score += 25.0
        }

        // ۳. انقباض ATR (۲۰)
        val atrNow = meanTrueRange(bars, currentIdx, 14)
        val atrPrior = meanTrueRange(bars, currentIdx - 1, 50)
        if (atrPrior > 0.0 && atrNow / atrPrior <= cfg.maxAtrRatio) score += 20.0

        // ۴. نزدیکی تنکان/کیجون (۱۵)
        val atr = snapshot.atr14
        if (atr > 0.0) {
            val gap = abs(snapshot.tenkan - snapshot.kijun) / atr
            if (gap <= cfg.maxTkGapAtr) score += 15.0
        }

        // ۵. تداوم فشردگی (۱۵)
        if (compressionBars(bars, currentIdx, cfg) >= cfg.minCompressionBars) score += 15.0

        return round(score * 10.0) / 10.0
    }

    /**
     * شمار کندل‌های متوالی «آرام» پیش از کندل جاری.
     *
     * تعریف: کندل آرام = دامنهٔ کندل ≤ [Config.maxAtrRatio] × میانگین دامنهٔ ۵۰ کندل قبل
     * از خودش. نسخهٔ اول این تابع از «عرض بولینگر ≤ پرسنتایل ۲۰» استفاده می‌کرد و در عمل
     * آستانه چنان باریک می‌شد که تداوم هیچ‌وقت از ۴ کندل بالاتر نمی‌رفت (اندازه‌گیری شد)،
     * یعنی شرط ۱۵ امتیازی عملاً قفل بود. معیار «کندل آرام نسبت به نوسان معمول خودش»
     * همان چیزی است که سند می‌خواهد و به دامنهٔ ثابت وابسته نیست.
     */
    private fun compressionBars(bars: List<Candle>, currentIdx: Int, cfg: Config): Int {
        var count = 0
        var i = currentIdx - 1
        while (i >= 51) {
            val reference = meanTrueRange(bars, i - 1, 50)
            if (reference <= 0.0) break
            val range = bars[i].high - bars[i].low
            if (range <= reference * cfg.maxAtrRatio) count++ else break
            i--
        }
        return count
    }

    /** پیدا کردن نزدیک‌ترین شکست تأییدشدهٔ گزشته که هنوز مهلت بازآزمایی دارد. */
    private data class PastBreakout(
        val index: Int,
        val bar: Candle,
        val zone: Zone,
        val atr: Double,
    )

    private fun findRecentBreakout(
        bars: List<Candle>,
        currentIdx: Int,
        cfg: Config,
        isLong: Boolean,
        noiseFloorPrice: Double,
    ): PastBreakout? {
        // پنجرهٔ جست‌وجو کمی بزرگ‌تر از مهلت بازآزمایی است تا «منقضی‌شدن» هم قابل گزارش
        // باشد؛ وگرنه ستاپی که مهلتش گذشته به‌کل فراموش می‌شد و کاربر دلیل بی‌ورود را
        // نمی‌دید. تصمیم ورود همچنان با همان مهلت [maxRetestWaitBars] گرفته می‌شود.
        val searchBack = cfg.maxRetestWaitBars + cfg.failedExpansionBars + 2
        val from = max(cfg.lookbackBars + 1, currentIdx - searchBack)
        var found: PastBreakout? = null
        for (j in from until currentIdx) {
            val zone = zoneEndingBefore(bars, j, cfg.lookbackBars) ?: continue
            val atr = meanTrueRange(bars, j, 14)
            if (atr <= 0.0) continue
            val buffer = max(cfg.breakoutBufferAtr * atr, noiseFloorPrice.coerceAtLeast(0.0))
            val bar = bars[j]
            val level = if (isLong) zone.high + buffer else zone.low - buffer
            val closeBeyond = if (isLong) bar.close > level else bar.close < level
            if (!closeBeyond) continue
            val dirOk = if (isLong) bar.close > bar.open else bar.close < bar.open
            if (!dirOk) continue
            if (bodyRatio(bar) < cfg.minBreakoutBodyRatio) continue
            val clv = closeLocationValue(bar)
            if (isLong && clv < cfg.minBreakoutClvLong) continue
            if (!isLong && clv > cfg.maxBreakoutClvShort) continue
            val baseAtr = meanTrueRange(bars, j - 1, 14)
            val expanded = baseAtr > 0.0 && atr / baseAtr >= cfg.minAtrExpansionRatio
            if (!expanded) continue
            if (!hasUsableVolume(bars, j)) continue
            val vr = volumeRatio(bars, j, 20) ?: continue
            if (vr < cfg.minBreakoutVolumeRatio) continue
            found = PastBreakout(j, bar, zone, atr)
        }
        return found
    }

    private fun structuralBlocks(gates: Gates, isLong: Boolean): List<String> = buildList {
        if (gates.higherTfOpposes) add("رژیم تایم‌فریم بالاتر مخالف جهت معامله است")
        if (gates.chikouBlocked) add("چیکو در مسیر معامله مسدود است")
        if (gates.futureKumoOpposes) add("ابر آیندهٔ مخالف، ورود را وتو می‌کند")
        if (gates.lateExhaustion) add("فاز روند «پایان روند» است؛ شروع انبساط با انتهای روند اشتباه نشود")
    }

    // ─────────────────────────── ابزار ریاضی ───────────────────────────

    /** حجم قابل‌اعتماد است؟ (مثبت و متنوع؛ حجم ثابت یعنی دادهٔ ساختگی/غایب) */
    internal fun hasUsableVolume(bars: List<Candle>, idx: Int): Boolean {
        if (idx < 1) return false
        val from = max(0, idx - 19)
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        for (i in from..idx) {
            val v = bars[i].volume
            if (!v.isFinite() || v <= 0.0) return false
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        return maxV > minV
    }

    internal fun meanTrueRange(bars: List<Candle>, endIdx: Int, period: Int): Double {
        if (endIdx < 1 || period <= 0) return 0.0
        val from = max(1, endIdx - period + 1)
        var sum = 0.0
        var count = 0
        for (i in from..endIdx) {
            val c = bars[i]
            val prevClose = bars[i - 1].close
            val tr = max(
                c.high - c.low,
                max(abs(c.high - prevClose), abs(c.low - prevClose)),
            )
            sum += tr
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }

    internal fun bbWidth(bars: List<Candle>, endIdx: Int, period: Int): Double? {
        if (endIdx < period - 1) return null
        val from = endIdx - period + 1
        var sum = 0.0
        for (i in from..endIdx) sum += bars[i].close
        val mean = sum / period
        if (mean <= 0.0) return null
        var variance = 0.0
        for (i in from..endIdx) {
            val d = bars[i].close - mean
            variance += d * d
        }
        val sd = sqrt(variance / period)
        return (2.0 * 2.0 * sd) / mean // upper−lower با ۲ انحراف معیار در هر سمت
    }

    internal fun volumeRatio(bars: List<Candle>, idx: Int, period: Int): Double? {
        if (idx < period) return null
        var sum = 0.0
        for (i in idx - period until idx) sum += bars[i].volume
        val mean = sum / period
        if (mean <= 0.0) return null
        return bars[idx].volume / mean
    }

    internal fun bodyRatio(bar: Candle): Double {
        val range = bar.high - bar.low
        return if (range <= 0.0) 0.0 else abs(bar.close - bar.open) / range
    }

    internal fun closeLocationValue(bar: Candle): Double {
        val range = bar.high - bar.low
        return if (range <= 0.0) 0.5 else (bar.close - bar.low) / range
    }

    internal fun upperWickRatio(bar: Candle): Double {
        val range = bar.high - bar.low
        if (range <= 0.0) return 0.0
        val top = max(bar.open, bar.close)
        return (bar.high - top) / range
    }

    internal fun lowerWickRatio(bar: Candle): Double {
        val range = bar.high - bar.low
        if (range <= 0.0) return 0.0
        val bottom = min(bar.open, bar.close)
        return (bottom - bar.low) / range
    }

    /** شناسهٔ یکتای شکست — مستقل از حافظهٔ برنامه. */
    internal fun setupId(
        symbol: String,
        strategyId: String,
        intervalLabel: String,
        breakoutBarTime: Long,
        isLong: Boolean,
        zone: Zone,
    ): String {
        val dir = if (isLong) "L" else "S"
        val hi = round(zone.high * 100.0) / 100.0
        val lo = round(zone.low * 100.0) / 100.0
        return "$symbol|$strategyId|$intervalLabel|$breakoutBarTime|$dir|$hi|$lo"
    }

    // ─────────────────────────── متن‌سازی ───────────────────────────

    private fun diagnostics(
        state: State,
        score: Double,
        zone: Zone,
        compressionBars: Int,
        volumeRatio: Double?,
        atrExpansion: Double,
        distanceAtr: Double?,
        cfg: Config,
    ): String = buildString {
        append("وضعیت: ${state.labelFa} · امتیاز فشردگی ${pct(score)}")
        append(" · ناحیه ${num(zone.low)}..${num(zone.high)} (${faDigits(zone.bars)} کندل)")
        append(" · تداوم ${faDigits(compressionBars)} کندل")
        if (volumeRatio != null) append(" · حجم ${num(volumeRatio)}×")
        append(" · گسترش ATR ${num(atrExpansion)}×")
        if (distanceAtr != null) append(" · فاصله از سطح ${num(distanceAtr)} ATR")
        if (cfg.runnerEnabled) append(" · Runner خاموش شد (پوزیشن جزئی پشتیبانی نمی‌شود)")
    }

    private fun off(direction: SignalAction) = Analysis(
        state = State.OFF,
        direction = direction,
        compressionScorePct = 0.0,
        compressionHigh = null,
        compressionLow = null,
        compressionBars = 0,
        breakoutPrice = null,
        breakoutBarTime = null,
        breakoutAtr = null,
        distanceFromBreakoutAtr = null,
        volumeRatio = null,
        atrExpansionRatio = 1.0,
        entryMode = EntryMode.NONE,
        blockedReasonsFa = emptyList(),
        diagnosticsFa = "ماشهٔ فشردگی–انبساط خاموش است.",
        volumeAvailable = false,
        setupId = null,
        suggestedStopLoss = null,
        breakoutLevel = null,
        isEntryAllowed = false,
    )

    private fun blank(state: State, direction: SignalAction, reason: String) = Analysis(
        state = state,
        direction = direction,
        compressionScorePct = 0.0,
        compressionHigh = null,
        compressionLow = null,
        compressionBars = 0,
        breakoutPrice = null,
        breakoutBarTime = null,
        breakoutAtr = null,
        distanceFromBreakoutAtr = null,
        volumeRatio = null,
        atrExpansionRatio = 1.0,
        entryMode = EntryMode.NONE,
        blockedReasonsFa = listOf(reason),
        diagnosticsFa = reason,
        volumeAvailable = false,
        setupId = null,
        suggestedStopLoss = null,
        breakoutLevel = null,
        isEntryAllowed = false,
    )

    private fun round2(v: Double) = round(v * 100.0) / 100.0

    private fun num(v: Double) = String.format(Locale.US, "%.2f", v)

    private fun pct(v: Double) = "${(round(v * 10.0) / 10.0)}%"

    internal fun faDigits(value: Int): String =
        value.toString().map { ch -> if (ch in '0'..'9') '۰' + (ch - '0') else ch }.joinToString("")
}
