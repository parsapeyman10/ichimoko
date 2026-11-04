package com.aurum.edge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست‌های «دفتر یادگیری» و «بستهٔ دانش» شبکهٔ عصبی خودآموز.
 *
 * این تست‌ها سه تضمین اصلی نسخهٔ ۳.۹.۲۷ را قفل می‌کنند:
 * ۱. هر معامله فقط **یک‌بار** آموزش می‌دهد؛ عدد یادگیری همیشه برابر تعداد معامله‌های یکتای
 *    ثبت‌شده در دفتر است و با تکرار همگام‌سازی ژورنال تورم نمی‌گیرد.
 * ۲. گیت فقط با **هر دو شرط** فعال می‌شود: رسیدن به ۱۰,۰۰۰ معاملهٔ یادگیری‌شده و فعال‌سازی
 *    صریح کاربر.
 * ۳. دانش شبکه (وزن‌ها، خوشه‌ها، پارامترها، کارنامهٔ اصلاحات) در قالب بستهٔ دانش نسخه‌دار با
 *    چک‌سام ذخیره/بازخوانی می‌شود تا در نسخهٔ بعدی اپ قابل فهم و قابل بازیابی باشد.
 */
class NeuralLearningLedgerTest {

    private fun stamp(
        tradeId: String,
        closedAt: Long,
        category: String = "CRYPTO",
        won: Boolean = true,
        r: Double = 1.0,
    ) = NeuralLearningLedger.LearnedStamp(
        fingerprint = NeuralLearningLedger.tradeFingerprint(tradeId, closedAt),
        tradeId = tradeId,
        categoryCode = category,
        won = won,
        achievedR = r,
        learnedAtMs = closedAt,
    )

    @Test
    fun `fingerprint is stable for the same closed trade and changes for another close`() {
        val a = NeuralLearningLedger.tradeFingerprint("trade-1", 1_700_000_000_000L)
        val b = NeuralLearningLedger.tradeFingerprint(" trade-1 ", 1_700_000_000_000L)
        val c = NeuralLearningLedger.tradeFingerprint("trade-1", 1_700_000_000_001L)
        assertEquals(a, b)
        assertTrue(a != c)
        assertEquals(16, a.length)
        // پول/زیان در اثرانگشت دخالت ندارد؛ ویرایش نتیجه نباید یادگیری تازه بسازد.
        assertEquals(
            NeuralLearningLedger.tradeFingerprint("trade-2", null),
            NeuralLearningLedger.tradeFingerprint("trade-2", 0L),
        )
    }

    @Test
    fun `learning total never inflates when the same trades are synced repeatedly`() {
        var ledger = emptyList<NeuralLearningLedger.LearnedStamp>()
        var pruned = 0
        val trades = (1..500).map { stamp("t$it", 1_000L + it) }

        repeat(4) { round ->
            for (trade in trades) {
                if (ledger.any { it.fingerprint == trade.fingerprint }) continue
                val (next, prunedNow) = NeuralLearningLedger.append(ledger, trade)
                ledger = next
                pruned += prunedNow
            }
            assertEquals("round $round", 500, NeuralLearningLedger.learnedTotal(ledger.size, pruned))
        }
    }

    @Test
    fun `legacy inflated counter is repaired to the real number of distinct learned trades`() {
        val ledger = (1..40).map { stamp("old$it", 5_000L + it) }
        val reconciliation = NeuralLearningLedger.reconcile(
            journalClosedTrades = 55,
            distinctJournalTrades = 50,
            ledger = ledger,
            prunedCount = 0,
            legacyStoredTotal = 1_284, // عدد متورم نسخهٔ قبل
            newlyLearnedThisRun = 10,
        )
        assertEquals(40, reconciliation.learnedTotal)
        assertEquals(1_244, reconciliation.duplicatesRepaired)
        assertTrue(reconciliation.isBalanced)
        assertTrue(reconciliation.summaryFa.contains("1244") || reconciliation.summaryFa.contains("۱٬۲۴۴"))
        assertTrue(reconciliation.summaryFa.contains("10"))
    }

    @Test
    fun `pruned rows keep the true total while the ledger stays bounded`() {
        var ledger = emptyList<NeuralLearningLedger.LearnedStamp>()
        var pruned = 0
        for (i in 1..75) {
            val (next, prunedNow) = NeuralLearningLedger.append(ledger, stamp("t$i", i.toLong()), maxRows = 25)
            ledger = next
            pruned += prunedNow
        }
        assertEquals(25, ledger.size)
        assertEquals(75, NeuralLearningLedger.learnedTotal(ledger.size, pruned))
        assertEquals(50, pruned)
        // ردیف‌های قدیمی هرس شده‌اند اما تازه‌ترین‌ها باقی مانده‌اند
        assertEquals("t75", ledger.first().tradeId)
    }

    @Test
    fun `category tally is rebuilt from the ledger only`() {
        val ledger = listOf(
            stamp("a", 1L, "CRYPTO", won = true),
            stamp("b", 2L, "CRYPTO", won = false),
            stamp("c", 3L, "COMMODITY", won = true),
        )
        assertEquals(mapOf("CRYPTO" to 2, "COMMODITY" to 1), NeuralLearningLedger.categoryTally(ledger))
        val winsLosses = NeuralLearningLedger.categoryWinsLosses(ledger)
        assertEquals(1 to 1, winsLosses["CRYPTO"])
        assertEquals(1 to 0, winsLosses["COMMODITY"])
    }

    @Test
    fun `gate needs both the 10_000 milestone and the explicit user activation`() {
        val beforeMilestone = NeuralLearningLedger.gateState(
            learnedTotal = 9_999,
            target = 10_000,
            userActivationEnabled = true,
        )
        assertFalse(beforeMilestone.isGateActive)
        assertFalse(beforeMilestone.volumeConditionMet)

        val milestoneOnly = NeuralLearningLedger.gateState(
            learnedTotal = 10_000,
            target = 10_000,
            userActivationEnabled = false,
        )
        assertTrue(milestoneOnly.volumeConditionMet)
        assertFalse(milestoneOnly.isGateActive)
        assertTrue(milestoneOnly.summaryFa.contains("شرط ۱"))

        val bothConditions = NeuralLearningLedger.gateState(
            learnedTotal = 10_000,
            target = 10_000,
            userActivationEnabled = true,
        )
        assertTrue(bothConditions.isGateActive)
        assertTrue(bothConditions.summaryFa.contains("فعال"))

        val afterDeactivation = NeuralLearningLedger.gateState(
            learnedTotal = 12_500,
            target = 10_000,
            userActivationEnabled = false,
        )
        assertFalse(afterDeactivation.isGateActive)
    }

    @Test
    fun `knowledge bundle round trips through json and is tamper evident`() {
        val payload = """{"totalLearnedTrades":40,"schemaVersion":2}"""
        val bundle = NeuralLearningLedger.KnowledgeBundle(
            appVersionName = "3.9.27",
            appVersionCode = 736,
            createdAtMs = 1_700_000_000_000L,
            learnedTotal = 40,
            learnedStampRows = 40,
            learnedPrunedCount = 0,
            adamStepCount = 320,
            tuningGeneration = 6,
            gateUserActivationEnabled = false,
            summaryFa = "خلاصهٔ وضعیت دانش",
            payloadSha256 = NeuralLearningLedger.sha256(payload),
            payloadJson = payload,
            changeLog = listOf(
                NeuralLearningLedger.KnowledgeChangeEntry(
                    id = "TUNE-6",
                    atMs = 1_700_000_000_000L,
                    kindCode = "PARAM_TUNE",
                    titleFa = "تیونینگ خودکار نسل ۶",
                    detailFa = "R:R حداقل 1.65 · بافر ATR 0.26",
                    learnedCountAtChange = 40,
                ),
            ),
        )
        assertTrue(NeuralLearningLedger.validateBundle(bundle).isEmpty())

        val encoded = NeuralLearningLedger.encodeBundle(bundle)
        val decoded = NeuralLearningLedger.decodeBundle(encoded)
        assertNotNull(decoded)
        assertEquals(40, decoded!!.learnedTotal)
        assertEquals(6, decoded.tuningGeneration)
        assertEquals(1, decoded.changeLog.size)
        assertEquals("3.9.27", decoded.appVersionName)

        val tampered = bundle.copy(payloadJson = """{"totalLearnedTrades":99999}""")
        assertFalse(NeuralLearningLedger.validateBundle(tampered).isEmpty())
        assertNull(NeuralLearningLedger.decodeBundle(NeuralLearningLedger.encodeBundle(tampered)))

        val foreign = bundle.copy(bundleKind = "SOMETHING_ELSE")
        assertFalse(NeuralLearningLedger.validateBundle(foreign).isEmpty())
    }

    @Test
    fun `merging two ledgers never loses or double counts a learned trade`() {
        val deviceA = (1..10).map { stamp("a$it", it.toLong()) }
        val deviceB = (6..15).map { stamp("b$it", it.toLong() + 100) }
        val merged = NeuralLearningLedger.merge(deviceA, deviceB, maxRows = 1_000)
        assertEquals(20, merged.size)
        assertEquals(merged.size, merged.map { it.fingerprint }.toSet().size)
        // ادغام دوباره تغییری نمی‌دهد (idempotent)
        assertEquals(20, NeuralLearningLedger.merge(merged, deviceB, maxRows = 1_000).size)
    }

    @Test
    fun `persian manifest explains every correction and where to restore it`() {
        val bundle = NeuralLearningLedger.KnowledgeBundle(
            appVersionName = "3.9.27",
            appVersionCode = 736,
            createdAtMs = 1_700_000_000_000L,
            learnedTotal = 128,
            learnedStampRows = 128,
            adamStepCount = 512,
            tuningGeneration = 9,
            summaryFa = "گیت غیرفعال است",
            payloadSha256 = NeuralLearningLedger.sha256("x"),
            payloadJson = "x",
            changeLog = listOf(
                NeuralLearningLedger.KnowledgeChangeEntry(
                    id = "N1",
                    atMs = 1_700_000_000_000L,
                    kindCode = "NO_TRADE_DISCOVERED",
                    titleFa = "کشف شرایط ممنوعه در CRYPTO",
                    detailFa = "کوموی نازک + کشیدگی کیجون",
                    learnedCountAtChange = 128,
                ),
                NeuralLearningLedger.KnowledgeChangeEntry(
                    id = "M1",
                    atMs = 1_700_000_500_000L,
                    kindCode = "SCHEMA_MIGRATION",
                    titleFa = "مهاجرت به دفتر یادگیری نسخهٔ ۲",
                    detailFa = "شمارندهٔ 1284 ← 128",
                    learnedCountAtChange = 128,
                ),
            ),
        )
        val manifest = NeuralLearningLedger.buildManifestFa(bundle)
        assertTrue(manifest.contains("منیفست دانش شبکهٔ عصبی"))
        assertTrue(manifest.contains("کشف شرایط ممنوعه در CRYPTO"))
        assertTrue(manifest.contains("مهاجرت به دفتر یادگیری نسخهٔ ۲"))
        assertTrue(manifest.contains("Download/AurumEdge/Archive"))
        assertTrue(manifest.contains("128"))
        assertTrue(manifest.contains(NeuralLearningLedger.bundleFileName(736)))
    }

    @Test
    fun `change log keeps newest entries and drops duplicates`() {
        var log = emptyList<NeuralLearningLedger.KnowledgeChangeEntry>()
        for (i in 1..300) {
            log = NeuralLearningLedger.appendChange(
                log,
                NeuralLearningLedger.KnowledgeChangeEntry(
                    id = "E$i",
                    atMs = i.toLong(),
                    kindCode = "PARAM_TUNE",
                    titleFa = "رویداد $i",
                    detailFa = "توضیح $i",
                    learnedCountAtChange = i,
                ),
                maxRows = 40,
            )
        }
        assertEquals(40, log.size)
        assertEquals("E300", log.first().id)
        val replayed = NeuralLearningLedger.appendChange(log, log.first(), maxRows = 40)
        assertEquals(40, replayed.size)
    }

    @Test
    fun `sha256 helper matches the published test vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            NeuralLearningLedger.sha256("abc"),
        )
        assertEquals(64, NeuralLearningLedger.sha256("هر متن فارسی هم پشتیبانی می‌شود").length)
    }
}
