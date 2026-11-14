package com.microfoods.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.microfoods.app.AppVm
import com.microfoods.app.Screen
import com.microfoods.core.Accuracy
import com.microfoods.core.AppData
import com.microfoods.core.Food
import com.microfoods.core.Nutrients
import com.microfoods.core.Portions

/** Foods whose one-piece weight you can set from your own kitchen. */
private val PIECE_FOODS = listOf("chapati" to "Chapati", "thepla" to "Thepla", "pav" to "Pav", "egg_boiled" to "Egg", "omelette" to "Omelette")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppVm, data: AppData) {
    val host = rememberMessages(vm)
    val s = data.settings
    var endpoint by remember { mutableStateOf(s.endpoint) }
    var token by remember { mutableStateOf(s.token) }
    var katori by remember { mutableStateOf(qtyText(s.portions.katoriMl)) }
    var dayEnd by remember { mutableStateOf(s.portions.dayEndHour.toString()) }
    var kcal by remember { mutableStateOf(qtyText(s.kcalTarget)) }
    var protein by remember { mutableStateOf(qtyText(s.proteinTarget)) }
    var testMode by remember { mutableStateOf(s.testMode) }
    val pieces = remember { PIECE_FOODS.associate { (id, _) -> id to mutableStateOf(s.portions.pieceG[id]?.let(::qtyText) ?: "") } }

    fun current() = s.copy(
        endpoint = endpoint.trim(), token = token.trim(), testMode = testMode,
        kcalTarget = kcal.toDoubleOrNull()?.takeIf { it in 800.0..6000.0 } ?: s.kcalTarget,
        proteinTarget = protein.toDoubleOrNull()?.takeIf { it in 20.0..400.0 } ?: s.proteinTarget,
        portions = Portions(
            katoriMl = katori.toDoubleOrNull()?.takeIf { it in 30.0..1000.0 } ?: s.portions.katoriMl,
            pieceG = pieces.mapNotNull { (id, v) -> v.value.toDoubleOrNull()?.takeIf { it > 0 }?.let { id to it } }.toMap(),
            dayEndHour = dayEnd.toIntOrNull()?.coerceIn(0, 8) ?: s.portions.dayEndHour,
        ),
    )

    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { vm.saveSettings(current()); vm.screen = Screen.TODAY }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
            )
        },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Your Google Sheet")
            Text("Paste the web app link and the token from the script log (README, step 4). Type demo to try the app with sample answers.", color = Quiet, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Web app link") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("endpoint"))
            OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("token"))
            OutlinedButton(onClick = { vm.saveSettings(current()); vm.ping() }, modifier = Modifier.testTag("ping")) { Text("Save and test the connection") }

            Section("Your kitchen")
            Text("Fill your katori with water and weigh it: grams of water = ml. Weigh one chapati as made at home.", color = Quiet, style = MaterialTheme.typography.bodySmall)
            NumberField("Katori size, ml", katori, "katori") { katori = it }
            PIECE_FOODS.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (id, label) ->
                        val v = pieces.getValue(id)
                        val default = vm.db.byId(id)?.units?.get("piece")
                        NumberField("$label, g each (default ${fmt0(default)})", v.value, "piece-$id", Modifier.weight(1f)) { v.value = it }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            NumberField("Day ends at (hour, 0-8)", dayEnd, "day-end") { dayEnd = it }

            Section("Targets")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("kcal a day", kcal, "kcal-target", Modifier.weight(1f)) { kcal = it }
                NumberField("Protein g a day", protein, "protein-target", Modifier.weight(1f)) { protein = it }
            }

            Section("Accuracy test")
            Row(
                Modifier.fillMaxWidth().toggleable(value = testMode, role = Role.Switch, onValueChange = { testMode = it; vm.saveSettings(current()) }).testTag("test-mode"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Test mode")
                    Text("Adds a weighed-grams box to every item and asks the AI twice (with and without your hint).", color = Quiet, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = testMode, onCheckedChange = null)
            }
            AccuracyCard(Accuracy.report(data.meals))

            Section("Add a food from its label")
            LabelFoodForm(vm)

            Button(
                onClick = { vm.saveSettings(current()); vm.message = "Saved." },
                modifier = Modifier.fillMaxWidth().testTag("save-settings"), colors = ButtonDefaults.buttonColors(containerColor = Leaf),
            ) { Text("Save") }
            Text("Food list ${vm.db.version}: ${vm.db.foods.size} foods. Nutrients come from INDB, UK CoFID and USDA rows; see the README.", color = Quiet, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.padding(bottom = 32.dp))
        }
    }
}

@Composable
private fun Section(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun NumberField(label: String, value: String, tag: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(7)) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun AccuracyCard(r: Accuracy.Report) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().testTag("accuracy")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${r.testMeals} of ${Accuracy.TARGET_MEALS} weighed meals", fontWeight = FontWeight.SemiBold)
            if (r.testMeals == 0) {
                Text("Turn on test mode, weigh each item, and type the grams on the draft card. The report fills in here.", color = Quiet, style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Text("Foods named right in ${r.identified} of ${r.testMeals} meals (pass: 90%)", style = MaterialTheme.typography.bodyMedium)
            Row { Text("Median error", Modifier.weight(1.4f), color = Quiet); Text("item g", Modifier.weight(1f), color = Quiet); Text("meal kcal", Modifier.weight(1f), color = Quiet); Text("meal P", Modifier.weight(1f), color = Quiet); Text("day kcal", Modifier.weight(1f), color = Quiet) }
            HorizontalDivider()
            r.stats.forEach { m ->
                Row {
                    Text(m.method.label, Modifier.weight(1.4f), fontWeight = if (m.method == Accuracy.Method.CONFIRMED) FontWeight.SemiBold else FontWeight.Normal)
                    Text(Accuracy.pct(m.itemMedian), Modifier.weight(1f))
                    Text(Accuracy.pct(m.mealKcalMedian), Modifier.weight(1f))
                    Text(Accuracy.pct(m.mealProteinMedian), Modifier.weight(1f))
                    Text(Accuracy.pct(m.dayKcalMedian), Modifier.weight(1f))
                }
            }
            Text(
                when {
                    r.testMeals < Accuracy.TARGET_MEALS -> "Pass line: meal kcal and protein 20% or less, day 15% or less. ${Accuracy.TARGET_MEALS - r.testMeals} meals to go."
                    r.passes -> "PASS: the numbers after your fixes meet the line set in the spec."
                    else -> "FAIL: the numbers after your fixes miss the line set in the spec."
                },
                color = if (r.testMeals >= Accuracy.TARGET_MEALS && !r.passes) Chilli else Leaf, style = MaterialTheme.typography.bodyMedium,
            )
            if (r.worstFoods.isNotEmpty()) {
                Text("AI's worst foods (photo + hint):", color = Quiet, style = MaterialTheme.typography.bodySmall)
                r.worstFoods.forEach { f ->
                    Text("${f.name}: off by ${Accuracy.pct(f.medianAbs)} (${if (f.bias >= 0) "over" else "under"} by ${Accuracy.pct(kotlin.math.abs(f.bias))}), ${f.n} weighed", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun LabelFoodForm(vm: AppVm) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var fibre by remember { mutableStateOf("") }
    var piece by remember { mutableStateOf("") }
    var scoop by remember { mutableStateOf("") }
    Text("Per 100 g, as printed on the pack (whey, atta, milk...).", color = Quiet, style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("label-name"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("kcal", kcal, "label-kcal", Modifier.weight(1f)) { kcal = it }
        NumberField("Protein g", protein, "label-protein", Modifier.weight(1f)) { protein = it }
        NumberField("Carbs g", carbs, "label-carbs", Modifier.weight(1f)) { carbs = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Fat g", fat, "label-fat", Modifier.weight(1f)) { fat = it }
        NumberField("Fibre g", fibre, "label-fibre", Modifier.weight(1f)) { fibre = it }
        NumberField("1 piece g", piece, "label-piece", Modifier.weight(1f)) { piece = it }
        NumberField("1 scoop g", scoop, "label-scoop", Modifier.weight(1f)) { scoop = it }
    }
    OutlinedButton(
        onClick = {
            val k = kcal.toDoubleOrNull()
            if (name.isBlank() || k == null) { vm.message = "Name and kcal are needed."; return@OutlinedButton }
            val n = Nutrients(k, protein.toDoubleOrNull() ?: 0.0, carbs.toDoubleOrNull() ?: 0.0, fat.toDoubleOrNull() ?: 0.0, fibre.toDoubleOrNull() ?: 0.0)
            val atw = 4 * n.protein + 4 * n.carbs + 9 * n.fat + 2 * n.fibre
            if (atw > 0 && kotlin.math.abs(atw - k) / k > 0.2) vm.message = "Check the label: protein, carbs and fat add up to ${fmt0(atw)} kcal, not ${fmt0(k)}."
            val id = "my_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            vm.addLabelFood(Food.fromLabel(id, name.trim(), n, pieceG = piece.toDoubleOrNull(), scoopG = scoop.toDoubleOrNull()))
            name = ""; kcal = ""; protein = ""; carbs = ""; fat = ""; fibre = ""; piece = ""; scoop = ""
        },
        modifier = Modifier.testTag("label-add"),
    ) { Text("Add to my foods") }
    Spacer(Modifier.width(1.dp))
}
