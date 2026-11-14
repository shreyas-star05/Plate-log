package com.microfoods.core

import java.util.UUID

private fun round1(d: Double) = Math.round(d * 10.0) / 10.0
private fun round2(d: Double) = Math.round(d * 100.0) / 100.0

/**
 * One line on the draft card. The ai* fields keep the model's first answer untouched, so calibration can
 * compare it with what you confirmed and what you weighed.
 */
data class DraftItem(
    val key: String = UUID.randomUUID().toString(),
    val foodId: String?,
    val seenAs: String,
    val qty: Double,
    val unit: String,
    val confidence: Double = 0.0,
    val addonFor: String? = null,
    val aiFoodId: String? = null,
    val aiQty: Double? = null,
    val aiUnit: String? = null,
    val aiGrams: Double? = null,
    val aiGramsNoHint: Double? = null,
    /** Grams you typed, which override count x unit. */
    val manualGrams: Double? = null,
    /** Test and calibration meals: what the kitchen scale said. */
    val weighedG: Double? = null,
) {
    /** Grams from count x unit with your portion sizes; falls back to the model's estimate when the unit has no weight. */
    fun grams(db: FoodDb, p: Portions): Double? {
        manualGrams?.let { return it }
        val food = db.byId(foodId) ?: return aiGrams
        return Units.grams(food, qty, unit, p) ?: aiGrams
    }

    fun gramsSource(db: FoodDb, p: Portions): String = when {
        manualGrams != null -> "typed"
        db.byId(foodId)?.let { Units.grams(it, qty, unit, p) } != null -> "units"
        aiGrams != null -> "model"
        else -> "none"
    }

    fun nutrients(db: FoodDb, p: Portions): Nutrients? {
        val food = db.byId(foodId) ?: return null
        val g = grams(db, p) ?: return null
        return food.nutrientsFor(g)
    }

    /** Grams the model's own count and unit give with your portion sizes (the "your units" method). */
    fun aiUnitGrams(db: FoodDb, p: Portions): Double? {
        val food = db.byId(aiFoodId ?: foodId) ?: return null
        val q = aiQty ?: return null
        val u = aiUnit ?: return null
        return Units.grams(food, q, u, p)
    }

    fun stepped(delta: Double): DraftItem {
        val step = if (Units.normalize(unit) in listOf("g", "ml")) 10.0 else 0.5
        return copy(qty = maxOf(0.0, qty + delta * step), manualGrams = null)
    }
}

/** A confirmed food item: what becomes one row in the Sheet. */
data class LoggedItem(
    val rowId: String,
    val itemId: String,
    val foodId: String?,
    val name: String,
    val source: String,
    val qty: Double,
    val unit: String,
    val aiFoodId: String?,
    val aiQty: Double?,
    val aiGrams: Double?,
    val aiGramsNoHint: Double?,
    val aiUnitGrams: Double?,
    val grams: Double,
    val gramsSource: String,
    val weighedG: Double?,
    val n: Nutrients,
    val per100: Nutrients?,
    val confidence: Double,
    val addonFor: String?,
    val status: String = "active",
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "row_id" to rowId, "item_id" to itemId, "food_id" to foodId, "name" to name, "source" to source,
        "qty" to qty, "unit" to unit, "ai_food_id" to aiFoodId, "ai_qty" to aiQty, "ai_grams" to aiGrams,
        "ai_grams_nohint" to aiGramsNoHint, "ai_unit_grams" to aiUnitGrams, "grams" to grams,
        "grams_source" to gramsSource, "weighed_g" to weighedG,
        "kcal" to n.kcal, "protein_g" to n.protein, "carbs_g" to n.carbs, "fat_g" to n.fat, "fibre_g" to n.fibre,
        "per100" to per100?.let { listOf(it.kcal, it.protein, it.carbs, it.fat, it.fibre) },
        "confidence" to confidence, "addon_for" to addonFor, "status" to status,
    )

    companion object {
        fun fromMap(m: JMap): LoggedItem {
            val p = m.list("per100").mapNotNull { (it as? Number)?.toDouble() }
            return LoggedItem(
                rowId = m.str("row_id")!!, itemId = m.str("item_id") ?: m.str("row_id")!!, foodId = m.str("food_id"),
                name = m.str("name") ?: "", source = m.str("source") ?: "", qty = m.dbl("qty") ?: 0.0,
                unit = m.str("unit") ?: "g", aiFoodId = m.str("ai_food_id"), aiQty = m.dbl("ai_qty"),
                aiGrams = m.dbl("ai_grams"), aiGramsNoHint = m.dbl("ai_grams_nohint"), aiUnitGrams = m.dbl("ai_unit_grams"),
                grams = m.dbl("grams") ?: 0.0, gramsSource = m.str("grams_source") ?: "units", weighedG = m.dbl("weighed_g"),
                n = Nutrients(m.dbl("kcal") ?: 0.0, m.dbl("protein_g") ?: 0.0, m.dbl("carbs_g") ?: 0.0, m.dbl("fat_g") ?: 0.0, m.dbl("fibre_g") ?: 0.0),
                per100 = if (p.size == 5) Nutrients(p[0], p[1], p[2], p[3], p[4]) else null,
                confidence = m.dbl("confidence") ?: 0.0, addonFor = m.str("addon_for"), status = m.str("status") ?: "active",
            )
        }
    }
}

data class Meal(
    val mealId: String,
    val loggedAt: Long,
    val day: String,
    val slot: String,
    val hint: String,
    val items: List<LoggedItem>,
    val photoFile: String? = null,
    val photoUrl: String? = null,
    val model: String = "",
    val testMode: Boolean = false,
    /** Items the model found that you deleted on the draft card (counts against identification). */
    val aiItemsDeleted: Int = 0,
    val synced: Boolean = false,
) {
    val total: Nutrients get() = Nutrients.sum(items.filter { it.status == "active" && it.foodId != null }.map { it.n })
    val unmatched: Int get() = items.count { it.foodId == null }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "meal_id" to mealId, "logged_at" to loggedAt, "day" to day, "slot" to slot, "hint" to hint,
        "items" to items.map { it.toMap() }, "photo_file" to photoFile, "photo_url" to photoUrl, "model" to model,
        "test_mode" to testMode, "ai_items_deleted" to aiItemsDeleted, "synced" to synced,
    )

    /** Rows for the Sheet's Log tab, keyed by column name (the script matches names, not positions). */
    fun sheetRows(dbVersion: String, appVersion: String, loggedAtText: String): List<Map<String, Any?>> = items.map { it ->
        linkedMapOf(
            "row_id" to it.rowId, "meal_id" to mealId, "item_id" to it.itemId, "logged_at" to loggedAtText,
            "day" to day, "slot" to slot, "item" to it.name, "food_id" to (it.foodId ?: "UNMATCHED"), "source" to it.source,
            "qty" to round2(it.qty), "unit" to it.unit, "ai_qty" to it.aiQty?.let(::round2), "ai_grams" to it.aiGrams?.let(::round1),
            "ai_grams_nohint" to it.aiGramsNoHint?.let(::round1), "ai_unit_grams" to it.aiUnitGrams?.let(::round1),
            "grams" to round1(it.grams), "grams_source" to it.gramsSource, "weighed_g" to it.weighedG?.let(::round1),
            "kcal" to round1(it.n.kcal), "protein_g" to round1(it.n.protein), "carbs_g" to round1(it.n.carbs),
            "fat_g" to round1(it.n.fat), "fibre_g" to round1(it.n.fibre), "addon_for" to it.addonFor,
            "confidence" to round2(it.confidence), "hint" to hint, "photo_url" to photoUrl, "status" to it.status,
            "model" to model, "db_version" to dbVersion, "app_version" to appVersion,
        )
    }

    companion object {
        fun fromMap(m: JMap) = Meal(
            mealId = m.str("meal_id")!!, loggedAt = m.lng("logged_at") ?: 0L, day = m.str("day") ?: "", slot = m.str("slot") ?: "",
            hint = m.str("hint") ?: "", items = m.objs("items").map(LoggedItem::fromMap), photoFile = m.str("photo_file"),
            photoUrl = m.str("photo_url"), model = m.str("model") ?: "", testMode = m.bool("test_mode") ?: false,
            aiItemsDeleted = m.int("ai_items_deleted") ?: 0, synced = m.bool("synced") ?: false,
        )

        /** Column order for the Log tab. The Apps Script creates the header row from this list. */
        val LOG_COLUMNS = listOf(
            "row_id", "meal_id", "item_id", "logged_at", "day", "slot", "item", "food_id", "source", "qty", "unit",
            "ai_qty", "ai_grams", "ai_grams_nohint", "ai_unit_grams", "grams", "grams_source", "weighed_g",
            "kcal", "protein_g", "carbs_g", "fat_g", "fibre_g", "addon_for", "confidence", "hint", "photo_url",
            "status", "model", "db_version", "app_version",
        )

        /** Turns the draft card into a confirmed meal. Items with zero grams are dropped. */
        fun confirm(
            draft: List<DraftItem>, db: FoodDb, p: Portions, loggedAt: Long, slot: String, hint: String,
            photoFile: String?, photoUrl: String?, model: String, testMode: Boolean, aiItemsDeleted: Int,
            mealId: String = UUID.randomUUID().toString(), newId: () -> String = { UUID.randomUUID().toString() },
        ): Meal {
            val items = draft.mapNotNull { d ->
                val g = d.grams(db, p) ?: return@mapNotNull null
                if (g <= 0) return@mapNotNull null
                val food = db.byId(d.foodId)
                LoggedItem(
                    rowId = newId(), itemId = d.key, foodId = food?.id, name = food?.name ?: d.seenAs,
                    source = food?.source ?: "", qty = d.qty, unit = Units.normalize(d.unit), aiFoodId = d.aiFoodId,
                    aiQty = d.aiQty, aiGrams = d.aiGrams, aiGramsNoHint = d.aiGramsNoHint, aiUnitGrams = d.aiUnitGrams(db, p),
                    grams = g, gramsSource = d.gramsSource(db, p), weighedG = d.weighedG,
                    n = food?.nutrientsFor(g) ?: Nutrients.ZERO, per100 = food?.per100,
                    confidence = d.confidence, addonFor = d.addonFor,
                )
            }
            return Meal(
                mealId, loggedAt, Days.dayOf(loggedAt, p.dayEndHour).toString(), slot, hint.trim(), items,
                photoFile, photoUrl, model, testMode, aiItemsDeleted,
            )
        }
    }
}

/** What the analyze call returned, turned into draft lines. */
data class Analysis(val model: String, val items: List<DraftItem>, val note: String, val photoUrl: String?) {
    companion object {
        fun parse(json: String, db: FoodDb): Analysis {
            val m = Json.parseObj(json)
            if (m.bool("ok") == false) error(m.str("error") ?: "analysis failed")
            val items = m.objs("items").mapNotNull { it ->
                val fid = it.str("food_id")?.takeIf { id -> db.byId(id) != null }
                val qty = it.dbl("qty")?.takeIf { q -> q > 0 } ?: return@mapNotNull null
                val unit = Units.normalize(it.str("unit")).ifEmpty { "g" }
                DraftItem(
                    foodId = fid, seenAs = it.str("seen_as")?.trim().orEmpty().ifEmpty { db.byId(fid)?.name ?: "Unknown item" },
                    qty = qty, unit = unit, confidence = (it.dbl("confidence") ?: 0.0).coerceIn(0.0, 1.0),
                    addonFor = it.str("addon_for")?.trim()?.ifEmpty { null },
                    aiFoodId = fid, aiQty = qty, aiUnit = unit, aiGrams = it.dbl("est_grams")?.takeIf { g -> g > 0 },
                )
            }
            return Analysis(m.str("model") ?: "", items, m.str("note") ?: "", m.str("photo_url"))
        }

        /** Test mode: adds the photo-only estimate to each item, matched by food (summed if the food appears twice). */
        fun withNoHint(withHint: Analysis, noHint: Analysis): Analysis {
            val byFood = noHint.items.filter { it.foodId != null && it.aiGrams != null }
                .groupBy { it.foodId }.mapValues { (_, v) -> v.sumOf { it.aiGrams!! } }
            val counts = withHint.items.groupingBy { it.foodId }.eachCount()
            return withHint.copy(items = withHint.items.map { d ->
                val total = byFood[d.foodId] ?: return@map d
                d.copy(aiGramsNoHint = total / (counts[d.foodId] ?: 1))
            })
        }
    }
}
