package com.microfoods.core

/** Nutrients for some amount of food. All numbers are grams except kcal. */
data class Nutrients(
    val kcal: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val fibre: Double = 0.0,
) {
    operator fun plus(o: Nutrients) = Nutrients(kcal + o.kcal, protein + o.protein, carbs + o.carbs, fat + o.fat, fibre + o.fibre)
    operator fun times(f: Double) = Nutrients(kcal * f, protein * f, carbs * f, fat * f, fibre * f)

    companion object {
        val ZERO = Nutrients()
        fun sum(list: Iterable<Nutrients>) = list.fold(ZERO) { a, b -> a + b }
    }
}

/**
 * One food with its nutrients per 100 g. [units] maps a unit ("piece", "bowl", "cup") to grams for one of it;
 * "katori" is worked out from your katori size times [katoriGPerMl]. [assumed] lists units whose weights are
 * estimates until calibration corrects them.
 */
data class Food(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val group: String = "veg",
    val source: String = "",
    val per100: Nutrients,
    val micros: Map<String, Double> = emptyMap(),
    val units: Map<String, Double> = emptyMap(),
    val katoriGPerMl: Double? = null,
    val assumed: List<String> = emptyList(),
    val custom: Boolean = false,
) {
    fun nutrientsFor(grams: Double): Nutrients = per100 * (grams / 100.0)

    /** Units the model and the draft card may offer for this food, most natural first. */
    fun unitChoices(): List<String> {
        val out = mutableListOf<String>()
        if ("piece" in units) out += "piece"
        if (katoriGPerMl != null) out += "katori"
        for (u in listOf("bowl", "plate", "glass", "cup", "scoop", "tbsp", "tsp")) if (u in units && u !in out) out += u
        out += "g"
        return out
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "name" to name, "aliases" to aliases, "group" to group, "source" to source,
        "kcal" to per100.kcal, "protein_g" to per100.protein, "carbs_g" to per100.carbs, "fat_g" to per100.fat,
        "fibre_g" to per100.fibre, "micros" to micros, "units" to units, "katori_g_per_ml" to katoriGPerMl,
        "assumed" to assumed, "custom" to custom,
    )

    companion object {
        fun fromMap(m: JMap): Food = Food(
            id = m.str("id") ?: error("food without id"),
            name = m.str("name") ?: error("food without name"),
            aliases = m.strs("aliases"),
            group = m.str("group") ?: "veg",
            source = m.str("source") ?: "",
            per100 = Nutrients(
                m.dbl("kcal") ?: 0.0, m.dbl("protein_g") ?: 0.0, m.dbl("carbs_g") ?: 0.0,
                m.dbl("fat_g") ?: 0.0, m.dbl("fibre_g") ?: 0.0,
            ),
            micros = (m.obj("micros") ?: emptyMap()).mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toDouble() } }.toMap(),
            units = (m.obj("units") ?: emptyMap()).mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toDouble() } }.toMap(),
            katoriGPerMl = m.dbl("katori_g_per_ml"),
            assumed = m.strs("assumed"),
            custom = m.bool("custom") ?: false,
        )

        /** A food you add from a nutrition label: values per 100 g, plus how much one piece or scoop weighs. */
        fun fromLabel(
            id: String, name: String, per100: Nutrients, pieceG: Double? = null, scoopG: Double? = null,
            katoriGPerMl: Double? = null, group: String = "veg",
        ): Food {
            val units = linkedMapOf<String, Double>()
            if (pieceG != null && pieceG > 0) units["piece"] = pieceG
            if (scoopG != null && scoopG > 0) units["scoop"] = scoopG
            return Food(id, name, emptyList(), group, "My label", per100, emptyMap(), units, katoriGPerMl, emptyList(), custom = true)
        }
    }
}

/** The food list on the phone: the shipped list plus foods you add. */
class FoodDb(val version: String, val katoriDefaultMl: Double, foods: List<Food>) {
    private val list = foods.toMutableList()
    val foods: List<Food> get() = list

    fun byId(id: String?): Food? = if (id == null) null else list.firstOrNull { it.id == id }

    fun add(food: Food) {
        list.removeAll { it.id == food.id }
        list += food
    }

    /** Ranked search over names and aliases: exact, then starts-with, then contains. */
    fun search(query: String, limit: Int = 20): List<Food> {
        val q = norm(query)
        if (q.isEmpty()) return list.sortedBy { it.name }.take(limit)
        fun score(f: Food): Int {
            val names = listOf(norm(f.name)) + f.aliases.map(::norm)
            return when {
                names.any { it == q } -> 0
                names.any { it.startsWith(q) } -> 1
                names.any { n -> n.split(' ').any { it.startsWith(q) } } -> 2
                names.any { it.contains(q) } -> 3
                else -> 9
            }
        }
        return list.map { it to score(it) }.filter { it.second < 9 }.sortedWith(compareBy({ it.second }, { it.first.name }))
            .take(limit).map { it.first }
    }

    /** What the model is allowed to choose from: ids, names, a few aliases and the units each food takes. */
    fun listForModel(): List<Map<String, Any?>> = list.map {
        linkedMapOf("id" to it.id, "name" to it.name, "aliases" to it.aliases.take(4), "units" to it.unitChoices())
    }

    fun toJson(): String = Json.write(mapOf("version" to version, "katori_default_ml" to katoriDefaultMl, "foods" to list.map { it.toMap() }))

    companion object {
        fun parse(json: String): FoodDb {
            val m = Json.parseObj(json)
            return FoodDb(m.str("version") ?: "?", m.dbl("katori_default_ml") ?: 150.0, m.objs("foods").map(Food::fromMap))
        }

        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
