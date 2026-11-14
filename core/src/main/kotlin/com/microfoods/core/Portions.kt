package com.microfoods.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Your kitchen: katori size, the weight of one of your chapatis or bhakris, and when your day ends. */
data class Portions(
    val katoriMl: Double = 150.0,
    /** food id -> grams for one piece, e.g. "chapati" -> 38.0, as weighed at home. */
    val pieceG: Map<String, Double> = emptyMap(),
    val dayEndHour: Int = 4,
) {
    fun toMap(): Map<String, Any?> = mapOf("katori_ml" to katoriMl, "piece_g" to pieceG, "day_end_hour" to dayEndHour)

    companion object {
        fun fromMap(m: JMap?): Portions {
            if (m == null) return Portions()
            return Portions(
                katoriMl = m.dbl("katori_ml")?.takeIf { it in 30.0..1000.0 } ?: 150.0,
                pieceG = (m.obj("piece_g") ?: emptyMap()).mapNotNull { (k, v) -> (v as? Number)?.toDouble()?.takeIf { it > 0 }?.let { k to it } }.toMap(),
                dayEndHour = m.int("day_end_hour")?.coerceIn(0, 8) ?: 4,
            )
        }
    }
}

object Units {
    /** Units the model may answer with. Anything else is treated as grams the model estimated. */
    val ALL = listOf("piece", "katori", "bowl", "plate", "glass", "cup", "scoop", "tbsp", "tsp", "g", "ml")

    private val SYNONYMS = mapOf(
        "pc" to "piece", "pcs" to "piece", "pieces" to "piece", "nos" to "piece", "no" to "piece", "slice" to "piece",
        "katoris" to "katori", "vati" to "katori", "wati" to "katori", "bowls" to "bowl", "plates" to "plate",
        "glasses" to "glass", "cups" to "cup", "scoops" to "scoop", "gram" to "g", "grams" to "g", "gm" to "g",
        "tablespoon" to "tbsp", "teaspoon" to "tsp", "chamcha" to "tbsp",
    )

    fun normalize(u: String?): String {
        val s = (u ?: "").trim().lowercase()
        return SYNONYMS[s] ?: s
    }

    /**
     * Grams for [qty] [unit] of [food], using your kitchen's sizes. Returns null when the food has no weight for
     * that unit, so the caller can fall back to the model's own gram estimate and flag it.
     */
    fun grams(food: Food, qty: Double, unit: String, p: Portions): Double? {
        if (qty <= 0) return 0.0
        return when (val u = normalize(unit)) {
            "g" -> qty
            "ml" -> qty * (food.katoriGPerMl ?: 1.0)
            "katori" -> food.katoriGPerMl?.let { qty * p.katoriMl * it }
            "piece" -> (p.pieceG[food.id] ?: food.units["piece"])?.let { qty * it }
            else -> food.units[u]?.let { qty * it }
        }
    }
}

object Days {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    val SLOTS = listOf("Breakfast", "Brunch", "Lunch", "Snacks", "Dinner", "Late")

    /** The day a meal counts toward: anything before [dayEndHour] belongs to the previous date. */
    fun dayOf(epochMs: Long, dayEndHour: Int = 4, zone: ZoneId = IST): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).minusHours(dayEndHour.toLong()).toLocalDate()

    /** A first guess at the meal slot from the clock; you can always change it. */
    fun suggestSlot(t: LocalTime): String = when {
        t < LocalTime.of(4, 0) -> "Late"
        t < LocalTime.of(10, 30) -> "Breakfast"
        t < LocalTime.of(12, 0) -> "Brunch"
        t < LocalTime.of(16, 0) -> "Lunch"
        t < LocalTime.of(19, 30) -> "Snacks"
        t < LocalTime.of(23, 0) -> "Dinner"
        else -> "Late"
    }

    fun suggestSlot(epochMs: Long, zone: ZoneId = IST): String = suggestSlot(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime())

    val DAY_TYPES = listOf("Gym", "Rest", "Travel", "Outside food", "Festival")
}
