package com.aurum.edge.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.engine.PerformanceMetrics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a REAL PDF straight from the trades already saved in [com.aurum.edge.data.JournalStore] —
 * every row is a real, previously-recorded paper trade (never a generated/example row). Win-rate,
 * profit-factor and the rest come from the SAME [PerformanceMetrics] used on-screen; the PDF is a
 * static export of that exact computation, not a separate/looser one.
 */
object JournalPdfExporter {
    private const val PAGE_W = 595 // A4 @72dpi
    private const val PAGE_H = 842
    private const val MARGIN = 36f

    fun export(context: Context, uri: Uri, title: String, trades: List<PaperTrade>, startingBalance: Double) {
        require(startingBalance.isFinite() && startingBalance > 0) { "موجودی اولیه نامعتبر است" }
        val closed = trades.filter { !it.isOpen }.sortedByDescending { it.openedAt }
        val report = PerformanceMetrics.fromPaper(trades, startingBalance)
        val document = PdfDocument()
        val titlePaint = Paint().apply { textSize = 16f; isFakeBoldText = true; color = Color.BLACK }
        val subPaint = Paint().apply { textSize = 9f; color = Color.DKGRAY }
        val headerPaint = Paint().apply { textSize = 10f; isFakeBoldText = true; color = Color.BLACK }
        val rowPaint = Paint().apply { textSize = 9f; color = Color.DKGRAY }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN

        fun ensureSpace(next: Float) {
            if (y + next > PAGE_H - MARGIN) {
                document.finishPage(page)
                pageNumber += 1
                page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
                canvas = page.canvas
                y = MARGIN
            }
        }

        canvas.drawText(title, MARGIN, y, titlePaint); y += 20f
        canvas.drawText("Generated ${sdf.format(Date())} - only trades already saved in this journal file; nothing here is invented for the report.",
            MARGIN, y, subPaint); y += 18f

        val summaryLines = listOf(
            "Total ${report.total} | Wins ${report.wins} | Losses ${report.losses} | Long ${report.longCount} | Short ${report.shortCount}",
            "Win rate ${report.winRatePct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "-"} | " +
                "Profit factor ${report.profitFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "-"} | " +
                "Expectancy(R) ${report.expectancyR?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}",
            "Net P/L ${String.format(Locale.US, "%.2f", report.netPnlUsd)} | Gross profit ${String.format(Locale.US, "%.2f", report.grossProfitUsd)} | " +
                "Gross loss ${String.format(Locale.US, "%.2f", report.grossLossUsd)}",
            "Avg win ${report.averageWinUsd?.let { String.format(Locale.US, "%.2f", it) } ?: "-"} | " +
                "Avg loss ${report.averageLossUsd?.let { String.format(Locale.US, "%.2f", it) } ?: "-"} | " +
                "Max drawdown ${report.maxDrawdownPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "-"}",
            "Longest win streak ${report.longestWinningStreak} | Longest loss streak ${report.longestLosingStreak} | " +
                "Trade Sharpe (not annualized) ${report.tradeSharpe?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}",
        )
        for (line in summaryLines) { ensureSpace(15f); canvas.drawText(line, MARGIN, y, headerPaint); y += 15f }
        y += 8f

        ensureSpace(14f)
        canvas.drawText("Symbol | Side | Entry | Exit | P/L | R | Opened | Closed | Reason", MARGIN, y, headerPaint); y += 14f
        if (closed.isEmpty()) {
            ensureSpace(14f)
            canvas.drawText("No closed trades yet in this journal.", MARGIN, y, rowPaint); y += 14f
        }
        for (t in closed) {
            ensureSpace(13f)
            val side = if (t.action == SignalAction.BUY) "BUY" else "SELL"
            val row = "${t.symbol} | $side | ${String.format(Locale.US, "%.4f", t.entry)} | " +
                (t.exitPrice?.let { String.format(Locale.US, "%.4f", it) } ?: "-") + " | " +
                (t.pnlUsd?.let { String.format(Locale.US, "%.2f", it) } ?: "-") + " | " +
                (t.rMultiple?.let { String.format(Locale.US, "%.2f", it) } ?: "-") + " | " +
                sdf.format(Date(t.openedAt)) + " | " + (t.closedAt?.let { sdf.format(Date(it)) } ?: "-") + " | " +
                (t.exitReason ?: "-")
            canvas.drawText(row.take(140), MARGIN, y, rowPaint); y += 13f
        }
        document.finishPage(page)

        context.contentResolver.openOutputStream(uri)?.use { out -> document.writeTo(out) }
            ?: error("فایل مقصد برای نوشتن PDF باز نشد")
        document.close()
    }
}
