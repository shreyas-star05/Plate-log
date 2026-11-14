package com.microfoods.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.microfoods.core.Analysis
import com.microfoods.core.AppData
import com.microfoods.core.Days
import com.microfoods.core.DraftItem
import com.microfoods.core.Food
import com.microfoods.core.Meal
import com.microfoods.core.Settings
import com.microfoods.core.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class Screen { TODAY, CAPTURE, DRAFT, SETTINGS }

/** The meal being logged, from the first photo to Confirm. */
data class Draft(
    val mealId: String = UUID.randomUUID().toString(),
    val at: Long = System.currentTimeMillis(),
    val slot: String = Days.suggestSlot(System.currentTimeMillis()),
    val photoFile: String? = null,
    val hint: String = "",
    val items: List<DraftItem> = emptyList(),
    val model: String = "",
    val photoUrl: String? = null,
    val note: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val aiItemsDeleted: Int = 0,
    val analysed: Boolean = false,
)

class AppVm(app: Application) : AndroidViewModel(app) {
    init { Repo.init(app) }

    val data: StateFlow<AppData> = Repo.state
    val db get() = Repo.db

    var screen by mutableStateOf(Screen.TODAY)
    var draft by mutableStateOf<Draft?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var syncing by mutableStateOf(false)
        private set

    private val settings: Settings get() = data.value.settings
    private val ctx get() = getApplication<Application>()

    // ---------------- capture and analyse

    fun startMeal() {
        draft = Draft()
        screen = Screen.CAPTURE
    }

    fun setPhoto(file: File?) { draft = draft?.copy(photoFile = file?.absolutePath) }

    fun setHint(h: String) { draft = draft?.copy(hint = h.take(300)) }

    fun setSlot(s: String) { draft = draft?.copy(slot = s) }

    /** Skip the AI: an empty draft card where you add foods yourself. */
    fun manualEntry() {
        draft = (draft ?: Draft()).copy(analysed = true, error = null)
        screen = Screen.DRAFT
    }

    fun analyse() {
        val d = draft ?: return
        if (d.photoFile == null && d.hint.isBlank()) { message = "Take a photo or type what you ate."; return }
        draft = d.copy(loading = true, error = null)
        screen = Screen.DRAFT
        val s = settings
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    d.photoFile?.let(Photos::shrink)
                    val b64 = Photos.base64(d.photoFile)
                    val base = mapOf(
                        "action" to "analyze", "foods" to db.listForModel(), "hint" to d.hint, "meal_id" to d.mealId,
                        "image_b64" to b64, "mime" to "image/jpeg",
                    )
                    val backend = Backend(s)
                    val main = Analysis.parse(backend.call(base + mapOf("with_hint" to true, "save_photo" to (b64 != null))), db)
                    if (s.testMode && b64 != null && d.hint.isNotBlank()) {
                        // accuracy test: the same photo without the hint, to see what the hint is worth
                        val bare = runCatching { Analysis.parse(backend.call(base + mapOf("with_hint" to false, "save_photo" to false)), db) }.getOrNull()
                        if (bare != null) Analysis.withNoHint(main, bare) else main
                    } else main
                }
            }
            val cur = draft ?: return@launch
            draft = result.fold(
                onSuccess = { a -> cur.copy(loading = false, analysed = true, items = a.items, model = a.model, photoUrl = a.photoUrl, note = a.note) },
                onFailure = { e -> cur.copy(loading = false, analysed = true, error = e.message ?: "Analysis failed") },
            )
        }
    }

    // ---------------- draft card edits

    fun updateItem(key: String, change: (DraftItem) -> DraftItem) {
        draft = draft?.let { d -> d.copy(items = d.items.map { if (it.key == key) change(it) else it }) }
    }

    fun removeItem(key: String) {
        draft = draft?.let { d ->
            val gone = d.items.firstOrNull { it.key == key }
            d.copy(items = d.items.filter { it.key != key }, aiItemsDeleted = d.aiItemsDeleted + if (gone?.aiFoodId != null || gone?.aiGrams != null) 1 else 0)
        }
    }

    /** Puts [food] on the line [key], or adds a new line when key is null. Keeps the model's first answer. */
    fun pickFood(key: String?, food: Food) {
        val unit = food.unitChoices().first()
        if (key == null) {
            draft = draft?.let { d -> d.copy(items = d.items + DraftItem(foodId = food.id, seenAs = food.name, qty = if (unit == "g") 100.0 else 1.0, unit = unit, confidence = 1.0)) }
        } else updateItem(key) { item ->
            val keepUnit = item.unit in food.unitChoices()
            item.copy(foodId = food.id, unit = if (keepUnit) item.unit else unit, qty = if (keepUnit) item.qty else if (unit == "g") (item.aiGrams ?: 100.0) else 1.0, manualGrams = null)
        }
    }

    fun gramsOf(item: DraftItem): Double? = item.grams(db, settings.portions)

    fun confirm() {
        val d = draft ?: return
        if (d.items.isEmpty()) { message = "Add at least one food."; return }
        val meal = Meal.confirm(
            d.items, db, settings.portions, d.at, d.slot, d.hint, d.photoFile, d.photoUrl, d.model,
            settings.testMode, d.aiItemsDeleted, mealId = d.mealId,
        )
        Repo.update { it.addMeal(meal) }
        draft = null
        screen = Screen.TODAY
        message = if (meal.unmatched > 0) "Saved. ${meal.unmatched} item(s) have no food match and count 0 kcal." else "Saved."
        SyncWorker.enqueue(ctx)
        syncNow(quiet = true)
    }

    fun cancelDraft() {
        draft?.photoFile?.let { p -> if (draft?.analysed != true) File(p).delete() }
        draft = null
        screen = Screen.TODAY
    }

    // ---------------- sync, settings, days

    fun syncNow(quiet: Boolean = false) {
        if (syncing) return
        syncing = true
        viewModelScope.launch {
            val r = Sync.run(BuildConfig.VERSION_NAME)
            syncing = false
            if (!quiet || r.isFailure) message = r.fold({ if (it == 0) "Everything is in the Sheet." else "Sent $it meal(s) to the Sheet." }, { "Not sent yet: ${it.message}" })
            if (r.isFailure) SyncWorker.enqueue(ctx)
        }
    }

    fun saveSettings(s: Settings) { Repo.update { it.copy(settings = s) } }

    fun ping() {
        val s = settings
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Backend(s).callObj(mapOf("action" to "ping")) } }
            message = r.fold({ m ->
                if (m["hasKey"] == false) "Connected to '${m["sheet"]}', but GEMINI_API_KEY is not set in the script." else "Connected to '${m["sheet"]}' (${m["model"]})."
            }, { "Could not connect: ${it.message}" })
        }
    }

    fun setDayType(day: String, type: String?) {
        Repo.update { it.setDay(day, dayType = type) }
        SyncWorker.enqueue(ctx)
    }

    fun addLabelFood(food: Food) {
        Repo.addFood(food)
        message = "Added ${food.name}."
    }

    fun unitGrams(item: DraftItem, unit: String): Double? = db.byId(item.foodId)?.let { Units.grams(it, 1.0, unit, settings.portions) }
}
