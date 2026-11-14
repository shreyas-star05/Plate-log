package com.microfoods.core

import kotlin.math.abs

/**
 * The accuracy test: compares four ways of getting grams with what the kitchen scale said. The same food
 * values sit on both sides, so this measures portion error only (spec: "What this test cannot measure").
 */
object Accuracy {
    enum class Method(val label: String) {
        PHOTO("Photo only"),
        PHOTO_HINT("Photo + hint"),
        AI_COUNT("AI count x your sizes"),
        CONFIRMED("After your fixes"),
    }

    // Pass line, fixed in the spec before any results: medians as fractions, identification as meals.
    const val PASS_MEAL_KCAL = 0.20
    const val PASS_MEAL_PROTEIN = 0.20
    const val PASS_DAY_KCAL = 0.15
    const val PASS_ID_SHARE = 0.90
    const val TARGET_MEALS = 20

    data class MethodStats(
        val method: Method,
        val items: Int,
        val itemMedian: Double?,
        val meals: Int,
        val mealKcalMedian: Double?,
        val mealProteinMedian: Double?,
        val days: Int,
        val dayKcalMedian: Double?,
        /** Positive = this method over-estimates on average (median signed error of items). */
        val itemBias: Double?,
    ) {
        val passes: Boolean
            get() = mealKcalMedian != null && mealKcalMedian <= PASS_MEAL_KCAL &&
                mealProteinMedian != null && mealProteinMedian <= PASS_MEAL_PROTEIN &&
                (dayKcalMedian == null || dayKcalMedian <= PASS_DAY_KCAL)
    }

    data class FoodError(val foodId: String, val name: String, val n: Int, val medianAbs: Double, val bias: Double)

    data class Report(
        val testMeals: Int,
        val identified: Int,
        val stats: List<MethodStats>,
        val worstFoods: List<FoodError>,
    ) {
        val idShare: Double? get() = if (testMeals == 0) null else identified.toDouble() / testMeals
        val confirmed: MethodStats get() = stats.first { it.method == Method.CONFIRMED }
        val passes: Boolean get() = testMeals >= TARGET_MEALS && confirmed.passes && (idShare ?: 0.0) >= PASS_ID_SHARE
    }

    fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    fun estimate(it: LoggedItem, m: Method): Double? = when (m) {
        Method.PHOTO -> it.aiGramsNoHint
        Method.PHOTO_HINT -> it.aiGrams
        Method.AI_COUNT -> it.aiUnitGrams
        Method.CONFIRMED -> it.grams
    }

    private fun weighedItems(meal: Meal) = meal.items.filter { it.status == "active" && (it.weighedG ?: 0.0) > 0 }

    /** kcal and protein the method gives for a set of items, or null if any item lacks that estimate or food values. */
    private fun totals(items: List<LoggedItem>, m: Method): Pair<Double, Double>? {
        var k = 0.0; var p = 0.0
        for (it in items) {
            val e = estimate(it, m) ?: return null
            val per = it.per100 ?: return null
            k += per.kcal * e / 100; p += per.protein * e / 100
        }
        return k to p
    }

    private fun truth(items: List<LoggedItem>): Pair<Double, Double>? {
        var k = 0.0; var p = 0.0
        for (it in items) {
            val per = it.per100 ?: return null
            k += per.kcal * it.weighedG!! / 100; p += per.protein * it.weighedG / 100
        }
        return k to p
    }

    private fun rel(est: Double, truth: Double): Double? = if (truth <= 0) null else abs(est - truth) / truth

    fun report(meals: List<Meal>): Report {
        val test = meals.filter { m -> m.testMode && weighedItems(m).isNotEmpty() }
        val identified = test.count { m ->
            m.aiItemsDeleted == 0 && m.items.filter { it.status == "active" }.all { it.aiFoodId != null && it.aiFoodId == it.foodId }
        }
        val stats = Method.values().map { method ->
            val itemErr = mutableListOf<Double>(); val itemSigned = mutableListOf<Double>()
            val mealK = mutableListOf<Double>(); val mealP = mutableListOf<Double>()
            for (meal in test) {
                val w = weighedItems(meal)
                for (it in w) {
                    val e = estimate(it, method) ?: continue
                    itemErr += abs(e - it.weighedG!!) / it.weighedG
                    itemSigned += (e - it.weighedG) / it.weighedG
                }
                val est = totals(w, method) ?: continue
                val tr = truth(w) ?: continue
                rel(est.first, tr.first)?.let { mealK += it }
                rel(est.second, tr.second)?.let { mealP += it }
            }
            val dayK = mutableListOf<Double>()
            for ((_, dayMeals) in test.groupBy { it.day }) {
                val w = dayMeals.flatMap(::weighedItems)
                val est = totals(w, method) ?: continue
                val tr = truth(w) ?: continue
                rel(est.first, tr.first)?.let { dayK += it }
            }
            MethodStats(method, itemErr.size, median(itemErr), mealK.size, median(mealK), median(mealP), dayK.size, median(dayK), median(itemSigned))
        }
        val worst = test.flatMap(::weighedItems).filter { it.foodId != null && it.aiGrams != null }
            .groupBy { it.foodId!! }
            .map { (id, xs) ->
                val signed = xs.map { (it.aiGrams!! - it.weighedG!!) / it.weighedG }
                FoodError(id, xs.first().name, xs.size, median(signed.map(::abs))!!, median(signed)!!)
            }
            .sortedByDescending { it.medianAbs }.take(5)
        return Report(test.size, identified, stats, worst)
    }

    fun pct(x: Double?): String = if (x == null) "-" else "${Math.round(x * 100)}%"
}
