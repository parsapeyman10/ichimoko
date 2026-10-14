package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aurum.edge.data.AppNetwork
import com.aurum.edge.data.EmbeddedProxy
import com.aurum.edge.ui.theme.AurumColors

internal fun proxyLampConnected(status: EmbeddedProxy.Status, route: AppNetwork.Route): Boolean =
    route.enabled && route.ready && status == EmbeddedProxy.Status.ONLINE

/** In the shared Scaffold: present on every tab, not inside scrolling page content. */
@Composable
internal fun ProxyStatusLamp(enabled: Boolean, status: EmbeddedProxy.Status, route: AppNetwork.Route,
                             onClick: () -> Unit) {
    if (!enabled) return
    val connected = proxyLampConnected(status, route)
    val label = if (connected) "پروکسی متصل" else "پروکسی قطع / در حال اتصال"
    Row(Modifier.fillMaxWidth().statusBarsPadding().background(AurumColors.Surface)
        .clickable(onClick = onClick).semantics(mergeDescendants = true) { contentDescription = label }
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(if (connected) AurumColors.Green else AurumColors.Red, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = if (connected) AurumColors.Green else AurumColors.Red)
        Spacer(Modifier.width(8.dp))
        Text("تنظیمات اتصال", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
    }
}
