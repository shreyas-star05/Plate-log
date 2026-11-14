package com.microfoods.core

/** Your settings. Targets are yours (2,300 kcal, 175 g protein) until you change them. */
data class Settings(
    val endpoint: String = "",
    val token: String = "",
    val portions: Portions = Portions(),
    val testMode: Boolean = false,
    val kcalTarget: Double = 2300.0,
    val proteinTarget: Double = 175.0,
) {
    val connected: Boolean get() = endpoint.startsWith("https://") && token.length >= 16
    val demo: Boolean get() = endpoint == "demo"

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "endpoint" to endpoint, "token" to token, "portions" to portions.toMap(), "test_mode" to testMode,
        "kcal_target" to kcalTarget, "protein_target" to proteinTarget,
    )

    companion object {
        fun fromMap(m: JMap?): Settings {
            if (m == null) return Settings()
            return Settings(
                endpoint = m.str("endpoint") ?: "", token = m.str("token") ?: "", portions = Portions.fromMap(m.obj("portions")),
                testMode = m.bool("test_mode") ?: false, kcalTarget = m.dbl("kcal_target") ?: 2300.0,
                proteinTarget = m.dbl("protein_target") ?: 175.0,
            )
        }
    }
}

/** A day's type (Gym, Rest...) and optional weight, waiting to reach the Days tab. */
data class DayNote(val day: String, val dayType: String?, val weightKg: Double?, val synced: Boolean = false) {
    fun toMap(): Map<String, Any?> = linkedMapOf("day" to day, "day_type" to dayType, "weight_kg" to weightKg, "synced" to synced)

    companion object {
        fun fromMap(m: JMap) = DayNote(m.str("day")!!, m.str("day_type"), m.dbl("weight_kg"), m.bool("synced") ?: false)
    }
}

/** Everything the app keeps on the phone, as one JSON document. Meals are saved here before any network call. */
data class AppData(
    val settings: Settings = Settings(),
    val meals: List<Meal> = emptyList(),
    val days: List<DayNote> = emptyList(),
    val customFoods: List<Food> = emptyList(),
) {
    val pendingMeals: List<Meal> get() = meals.filter { !it.synced }
    val pendingDays: List<DayNote> get() = days.filter { !it.synced }

    fun addMeal(m: Meal) = copy(meals = meals.filter { it.mealId != m.mealId } + m)

    /** Marks meals as sent. Safe to call twice: the Sheet also ignores row ids it already has. */
    fun markSynced(mealIds: Collection<String>, photoUrls: Map<String, String> = emptyMap()) = copy(meals = meals.map {
        if (it.mealId in mealIds) it.copy(synced = true, photoUrl = photoUrls[it.mealId] ?: it.photoUrl) else it
    })

    fun markDaysSynced(dayKeys: Collection<String>) = copy(days = days.map { if (it.day in dayKeys) it.copy(synced = true) else it })

    fun setDay(day: String, dayType: String? = dayNote(day)?.dayType, weightKg: Double? = dayNote(day)?.weightKg) =
        copy(days = days.filter { it.day != day } + DayNote(day, dayType, weightKg, synced = false))

    fun dayNote(day: String): DayNote? = days.firstOrNull { it.day == day }

    fun mealsOn(day: String) = meals.filter { it.day == day }.sortedBy { it.loggedAt }

    fun totalOn(day: String): Nutrients = Nutrients.sum(mealsOn(day).map { it.total })

    fun toJson(): String = Json.write(linkedMapOf(
        "v" to 1, "settings" to settings.toMap(), "meals" to meals.map { it.toMap() },
        "days" to days.map { it.toMap() }, "custom_foods" to customFoods.map { it.toMap() },
    ))

    companion object {
        fun parse(json: String?): AppData {
            if (json.isNullOrBlank()) return AppData()
            val m = Json.parseObj(json)
            return AppData(
                Settings.fromMap(m.obj("settings")), m.objs("meals").map(Meal::fromMap),
                m.objs("days").map(DayNote::fromMap), m.objs("custom_foods").map(Food::fromMap),
            )
        }
    }
}
