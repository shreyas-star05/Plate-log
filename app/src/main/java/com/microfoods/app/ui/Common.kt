package com.microfoods.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.microfoods.app.AppVm

val Leaf = Color(0xFF2E6B4F)
val Turmeric = Color(0xFFF2B33D)
val Chilli = Color(0xFFB3412E)
val Paper = Color(0xFFF7F2EA)
val Ink = Color(0xFF1F2420)
val Quiet = Color(0xFF66706A)

@Composable
fun PlateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Leaf, onPrimary = Color.White, secondary = Turmeric, background = Paper, surface = Color.White,
            onBackground = Ink, onSurface = Ink, surfaceVariant = Color(0xFFEDE6DA), error = Chilli,
        ),
        content = content,
    )
}

/**
 * Shows the view model's messages as snackbars. Keyed on Unit, so clearing the message cannot cancel the
 * snackbar that is showing it (that bug hid every message in build 4, including the connection test result).
 */
@Composable
fun rememberMessages(vm: AppVm): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        snapshotFlow { vm.message }.collect { m ->
            if (m != null) {
                vm.message = null   // taken now, so it is not shown again on the next screen
                host.showSnackbar(m, withDismissAction = true)
            }
        }
    }
    return host
}

fun fmt0(x: Double?): String = if (x == null) "-" else "%,d".format(Math.round(x))
fun fmt1(x: Double?): String = if (x == null) "-" else "%.1f".format(x)
fun qtyText(q: Double): String = if (q == Math.floor(q)) q.toLong().toString() else "%.1f".format(q)

/** A labelled bar: value against target. */
@Composable
fun TargetBar(label: String, value: Double, target: Double, unit: String, modifier: Modifier = Modifier) {
    val share = if (target > 0) (value / target).toFloat() else 0f
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = Quiet, modifier = Modifier.width(72.dp))
            Text("${fmt0(value)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(" / ${fmt0(target)} $unit", style = MaterialTheme.typography.bodyMedium, color = Quiet)
            Spacer(Modifier.weight(1f))
            Text("${Math.round(share * 100)}%", style = MaterialTheme.typography.labelLarge, color = if (share > 1.1f) Chilli else Quiet)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { share.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = if (share > 1.1f) Chilli else Leaf,
            trackColor = Color(0xFFE4DCCF),
        )
    }
}

/** Three dots for the model's confidence. */
@Composable
fun ConfidenceDots(c: Double) {
    val n = when { c >= 0.75 -> 3; c >= 0.5 -> 2; c > 0 -> 1; else -> 0 }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i -> Text(if (i < n) "●" else "○", color = if (n <= 1) Chilli else Leaf, style = MaterialTheme.typography.labelSmall) }
    }
}

val PagePadding = Modifier.padding(horizontal = 16.dp)
