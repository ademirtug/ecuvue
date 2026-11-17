@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.leximo.ecuvue.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import app.leximo.ecuvue.core.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.leximo.ecu_vue.resources.Res
import app.leximo.ecu_vue.resources.brand_mark
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalLayoutDirection

@Composable internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable internal fun Heading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable internal fun Message(message: String, details: String? = null, dismiss: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val w = LocalWords.current
    Panel {
        Text(message)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!details.isNullOrBlank()) TextButton(onClick = { expanded = !expanded }) { Text(w("connectionErrorDetails")) }
            TextButton(onClick = dismiss) { Text(w("dismiss")) }
        }
        if (expanded) Text(details.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun CapabilitiesPanel(capabilities: Capabilities) {
    val w = LocalWords.current
    var expanded by remember { mutableStateOf(false) }
    Panel {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(w("detectedCapabilities"), fontWeight = FontWeight.SemiBold)
            Icon(Icons.Outlined.ExpandMore, null)
        }
        if (expanded) {
            Text("${w("reportedAdapter")}: ${capabilities.adapterIdentity ?: "—"}")
            Text(capabilities.protocolName)
            Text("${w("supportedPids")}: ${capabilities.livePids.sorted().joinToString { it.toString(16).uppercase().padStart(2, '0') }}")
            fun status(value: Support) = w(when (value) { Support.SUPPORTED -> "supported"; Support.UNSUPPORTED -> "notSupported"; Support.UNAVAILABLE -> "noResponse"; Support.UNKNOWN -> "notChecked" })
            Text("${w("pending")}: ${status(capabilities.pendingCodes)}")
            Text("${w("permanent")}: ${status(capabilities.permanentCodes)}")
            Text("${w("freezeFrame")}: ${status(capabilities.freezeFrame)}")
            if (!capabilities.pidDiscoveryComplete) Text(w("partialDiscovery"))
            Text(w("capabilityHint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal data class Metric(val key: String, val unit: String, val pid: Int?, val value: (LiveData) -> Double?)

internal val metrics = listOf(Metric("engineSpeed", "rpm", 12) { it.rpm }, Metric("vehicleSpeed", "km/h", 13) { it.speed },
    Metric("coolant", "°C", 5) { it.coolant }, Metric("adapterVoltage", "V", null) { it.voltage },
    Metric("engineLoad", "%", 4) { it.load }, Metric("throttle", "%", 17) { it.throttle })

internal fun number(value: Double?): String = value?.let { if (it % 1.0 == 0.0) it.toLong().toString() else ((it * 10).roundToInt() / 10.0).toString() } ?: "—"

@Composable internal fun Metrics(data: LiveData?, capabilities: Capabilities, history: List<LiveData>, charts: Boolean) {
    val w = LocalWords.current
    val visible = metrics.filter { if (it.pid == null) capabilities.voltage == Support.SUPPORTED else it.pid in capabilities.livePids }
    if (visible.isEmpty()) { Text(w("noSupportedReadings")); return }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth > 900.dp) 3 else if (maxWidth > 420.dp) 2 else 1
        val width = (maxWidth - 12.dp * (columns - 1)) / columns
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            visible.forEach { metric ->
                Surface(Modifier.width(width), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(w(metric.key), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(number(data?.let(metric.value)), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium)
                            Text(metric.unit, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        if (charts) Sparkline(history.map { metric.value(it) })
                    }
                }
            }
        }
    }
}

@Composable private fun Sparkline(values: List<Double?>) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val present = values.filterNotNull()
        if (present.size < 2) return@Canvas
        val min = present.min(); val range = (present.max() - min).coerceAtLeast(1.0)
        val path = Path(); var started = false
        values.forEachIndexed { i, value ->
            if (value == null) started = false else {
                val x = size.width * i / (values.size - 1).coerceAtLeast(1)
                val y = (size.height - 4) - ((value - min) / range * (size.height - 8)).toFloat()
                if (!started) path.moveTo(x, y) else path.lineTo(x, y)
                started = true
            }
        }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
    }
}

@Composable internal fun CodeText(value: String) = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
}

@Composable internal fun BrandMark(size: Dp) {
    Image(painterResource(Res.drawable.brand_mark), null, Modifier.size(size), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
}

internal fun instant(timestamp: Long) = kotlin.time.Instant.fromEpochMilliseconds(timestamp).toString().replace('T', ' ').take(19) + " UTC"
