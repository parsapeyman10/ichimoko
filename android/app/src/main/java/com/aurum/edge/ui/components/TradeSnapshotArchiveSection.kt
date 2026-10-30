package com.aurum.edge.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.data.ArchivedTradeSnapshotRecord
import com.aurum.edge.data.TradeSnapshotArchiveManager
import com.aurum.edge.ui.AurumViewModel
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TradeSnapshotArchiveGallerySection(
    viewModel: AurumViewModel,
    initiallyExpanded: Boolean = false,
) {
    val records by viewModel.tradeSnapshotArchiveRecords.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    var selectedRecord by remember { mutableStateOf<ArchivedTradeSnapshotRecord?>(null) }
    var showAllItems by rememberSaveable { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importSnapshotFromPhoneUri(uri)
        }
    }

    SectionCard(
        title = "📸 آرشیو تصویری معاملات — باز شدن و بسته شدن (${records.size} اسکرین‌شات WebP)",
        subtitle = "ذخیره خودکار عکس چارت در لحظه باز شدن و بسته شدن معامله با هر شرایطی + درج شماره معامله (#N) روی تصویر",
        initiallyExpanded = initiallyExpanded,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // نوار ابزار همگام‌سازی و بازخوانی از حافظه گوشی
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AurumColors.SurfaceAlt)
                    .border(1.dp, AurumColors.Cyan.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "💾 ذخیره‌سازی دوگانه و خودخوان در گوشی (Pictures/AurumEdge + Download/AurumEdge/Archive)",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.Cyan,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "هر معامله که باز می‌شود، تصویر باکیفیت و کم‌حجم WebP (~40KB) از کندل‌ها، ابر ایچیموکو، ابر آینده ۲۶ کندلی و باکس پوزیشن SL/TP به همراه متادیتای خودخوان در دل عکس داخل گوشی ذخیره می‌گردد تا بعداً هم بتوانید با یک کلیک کل دیتای معاملات را از روی عکس‌های گوشی بازخوانی کنید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.restoreAllDataAndSnapshotsFromPhone() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AurumColors.Cyan.copy(alpha = 0.20f),
                            contentColor = AurumColors.Cyan,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("🔄 بازخوانی خودکار از گوشی", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { filePickerLauncher.launch(arrayOf("image/*", "application/json", "*/*")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("📂 انتخاب فایل عکس/دیتا از گوشی", fontSize = 11.sp, color = AurumColors.Gold)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.exportArchiveScreenshotsPdfNow(shareAfterExport = true) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AurumColors.Gold,
                            contentColor = Color.Black,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = "📸📄 خروجی PDF آرشیو اسکرین‌شات‌ها",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Button(
                        onClick = { viewModel.recordAllTradesWithArchiveSnapshotsAndExportPdf() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AurumColors.Green,
                            contentColor = Color.Black,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = "📊📄 خروجی PDF جامع ژورنال + تصاویر",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Button(
                    onClick = { viewModel.exportFullAiAndNeuralDataPdf(shareAfterExport = true) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurumColors.Cyan,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "🧠📄 خروجی PDF کامل تمام دیتاهای هوش مصنوعی + رگرسیون پارامترها",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            val effectiveRecords = remember(records, trades) {
                if (records.isNotEmpty()) {
                    records
                } else {
                    trades.sortedBy { it.displayTradeNumber }.flatMap { t ->
                        buildList {
                            add(TradeSnapshotArchiveManager.buildSyntheticSnapshotRecordForTrade(t, isClosePhase = false))
                            if (!t.isOpen) {
                                add(TradeSnapshotArchiveManager.buildSyntheticSnapshotRecordForTrade(t, isClosePhase = true))
                            }
                        }
                    }
                }
            }

            if (effectiveRecords.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AurumColors.Bg)
                        .border(1.dp, AurumColors.Line, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "هنوز اسکرین‌شاتی در آرشیو ثبت نشده است؛ با زدن دکمه «خروجی PDF آرشیو اسکرین‌شات‌ها» در بالا یا باز شدن معامله جدید، تصویر چارت ایچیموکو + SL/TP ساخته و در Download/AurumEdge ذخیره می‌شود.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.TextSecondary,
                        )
                    }
                }
            } else {
                val visibleList = if (showAllItems) effectiveRecords else effectiveRecords.take(6)
                visibleList.forEach { rec ->
                    ArchivedSnapshotItemCard(
                        record = rec,
                        onOpenViewer = { selectedRecord = rec },
                    )
                }
                if (effectiveRecords.size > 6) {
                    OutlinedButton(
                        onClick = { showAllItems = !showAllItems },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (showAllItems) "▲ نمایش کمتر (۶ مورد آخر)" else "▼ نمایش همه ${effectiveRecords.size} اسکرین‌شات آرشیو",
                            color = AurumColors.Gold,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }

    selectedRecord?.let { rec ->
        TradeSnapshotViewerDialog(
            record = rec,
            onDismiss = { selectedRecord = null },
        )
    }
}

@Composable
fun ArchivedSnapshotItemCard(
    record: ArchivedTradeSnapshotRecord,
    onOpenViewer: () -> Unit,
) {
    var expanded by rememberSaveable("${record.tradeId}_${record.snapshotPhase}") { mutableStateOf(false) }
    var imageBitmap by remember(record.tradeId, record.snapshotPhase, record.localImagePath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(record.tradeId, record.snapshotPhase, record.localImagePath) {
        withContext(Dispatchers.IO) {
            val bmp = TradeSnapshotArchiveManager.loadBitmapForRecord(record)
            if (bmp != null) {
                imageBitmap = bmp.asImageBitmap()
            }
        }
    }

    val isBuy = record.action.equals("BUY", ignoreCase = true)
    val sideColor = if (isBuy) AurumColors.Green else AurumColors.Red
    val sizeKb = (record.imageSizeBytes / 1024L).coerceAtLeast(1L)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AurumColors.Bg)
            .border(1.dp, sideColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pill(
                        text = "معامله #${record.displayTradeNumber}",
                        color = AurumColors.Gold,
                    )
                    Pill(
                        text = if (record.isCloseSnapshot) "🏁 بسته شدن (${record.closeConditionCategoryFa.ifBlank { "CLOSE" }})" else "📸 باز شدن (OPEN)",
                        color = if (record.isCloseSnapshot) AurumColors.Cyan else AurumColors.Green,
                    )
                    Pill(
                        text = if (isBuy) "🟢 LONG" else "🔴 SHORT",
                        color = sideColor,
                    )
                    Text(
                        text = "${record.symbol} · [${record.timeframeLabel}]",
                        style = MaterialTheme.typography.titleSmall,
                        color = AurumColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(3.dp))
                val closeInfo = if (record.isCloseSnapshot && record.realizedPnlUsd != null) {
                    val p = record.realizedPnlUsd
                    val s = if (p >= 0.0) "+" else "-"
                    " · نتیجه: ${s}$${String.format(Locale.US, "%.2f", kotlin.math.abs(p))}"
                } else ""
                Text(
                    text = "🎯 ${record.strategyTitleFa}$closeInfo · ${formatDateTime(record.effectiveTimestampMs)} · حجم: ${sizeKb}KB WebP",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    text = "🔍 بزرگ‌نمایی عکس",
                    color = AurumColors.Cyan,
                    modifier = Modifier.clickable { onOpenViewer() },
                )
                Pill(
                    text = if (expanded) "▲ بستن" else "▼ پیش‌نمایش",
                    color = AurumColors.Gold,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }

        // نوار خلاصه قیمت ورود، حد ضرر و حد سود
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatTile(
                label = "ورود (Entry)",
                value = formatPriceFor(record.symbol, record.entryPrice),
                valueColor = AurumColors.Gold,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "حد ضرر (SL · -$${String.format(Locale.US, "%.0f", record.fixedRiskUsd)})",
                value = formatPriceFor(record.symbol, record.stopLossPrice),
                valueColor = AurumColors.Red,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "حد سود (TP · +$${String.format(Locale.US, "%.1f", record.expectedProfitUsd)})",
                value = formatPriceFor(record.symbol, record.takeProfitPrice),
                valueColor = AurumColors.Green,
                modifier = Modifier.weight(1f),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                imageBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "اسکرین‌شات چارت ایچیموکو ${record.symbol}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1200f / 820f)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, AurumColors.Line, RoundedCornerShape(10.dp))
                            .clickable { onOpenViewer() },
                    )
                }
                Text(
                    text = "📐 ${record.rrFormulaFa}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Cyan,
                )
                Text(
                    text = "🛡️ مبنای حد ضرر: ${record.slBasisFa}  |  🎯 مبنای حد سود: ${record.tpBasisFa}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
fun TradeSnapshotViewerDialog(
    record: ArchivedTradeSnapshotRecord,
    onDismiss: () -> Unit,
) {
    var imageBitmap by remember(record.tradeId) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(record.tradeId) {
        withContext(Dispatchers.IO) {
            val bmp = TradeSnapshotArchiveManager.loadBitmapForRecord(record)
            if (bmp != null) {
                imageBitmap = bmp.asImageBitmap()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .clip(RoundedCornerShape(16.dp))
                .background(AurumColors.Surface)
                .border(1.5.dp, AurumColors.Gold, RoundedCornerShape(16.dp))
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "📸 اسکرین‌شات آرشیو معامله: ${record.symbol} [${record.timeframeLabel}]",
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.Gold,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${record.strategyTitleFa} · ${formatDateTime(record.openedAtMs)} · حجم فایل: ${(record.imageSizeBytes / 1024L).coerceAtLeast(1L)}KB WebP",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                }
                Pill(
                    text = "✖ بستن",
                    color = AurumColors.Red,
                    modifier = Modifier.clickable { onDismiss() },
                )
            }

            imageBitmap?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = "اسکرین‌شات معامله ${record.symbol}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1200f / 820f)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, AurumColors.Line, RoundedCornerShape(12.dp)),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AurumColors.SurfaceAlt)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "📊 مقادیر دقیق خطوط ایچیموکو و اندیکاتورها در لحظه باز شدن معامله:",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.Cyan,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Tenkan=${formatPriceFor(record.symbol, record.tenkan)} · Kijun=${formatPriceFor(record.symbol, record.kijun)} · SpanA=${formatPriceFor(record.symbol, record.currentSpanA)} · SpanB=${formatPriceFor(record.symbol, record.currentSpanB)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextPrimary,
                )
                Text(
                    text = "Future SpanA=${formatPriceFor(record.symbol, record.futureSpanA)} · Future SpanB=${formatPriceFor(record.symbol, record.futureSpanB)} · ATR14=${formatPriceFor(record.symbol, record.atr14)} · RSI14=${String.format(Locale.US, "%.1f", record.rsi14)} · ADX14=${String.format(Locale.US, "%.1f", record.adx14)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextSecondary,
                )
                Text(
                    text = "📐 ${record.rrFormulaFa}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.Gold,
                )
                Text(
                    text = "🛡️ مبنای حد ضرر: ${record.slBasisFa}  |  🎯 مبنای حد سود: ${record.tpBasisFa}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextSecondary,
                )
            }

            if (record.conditionsSummary.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AurumColors.Bg)
                        .border(1.dp, AurumColors.Line, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "✅ چک‌لیست ۱۶ شرط ایچیموکو در لحظه ورود:",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.Green,
                        fontWeight = FontWeight.Bold,
                    )
                    record.conditionsSummary.forEach { c ->
                        val ok = c.status.equals("CONFIRMED", true) || c.status.contains("OK", true)
                        Text(
                            text = "${if (ok) "✅" else "⚠️"} ${c.name}: ${c.detail}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ok) AurumColors.TextPrimary else AurumColors.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}
