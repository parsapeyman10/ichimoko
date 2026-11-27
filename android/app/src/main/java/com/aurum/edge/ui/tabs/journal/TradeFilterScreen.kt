package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.TradeFilter
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale

private fun fmt(v: Double?): String = v?.let { String.format(Locale.US, "%.2f", it) } ?: "نامعلوم"
private fun choiceLabel(d: String, value: String, trades: List<PaperTrade>): String = when {
    value == TradeFilter.UNKNOWN -> "نامعلوم / ثبت‌نشده"
    d == "strategy" -> trades.firstOrNull { it.strategyId == value }?.strategyTitleFa?.takeIf { it.isNotBlank() }?.let { "$it ($value)" } ?: value
    d == "direction" && value == "BUY" -> "خرید"
    d == "direction" && value == "SELL" -> "فروش"
    d == "weekday" -> mapOf("MONDAY" to "دوشنبه", "TUESDAY" to "سه‌شنبه", "WEDNESDAY" to "چهارشنبه", "THURSDAY" to "پنجشنبه", "FRIDAY" to "جمعه", "SATURDAY" to "شنبه", "SUNDAY" to "یکشنبه")[value] ?: value
    else -> value
}

@Composable
private fun Selector(title: String, value: String?, options: List<Pair<String, String>>, onChange: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$title: ${options.firstOrNull { it.first == value }?.second ?: "همه"}", maxLines = 2, fontSize = 11.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
            DropdownMenuItem(text = { Text("همه") }, onClick = { onChange(null); expanded = false })
            options.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label, fontSize = 11.sp, maxLines = 3) }, onClick = { onChange(key); expanded = false })
            }
        }
    }
}

/** Exploratory view only: filters do not affect orders, learning, or the underlying journal. */
@Composable
fun TradeFilterScreen(trades: List<PaperTrade>, onBack: () -> Unit, onChart: (String) -> Unit) {
    var choices by rememberSaveable { mutableStateOf(hashMapOf<String, String>()) }
    var rangeValues by rememberSaveable { mutableStateOf(hashMapOf<String, String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var groupBy by rememberSaveable { mutableStateOf("session") }
    var filtersExpanded by rememberSaveable { mutableStateOf(true) }
    var numericExpanded by rememberSaveable { mutableStateOf(false) }
    val criteria = remember(choices, rangeValues, query, from, to) {
        TradeFilter.Criteria(choices, query, from, to, TradeFilter.numerics.associate { it.key to (rangeValues[it.key + ":min"].orEmpty() to rangeValues[it.key + ":max"].orEmpty()) })
    }
    val error = remember(criteria) { TradeFilter.validation(criteria) }
    val result = remember(trades, criteria) { TradeFilter.apply(trades, criteria).sortedByDescending { it.openedAt } }
    val summary = remember(result) { TradeFilter.summary(result) }
    val grouped = remember(result, groupBy) {
        val d = TradeFilter.dimensions.firstOrNull { it.key == groupBy } ?: TradeFilter.dimensions.first()
        result.groupBy(d.value).map { (k, v) -> k to TradeFilter.summary(v) }.sortedByDescending { it.second.closed }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("بازگشت") }
                OutlinedButton(onClick = { choices = hashMapOf(); rangeValues = hashMapOf(); query = ""; from = ""; to = "" }) { Text("پاک‌کردن فیلترها") }
            }
            Text("🔎 فیلتر و تحلیل همهٔ معاملات", color = AurumColors.Gold, fontWeight = FontWeight.Bold)
            Text("فیلترها با هم ترکیب می‌شوند؛ فقط همین صفحه را تغییر می‌دهند، نه ژورنال یا تصمیم معامله. ساعت و تاریخ ورود UTC است.", color = AurumColors.TextSecondary, fontSize = 11.sp)
            OutlinedButton(onClick = { filtersExpanded = !filtersExpanded }) { Text(if (filtersExpanded) "بستن فیلترها ▲" else "نمایش فیلترها ▼") }
        }
        if (filtersExpanded) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("جستجو: شماره، نماد، یادداشت، دلیل، تگ و شرط") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(from, { from = it }, Modifier.weight(1f), label = { Text("از YYYY-MM-DD", fontSize = 10.sp) }, singleLine = true)
                    OutlinedTextField(to, { to = it }, Modifier.weight(1f), label = { Text("تا YYYY-MM-DD", fontSize = 10.sp) }, singleLine = true)
                }
            }
            itemsIndexed(TradeFilter.dimensions, key = { _, d -> "d:${d.key}" }) { _, d ->
                val options = remember(trades, d.key) { trades.map(d.value).distinct().sorted().map { it to choiceLabel(d.key, it, trades) } }
                Selector(d.titleFa, choices[d.key], options) { v -> choices = HashMap(choices).apply { if (v == null) remove(d.key) else put(d.key, v) } }
            }
            item {
                val tags = remember(trades) { (trades.flatMap { it.outcomeTags }.distinct() + listOfNotNull(TradeFilter.UNKNOWN.takeIf { trades.any { t -> t.outcomeTags.isEmpty() } })).sorted().map { it to choiceLabel("tag", it, trades) } }
                Selector("برچسب دلیل نتیجه", choices["tag"], tags) { v -> choices = HashMap(choices).apply { if (v == null) remove("tag") else put("tag", v) } }
                OutlinedButton(onClick = { numericExpanded = !numericExpanded }) { Text(if (numericExpanded) "بستن بازه‌های عددی ▲" else "بازه‌های عددی ▼") }
                if (numericExpanded) Text("خالی = بدون محدودیت؛ دادهٔ ثبت‌نشده در بازه وارد نمی‌شود.", color = AurumColors.Gold, fontSize = 11.sp)
            }
            if (numericExpanded) itemsIndexed(TradeFilter.numerics, key = { _, n -> "n:${n.key}" }) { _, n ->
                Column {
                    Text(n.titleFa, color = AurumColors.TextSecondary, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("min" to "حداقل", "max" to "حداکثر").forEach { (suffix, label) ->
                            val key = n.key + ":" + suffix
                            OutlinedTextField(rangeValues[key].orEmpty(), { v -> rangeValues = HashMap(rangeValues).apply { put(key, v) } }, Modifier.weight(1f), label = { Text(label) }, singleLine = true)
                        }
                    }
                }
            }
        }
        item {
            if (error != null) Text(error, color = AurumColors.Red)
            Text("نتیجه: ${result.size} از ${trades.size} معامله · ${summary.open} باز · ${summary.closed} بسته", color = AurumColors.Gold, fontWeight = FontWeight.Bold)
            Text("${summary.wins} برد / ${summary.losses} باخت / ${summary.breakeven} سربه‌سر / ${summary.unknown} نتیجه نامعلوم · نرخ برد ${fmt(if (summary.wins + summary.losses > 0) summary.wins * 100.0 / (summary.wins + summary.losses) else null)}٪", color = AurumColors.TextSecondary, fontSize = 11.sp)
            Text("خالص ${fmt(summary.net.takeIf { summary.wins + summary.losses + summary.breakeven > 0 })} دلار · میانگین ${fmt(summary.meanR)}R (${summary.rCount} نمونهٔ ریسک معتبر) · ضریب سود ${fmt(summary.profitFactor)}", color = AurumColors.TextSecondary, fontSize = 11.sp)
            Text("مجموع شامل منابع انتخاب‌شده است؛ بک‌تست پول واقعی یا موجودی حساب نیست. نمونهٔ کم و انتخاب چند فیلتر می‌تواند گمراه‌کننده باشد؛ رابطه علت و معلول یا سود آینده ثابت نمی‌کند.", color = AurumColors.TextSecondary, fontSize = 10.sp)
            Selector("تفکیک نتایج", groupBy, TradeFilter.dimensions.map { it.key to it.titleFa }) { if (it != null) groupBy = it }
        }
        itemsIndexed(grouped, key = { _, pair -> "g:$groupBy:${pair.first}" }) { _, (key, group) ->
            Text("${choiceLabel(groupBy, key, trades)}: ${group.closed} بسته، ${group.open} باز · ${group.wins} برد / ${group.losses} باخت · خالص ${fmt(group.net.takeIf { group.wins + group.losses + group.breakeven > 0 })}$ · ${fmt(group.meanR)}R (${group.rCount}) · PF ${fmt(group.profitFactor)}", color = AurumColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().background(AurumColors.SurfaceAlt, RoundedCornerShape(6.dp)).padding(6.dp))
        }
        item { Text("فهرست کامل معاملات منطبق (${result.size})", color = AurumColors.Gold, fontWeight = FontWeight.Bold) }
        itemsIndexed(result) { _, trade -> FilterTradeRow(trade, onChart) }
    }
}

@Composable
private fun FilterTradeRow(t: PaperTrade, onChart: (String) -> Unit) {
    var expanded by rememberSaveable(t.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(AurumColors.SurfaceAlt, RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("#${t.displayTradeNumber} · ${t.symbol} · ${t.interval.label} · ${TradeFilter.outcome(t)} · ${if (t.isBacktest) "بک‌تست" else "ژورنال"}", color = AurumColors.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("${t.strategyTitleFa.ifBlank { t.strategyId }} · ${t.action.name} · ${formatDateTime(t.openedAt)} (محلی) · خالص ${if (t.isOpen) "باز" else fmt(t.pnlUsd)}$ · ${fmt(TradeFilter.realizedR(t))}R", color = AurumColors.TextSecondary, fontSize = 11.sp)
        if (expanded) {
            Text("ورود ${fmt(t.entry)} · SL ${fmt(t.stopLoss)} · TP ${fmt(t.takeProfit)} · ریسک ثبت‌شده ${if (t.fixedRiskUsd > 0) fmt(t.fixedRiskUsd) else "نامعلوم"}$ · R:R ${fmt(t.riskReward)}", color = AurumColors.TextPrimary, fontSize = 11.sp)
            Text(t.techSnapshotLineFa, color = AurumColors.TextSecondary, fontSize = 11.sp)
            Text("فاز ${t.stateTrendPhaseCode.ifBlank { "نامعلوم" }} · الگو ${t.preEntry3CandleCategoryFa.ifBlank { "نامعلوم" }} · MFE ${fmt(t.mfeR.takeIf { it >= 0 })}R · MAE ${fmt(t.maeR.takeIf { it >= 0 })}R", color = AurumColors.TextSecondary, fontSize = 11.sp)
            Text("خروج: ${t.exitReason?.takeIf { it.isNotBlank() } ?: "ثبت‌نشده"}\nدلیل نتیجه: ${t.outcomeReasonFa.ifBlank { "ثبت‌نشده" }}\nیادداشت: ${t.note.ifBlank { "ثبت‌نشده" }}", color = AurumColors.TextSecondary, fontSize = 11.sp)
            OutlinedButton(onClick = { onChart(t.symbol) }) { Text("چارت نماد") }
        } else Text("برای جزئیات لمس کنید", color = AurumColors.TextSecondary, fontSize = 10.sp)
    }
}
