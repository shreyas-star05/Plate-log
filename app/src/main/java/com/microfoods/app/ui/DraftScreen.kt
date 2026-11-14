package com.microfoods.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.microfoods.app.AppVm
import com.microfoods.app.Photos
import com.microfoods.core.AppData
import com.microfoods.core.Days
import com.microfoods.core.DraftItem
import com.microfoods.core.FoodDb
import com.microfoods.core.Nutrients
import com.microfoods.core.Portions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** Marker for "add a new line" in the food picker. */
private const val NEW_LINE = "\u0000new"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftScreen(vm: AppVm, data: AppData) {
    val d = vm.draft ?: return
    val host = rememberMessages(vm)
    val db = vm.db
    val p = data.settings.portions
    val test = data.settings.testMode
    var pickerFor by remember { mutableStateOf<String?>(null) }
    val thumb = remember(d.photoFile) { Photos.thumb(d.photoFile, 240)?.asImageBitmap() }
    val total = Nutrients.sum(d.items.mapNotNull { it.nutrients(db, p) })

    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Text("Check the meal") },
                navigationIcon = { IconButton(onClick = vm::cancelDraft) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
            )
        },
        snackbarHost = { SnackbarHost(host, Modifier.imePadding()) },
        bottomBar = {
            Surface(shadowElevation = 8.dp, color = Color.White) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${fmt0(total.kcal)} kcal  ·  ${fmt1(total.protein)} g protein", fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("meal-total"))
                        val unmatched = d.items.count { it.foodId == null }
                        if (unmatched > 0) Text("$unmatched item(s) without a food match count as 0", color = Chilli, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = vm::confirm, enabled = !d.loading && d.items.isNotEmpty(),
                        modifier = Modifier.testTag("confirm"), colors = ButtonDefaults.buttonColors(containerColor = Leaf),
                    ) { Text("Confirm") }
                }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).testTag("draft-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (thumb != null) {
                        Image(thumb, "Meal photo", Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        if (d.hint.isNotBlank()) Text("“${d.hint}”", style = MaterialTheme.typography.bodyMedium)
                        if (d.model.isNotBlank()) Text("Read by ${d.model}. Nutrients come from the food list.", color = Quiet, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Days.SLOTS) { s -> FilterChip(selected = d.slot == s, onClick = { vm.setSlot(s) }, label = { Text(s) }) }
                }
            }
            if (d.loading) item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Leaf)
                    Spacer(Modifier.width(12.dp))
                    Text(if (test) "Identifying (twice, for the accuracy test)..." else "Identifying the food...")
                }
            }
            d.error?.let { e ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFBE9E5))) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Could not identify the food: $e", color = Chilli)
                            Row {
                                TextButton(onClick = vm::analyse) { Text("Try again") }
                                TextButton(onClick = { pickerFor = NEW_LINE }) { Text("Add foods myself") }
                            }
                        }
                    }
                }
            }
            itemsIndexed(d.items, key = { _, it -> it.key }) { i, item ->
                ItemCard(i, item, db, p, vm, test, onPick = { pickerFor = item.key })
            }
            if (!d.loading) item {
                OutlinedButton(onClick = { pickerFor = NEW_LINE }, modifier = Modifier.fillMaxWidth().testTag("add-item")) {
                    Icon(Icons.Filled.Add, null); Text("  Add a food")
                }
            }
        }
    }

    pickerFor?.let { target ->
        FoodPicker(db, onDismiss = { pickerFor = null }) { food ->
            vm.pickFood(if (target == NEW_LINE) null else target, food)
            pickerFor = null
        }
    }
}

@Composable
private fun ItemCard(i: Int, item: DraftItem, db: FoodDb, p: Portions, vm: AppVm, test: Boolean, onPick: () -> Unit) {
    val food = db.byId(item.foodId)
    val grams = item.grams(db, p)
    val n = item.nutrients(db, p)
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().testTag("item-$i")) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onPick).testTag("item-name-$i")) {
                    if (food != null) {
                        Text(food.name, fontWeight = FontWeight.SemiBold)
                        if (!item.seenAs.equals(food.name, ignoreCase = true)) Text("seen as ${item.seenAs}", color = Quiet, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(item.seenAs, fontWeight = FontWeight.SemiBold)
                        Text("No match in your food list. Tap to pick one.", color = Chilli, style = MaterialTheme.typography.bodySmall)
                    }
                    item.addonFor?.let { Text("on $it (from your hint)", color = Quiet, style = MaterialTheme.typography.bodySmall) }
                }
                ConfidenceDots(item.confidence)
                IconButton(onClick = { vm.removeItem(item.key) }, modifier = Modifier.testTag("delete-$i")) { Icon(Icons.Filled.Delete, "Remove", tint = Quiet) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { vm.updateItem(item.key) { it.stepped(-1.0) } }, modifier = Modifier.size(36.dp).testTag("minus-$i")) { Icon(Icons.Filled.Remove, "Less") }
                Text(qtyText(item.qty), Modifier.width(52.dp).testTag("qty-$i"), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                FilledTonalIconButton(onClick = { vm.updateItem(item.key) { it.stepped(+1.0) } }, modifier = Modifier.size(36.dp).testTag("plus-$i")) { Icon(Icons.Filled.Add, "More") }
                Spacer(Modifier.width(8.dp))
                UnitMenu(i, item, food?.unitChoices() ?: listOf("g")) { u -> vm.updateItem(item.key) { it.copy(unit = u, qty = if (u == "g") (grams ?: 100.0).let { g -> Math.round(g).toDouble() } else 1.0, manualGrams = null) } }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp)) {
                    Text("${fmt0(grams)} g", fontWeight = FontWeight.Medium)
                    Text(if (n != null) "${fmt0(n.kcal)} kcal · ${fmt1(n.protein)} g P" else "no values", color = Quiet, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (item.gramsSource(db, p) == "model") {
                Text("No weight for '${item.unit}' on this food: using the AI's ${fmt0(item.aiGrams)} g. Pick another unit to fix.", color = Chilli, style = MaterialTheme.typography.bodySmall)
            }
            if (test) {
                var text by remember(item.key) { mutableStateOf(item.weighedG?.let { qtyText(it) } ?: "") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        text = v.filter { it.isDigit() || it == '.' }.take(6)
                        vm.updateItem(item.key) { it.copy(weighedG = text.toDoubleOrNull()?.takeIf { g -> g > 0 }) }
                    },
                    label = { Text("Weighed g (kitchen scale)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 6.dp).testTag("weighed-$i"),
                )
            }
        }
    }
}

@Composable
private fun UnitMenu(i: Int, item: DraftItem, choices: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("unit-$i")) { Text(item.unit) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { u -> DropdownMenuItem(text = { Text(u) }, onClick = { open = false; onPick(u) }) }
        }
    }
}

@Composable
fun FoodPicker(db: FoodDb, onDismiss: () -> Unit, onPick: (com.microfoods.core.Food) -> Unit) {
    var q by remember { mutableStateOf("") }
    val results = remember(q, db.foods.size) { db.search(q, 40) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun close() { focus.clearFocus(); keyboard?.hide() }
    AlertDialog(
        onDismissRequest = { close(); onDismiss() },
        confirmButton = { TextButton(onClick = { close(); onDismiss() }) { Text("Close") } },
        title = { Text("Pick a food") },
        text = {
            Column {
                OutlinedTextField(
                    q, { q = it }, singleLine = true, placeholder = { Text("bhendi, varan, poli...") },
                    modifier = Modifier.fillMaxWidth().testTag("food-search"),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(results, key = { it.id }) { f ->
                        Column(Modifier.fillMaxWidth().clickable { close(); onPick(f) }.padding(vertical = 10.dp).testTag("food-${f.id}")) {
                            Text(f.name, fontWeight = FontWeight.Medium)
                            Text("${fmt0(f.per100.kcal)} kcal · ${fmt1(f.per100.protein)} g P per 100 g · ${f.source}", color = Quiet, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
    )
}
