package com.aurum.edge.core

/**
 * یک بخش متنی از «گزارش توضیحات هوش عصبی».
 * هر بخش یک عنوان فارسی و فهرستی از خطوط توضیح دارد و برای کپی در کلیپ‌بورد ساخته می‌شود.
 */
data class NeuralReportSection(
    val titleFa: String,
    val lines: List<String>,
)

/**
 * ساخت متن قابل‌کپی گزارش هوش عصبی.
 *
 * این کلاس هیچ وابستگی اندرویدی ندارد تا هم در اپ و هم در تست‌ها قابل استفاده باشد.
 * خروجی همیشه فارسی، راست‌به‌چپ‌خوان و بدون کاراکترهای نامرئی مخدوش‌کننده است.
 */
object NeuralReportText {

    const val DIVIDER: String = "──────────────────────────────"
    const val SECTION_MARK: String = "▌"

    /** `برچسب: مقدار` — مقدار خالی باعث حذف خط می‌شود. */
    fun label(labelFa: String, value: String): String =
        if (value.isBlank() || value == "—") "" else "$labelFa: $value"

    /** `برچسب: مقدار | برچسب۲: مقدار۲` — بخش‌های خالی حذف می‌شوند. */
    fun inline(vararg pairs: Pair<String, String>): String =
        pairs.map { (k, v) -> label(k, v) }
            .filter { it.isNotBlank() }
            .joinToString(" | ")

    fun number(value: Double, digits: Int = 2): String =
        if (!value.isFinite()) "—" else String.format(java.util.Locale.US, "%,.${digits}f", value)

    fun pct(value: Double, digits: Int = 1): String =
        if (!value.isFinite()) "—" else String.format(java.util.Locale.US, "%,.${digits}f%%", value)

    fun rMultiple(value: Double): String =
        if (!value.isFinite()) "—"
        else String.format(java.util.Locale.US, "%+.2fR", value)

    /**
     * متن نهایی: سرصفحه، تاریخ تولید، بخش‌ها و پانویس.
     * بخش‌های بدون خط یا با خطوط خالی حذف می‌شوند تا خروجی کپی‌شده تمیز بماند.
     */
    fun render(
        headerFa: String,
        generatedAtFa: String,
        sections: List<NeuralReportSection>,
        footerFa: String = "",
    ): String {
        val body = StringBuilder()
        body.appendLine(headerFa.trim())
        if (generatedAtFa.isNotBlank()) body.appendLine("🕒 $generatedAtFa")
        body.appendLine(DIVIDER)
        sections.forEach { section ->
            val lines = section.lines.map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) return@forEach
            body.appendLine("$SECTION_MARK ${section.titleFa}")
            lines.forEach { body.appendLine(it) }
            body.appendLine()
        }
        if (footerFa.isNotBlank()) {
            body.appendLine(DIVIDER)
            body.appendLine(footerFa.trim())
        }
        return body.toString().trimEnd()
    }

    /** خلاصهٔ کوتاه وضعیت یادگیری (برای کپی سریع). */
    fun renderSummary(
        headerFa: String,
        generatedAtFa: String,
        lines: List<String>,
        footerFa: String = "",
    ): String = render(
        headerFa = headerFa,
        generatedAtFa = generatedAtFa,
        sections = listOf(NeuralReportSection("خلاصهٔ وضعیت", lines)),
        footerFa = footerFa,
    )

    /**
     * متن توضیحات یک بخش (برای دکمهٔ «کپی» کنار هر کارت) با عنوان و مهر نسخه.
     */
    fun renderSection(
        titleFa: String,
        lines: List<String>,
        versionFa: String = "",
    ): String = render(
        headerFa = "🧠 $titleFa",
        generatedAtFa = "",
        sections = listOf(NeuralReportSection(titleFa, lines)),
        footerFa = if (versionFa.isBlank()) "" else "Aurum Edge $versionFa",
    )

    /** تاریخ و ساعت فارسی‌خوان با تقویم میلادی و ارقام لاتین (پایدار در کلیپ‌بورد). */
    fun timestamp(millis: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(millis))
}
