package com.microfoods.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.microfoods.app.AppVm
import com.microfoods.app.Screen
import com.microfoods.core.AppData
import com.microfoods.core.Days
import com.microfoods.core.Meal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dayTitle = DateTimeFormatter.ofPattern("EEE d MMM")
private val clock = DateTimeFormatter.ofPattern("H:mm").withZone(Days.IST)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(vm: AppVm, data: AppData) {
    val host = rememberMessages(vm)
    val s = data.settings
    val today = Days.dayOf(System.currentTimeMillis(), s.portions.dayEndHour)
    val key = today.toString()
    val meals = data.mealsOn(key)
    val total = data.totalOn(key)
    val note = data.dayNote(key)
    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Text("Today, ${today.format(dayTitle)}", fontWeight = FontWeight.SemiBold) },
                actions = { IconButton(onClick = { vm.screen = Screen.SETTINGS }, modifier = Modifier.testTag("open-settings")) { Icon(Icons.Filled.Settings, "Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::startMeal, modifier = Modifier.testTag("log-meal"),
                icon = { Icon(Icons.Filled.CameraAlt, null) }, text = { Text("Log a meal") },
                containerColor = Leaf, contentColor = Color.White,
            )
        },
        snackbarHost = { SnackbarHost(host, Modifier.imePadding()) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).testTag("today-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TargetBar("kcal", total.kcal, s.kcalTarget, "")
                        TargetBar("protein", total.protein, s.proteinTarget, "g")
                        Text(
                            "Carbs ${fmt0(total.carbs)} g   Fat ${fmt0(total.fat)} g   Fibre ${fmt0(total.fibre)} g",
                            style = MaterialTheme.typography.bodyMedium, color = Quiet,
                        )
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Days.DAY_TYPES) { t ->
                        FilterChip(
                            selected = note?.dayType == t,
                            onClick = { vm.setDayType(key, if (note?.dayType == t) null else t) },
                            label = { Text(t) }, modifier = Modifier.testTag("daytype-$t"),
                        )
                    }
                }
            }
            if (s.testMode) item {
                Text(
                    "Accuracy test is on: weigh each item and type the grams on the draft card.",
                    style = MaterialTheme.typography.bodyMedium, color = Chilli,
                )
            }
            val pending = data.pendingMeals.size + data.pendingDays.size
            if (pending > 0) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudUpload, null, tint = Quiet)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (data.pendingMeals.isNotEmpty()) "${data.pendingMeals.size} meal(s) waiting to reach the Sheet" else "Day notes waiting to reach the Sheet",
                        Modifier.weight(1f), color = Quiet,
                    )
                    TextButton(onClick = { vm.syncNow() }, enabled = !vm.syncing, modifier = Modifier.testTag("sync-now")) { Text(if (vm.syncing) "Sending..." else "Send now") }
                }
            }
            if (meals.isEmpty()) item {
                Text("Nothing logged yet today.", Modifier.padding(vertical = 24.dp), color = Quiet)
            }
            items(meals, key = { it.mealId }) { MealCard(it) }
            item { WeekList(data, today) }
        }
    }
}

@Composable
private fun MealCard(m: Meal) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.slot, fontWeight = FontWeight.SemiBold)
                Text("  ${clock.format(Instant.ofEpochMilli(m.loggedAt))}", color = Quiet)
                if (m.testMode) Text("  test", color = Chilli, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text("${fmt0(m.total.kcal)} kcal  ${fmt0(m.total.protein)} g P", fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Icon(if (m.synced) Icons.Filled.CloudDone else Icons.Filled.CloudUpload, if (m.synced) "In the Sheet" else "Waiting", tint = if (m.synced) Leaf else Quiet)
            }
            Text(
                m.items.joinToString(", ") { "${qtyText(it.qty)} ${it.unit} ${it.name}" + if (it.foodId == null) " (no match)" else "" },
                color = Quiet, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WeekList(data: AppData, today: LocalDate) {
    val days = (1..6).map { today.minusDays(it.toLong()) }.filter { data.mealsOn(it.toString()).isNotEmpty() }
    if (days.isEmpty()) return
    Column(Modifier.padding(top = 8.dp)) {
        Text("Last 7 days", style = MaterialTheme.typography.titleSmall, color = Quiet)
        Spacer(Modifier.height(4.dp))
        days.forEach { d ->
            val t = data.totalOn(d.toString())
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(d.format(dayTitle), Modifier.weight(1f))
                Text(data.dayNote(d.toString())?.dayType ?: "", Modifier.width(96.dp), color = Quiet)
                Text("${fmt0(t.kcal)} kcal", Modifier.width(88.dp))
                Text("${fmt0(t.protein)} g P")
            }
        }
    }
}
