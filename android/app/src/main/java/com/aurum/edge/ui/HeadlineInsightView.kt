package com.aurum.edge.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurum.edge.data.HeadlineInsight
import com.aurum.edge.data.ResearchState
import com.aurum.edge.ui.theme.AurumColors

/** One consistent research readout in all four workspaces; never a ninth-gate verdict. */
@Composable
fun HeadlineInsightView(insight: HeadlineInsight, identity: String) {
    var showMethod by remember(identity) { mutableStateOf(false) }
    val valid = insight.note.state == ResearchState.CONTEXT
    Text(insight.priority, style = MaterialTheme.typography.bodySmall,
        color = if (valid) AurumColors.Cyan else AurumColors.Gold, modifier = Modifier.padding(top = 6.dp))
    Text("مبنای اهمیت: ${insight.relevance}", style = MaterialTheme.typography.labelSmall,
        color = AurumColors.TextSecondary)
    Text("جهت احتمالی: ${insight.possibleDirection}", style = MaterialTheme.typography.labelSmall,
        color = AurumColors.Gold)
    Text("اثر مشاهده‌شده: ${insight.observedEffect}", style = MaterialTheme.typography.labelSmall,
        color = AurumColors.TextMuted)
    TextButton(onClick = { showMethod = !showMethod }) {
        Text(if (showMethod) "بستن روش بررسی" else "چرا این برچسب؟ محدودیت منبع")
    }
    if (showMethod) Text("${insight.note.title}: ${insight.note.detail}",
        style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
}
