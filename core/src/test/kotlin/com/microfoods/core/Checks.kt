package com.microfoods.core

import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Every check on the core, as plain functions so they run the same under JUnit in CI and from a plain main.
 * Expected numbers are worked out by hand from data/foods.json values, not copied from the code's output.
 */
object Checks {
    class Failure(val name: String, val why: String)

    private val failures = mutableListOf<Failure>()
    private var count = 0
    private var current = ""

    private fun near(a: Double?, b: Double, tol: Double = 0.05, what: String = "") {
        if (a == null || abs(a - b) > tol) throw AssertionError("$what expected $b, got $a")
    }

    private fun yes(c: Boolean, what: String) { if (!c) throw AssertionError(what) }

    private fun eq(a: Any?, b: Any?, what: String = "") { if (a != b) throw AssertionError("$what expected <$b>, got <$a>") }

    private fun check(name: String, body: () -> Unit) {
        count++; current = name
        try { body() } catch (t: Throwable) { failures += Failure(name, t.message ?: t.toString()) }
    }

    private val db: FoodDb by lazy { FoodDb.parse(File("data/foods.json").readText()) }

    private fun ist(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, Days.IST).toInstant().toEpochMilli()

    fun runAll(): Pair<Int, List<Failure>> {
        failures.clear(); count = 0

        // ---------- the shipped food list ----------
        check("food list loads with unique ids") {
            yes(db.foods.size >= 35, "only ${db.foods.size} foods")
            eq(db.foods.map { it.id }.toSet().size, db.foods.size, "duplicate ids")
        }
        check("every food's kcal matches its own protein, carbs, fat and fibre within 15%") {
            for (f in db.foods) {
                val p = f.per100
                val atw = 4 * p.protein + 4 * p.carbs + 9 * p.fat + 2 * p.fibre
                yes(p.kcal > 0 && abs(atw - p.kcal) / p.kcal <= 0.15, "${f.id}: ${p.kcal} kcal vs $atw from macros")
            }
        }
        check("no dish carries frying oil as eaten (fat above 50 g per 100 g), except fats themselves") {
            for (f in db.foods.filter { it.group != "addon" && it.id != "peanuts" }) yes(f.per100.fat <= 50, "${f.id}: fat ${f.per100.fat}")
        }
        check("every food can be portioned and every unit weight is sensible") {
            for (f in db.foods) {
                yes(f.units.isNotEmpty(), "${f.id} has no units")
                for ((u, g) in f.units) yes(g > 0 && g < 700, "${f.id} $u = $g g")
            }
        }
        check("egg comes from USDA, not INDB's 45 kcal boiled egg") {
            val e = db.byId("egg_boiled")!!
            near(e.per100.kcal, 155.0, what = "kcal"); near(e.per100.protein, 12.58, what = "protein")
            yes(e.source.contains("USDA"), "source ${e.source}")
        }
        check("chapati is INDB ASC096 at 202.3 kcal/100 g, 36 g a piece") {
            val c = db.byId("chapati")!!
            near(c.per100.kcal, 202.311, 0.01); near(c.units["piece"], 36.0); eq(c.source, "INDB ASC096")
        }

        // ---------- search ----------
        check("Marathi and Hindi names find the right food") {
            eq(db.search("bhendi").first().id, "bhindi", "bhendi")
            eq(db.search("varan").first().id, "dal", "varan")
            eq(db.search("poli").first().id, "chapati", "poli")
            eq(db.search("matki").first().id, "matki", "matki")
            eq(db.search("taak").first().id, "chaas", "taak")
            eq(db.search("ghee").first().id, "ghee", "ghee")
        }
        check("search ignores case and punctuation, and an empty query lists foods") {
            eq(db.search("  BHENDI-bhaji ").first().id, "bhindi")
            yes(db.search("").size == 20, "empty query should list 20")
            yes(db.search("zzzz").isEmpty(), "nonsense should find nothing")
        }
        check("the list sent to the model has ids, names and allowed units") {
            val l = db.listForModel()
            eq(l.size, db.foods.size)
            val chap = l.first { it["id"] == "chapati" }
            eq(chap["units"], listOf("piece", "g"))
            @Suppress("UNCHECKED_CAST") val dalUnits = l.first { it["id"] == "dal" }["units"] as List<String>
            yes("katori" in dalUnits && "bowl" in dalUnits, "dal units $dalUnits")
        }

        // ---------- portions ----------
        val p0 = Portions()
        check("3 chapati at the default 36 g = 108 g = 218.5 kcal") {
            val c = db.byId("chapati")!!
            near(Units.grams(c, 3.0, "piece", p0), 108.0)
            near(c.nutrientsFor(108.0).kcal, 218.5, 0.1)
        }
        check("your own chapati weight wins over the database's") {
            val c = db.byId("chapati")!!
            near(Units.grams(c, 3.0, "pcs", Portions(pieceG = mapOf("chapati" to 40.0))), 120.0)
        }
        check("1 katori dal = your katori ml x 1.0 g/ml") {
            val d = db.byId("dal")!!
            near(Units.grams(d, 1.0, "katori", p0), 150.0)
            near(Units.grams(d, 1.0, "vati", Portions(katoriMl = 200.0)), 200.0)
            near(d.nutrientsFor(150.0).kcal, 92.89, 0.05)
        }
        check("rice katori uses 0.8 g/ml: 150 ml katori = 120 g") { near(Units.grams(db.byId("rice")!!, 1.0, "katori", p0), 120.0) }
        check("2 tsp ghee = 9.1 g = 79.9 kcal") {
            val g = db.byId("ghee")!!
            val grams = Units.grams(g, 2.0, "tsp", p0)!!
            near(grams, 9.1, 0.01); near(g.nutrientsFor(grams).kcal, 79.9, 0.1)
        }
        check("grams and ml always work; a unit the food has no weight for returns null") {
            val c = db.byId("chapati")!!
            near(Units.grams(c, 75.0, "g", p0), 75.0)
            yes(Units.grams(c, 1.0, "katori", p0) == null, "chapati has no katori")
            yes(Units.grams(c, 1.0, "bowl", p0) == null, "chapati has no bowl")
            near(Units.grams(c, 0.0, "piece", p0), 0.0)
        }
        check("portion settings are clamped to sane values") {
            val p = Portions.fromMap(mapOf("katori_ml" to 5000.0, "day_end_hour" to 12, "piece_g" to mapOf("chapati" to 41.0, "bad" to -3.0)))
            near(p.katoriMl, 150.0); eq(p.dayEndHour, 8); eq(p.pieceG, mapOf("chapati" to 41.0))
        }

        // ---------- day and slot ----------
        check("a 2:30 AM snack counts toward the previous day; 4:00 AM starts the new one") {
            eq(Days.dayOf(ist(2026, 11, 14, 2, 30)), LocalDate.of(2026, 11, 13))
            eq(Days.dayOf(ist(2026, 11, 14, 4, 0)), LocalDate.of(2026, 11, 14))
            eq(Days.dayOf(ist(2026, 11, 14, 23, 59)), LocalDate.of(2026, 11, 14))
            eq(Days.dayOf(ist(2026, 11, 14, 2, 30), dayEndHour = 0), LocalDate.of(2026, 11, 14))
        }
        check("slots follow the clock, including brunch and late") {
            eq(Days.suggestSlot(LocalTime.of(9, 40)), "Breakfast")
            eq(Days.suggestSlot(LocalTime.of(11, 15)), "Brunch")
            eq(Days.suggestSlot(LocalTime.of(14, 10)), "Lunch")
            eq(Days.suggestSlot(LocalTime.of(17, 30)), "Snacks")
            eq(Days.suggestSlot(LocalTime.of(21, 0)), "Dinner")
            eq(Days.suggestSlot(LocalTime.of(23, 30)), "Late")
            eq(Days.suggestSlot(LocalTime.of(1, 0)), "Late")
        }

        // ---------- analysis ----------
        val sample = """{"ok":true,"model":"gemini-3.8-flash","note":"home lunch","photo_url":"https://drive.google.com/x",
            "items":[
             {"food_id":"chapati","seen_as":"Chapati","qty":3,"unit":"pcs","est_grams":105,"confidence":0.9},
             {"food_id":"bhindi","seen_as":"Bhendi bhaji","qty":1,"unit":"katori","est_grams":130,"confidence":0.7},
             {"food_id":"ghee","seen_as":"Ghee on chapati","qty":3,"unit":"tsp","est_grams":13,"confidence":0.5,"addon_for":"Chapati"},
             {"food_id":"UNKNOWN","seen_as":"Pickle","qty":1,"unit":"tsp","est_grams":8,"confidence":0.4},
             {"food_id":"dal","seen_as":"Dal","qty":0,"unit":"katori","est_grams":0,"confidence":0.2}
            ]}"""
        check("analysis: known foods match, unknown stays unmatched, zero quantities are dropped") {
            val a = Analysis.parse(sample, db)
            eq(a.model, "gemini-3.8-flash"); eq(a.items.size, 4)
            eq(a.items[0].foodId, "chapati"); eq(a.items[0].unit, "piece"); near(a.items[0].aiGrams, 105.0)
            eq(a.items[2].addonFor, "Chapati")
            eq(a.items[3].foodId, null); eq(a.items[3].seenAs, "Pickle")
            eq(a.photoUrl, "https://drive.google.com/x")
        }
        check("analysis: an error reply raises the script's message") {
            val r = runCatching { Analysis.parse("""{"ok":false,"error":"quota exhausted"}""", db) }
            yes(r.isFailure && r.exceptionOrNull()!!.message == "quota exhausted", "got ${r.exceptionOrNull()?.message}")
        }
        check("analysis: confidence is clamped to 0..1 and a food id not on the list is unmatched") {
            val a = Analysis.parse("""{"items":[{"food_id":"pizza","seen_as":"Pizza","qty":1,"unit":"piece","confidence":7}]}""", db)
            eq(a.items[0].foodId, null); near(a.items[0].confidence, 1.0)
        }
        check("test mode: the photo-only estimate is attached per food") {
            val withHint = Analysis.parse(sample, db)
            val noHint = Analysis.parse("""{"items":[{"food_id":"chapati","seen_as":"Roti","qty":2,"unit":"piece","est_grams":70},
                {"food_id":"bhindi","seen_as":"Okra","qty":1,"unit":"bowl","est_grams":160}]}""", db)
            val merged = Analysis.withNoHint(withHint, noHint)
            near(merged.items[0].aiGramsNoHint, 70.0); near(merged.items[1].aiGramsNoHint, 160.0)
            eq(merged.items[2].aiGramsNoHint, null)
        }

        // ---------- draft card and confirm ----------
        check("draft: count x unit gives grams; typed grams override; unsupported unit falls back to the model") {
            val d = DraftItem(foodId = "chapati", seenAs = "Chapati", qty = 3.0, unit = "piece", aiGrams = 105.0)
            near(d.grams(db, p0), 108.0); eq(d.gramsSource(db, p0), "units")
            near(d.copy(manualGrams = 99.0).grams(db, p0), 99.0); eq(d.copy(manualGrams = 99.0).gramsSource(db, p0), "typed")
            near(d.copy(unit = "bowl").grams(db, p0), 105.0); eq(d.copy(unit = "bowl").gramsSource(db, p0), "model")
        }
        check("draft: the stepper moves pieces by half and grams by 10, never below zero, and clears typed grams") {
            val d = DraftItem(foodId = "chapati", seenAs = "Chapati", qty = 0.5, unit = "piece", manualGrams = 50.0)
            near(d.stepped(+1.0).qty, 1.0); eq(d.stepped(+1.0).manualGrams, null)
            near(d.stepped(-1.0).stepped(-1.0).qty, 0.0)
            near(d.copy(unit = "g", qty = 100.0).stepped(+1.0).qty, 110.0)
        }
        check("confirm: grams, nutrients, day and the AI's own estimates all land on the item") {
            val a = Analysis.parse(sample, db)
            val at = ist(2026, 11, 14, 14, 10)
            var n = 0
            val meal = Meal.confirm(a.items, db, p0, at, "Lunch", " ghee roti ", null, a.photoUrl, a.model, false, 0,
                mealId = "m1", newId = { "r${++n}" })
            eq(meal.day, "2026-11-14"); eq(meal.hint, "ghee roti"); eq(meal.items.size, 4)
            val chap = meal.items[0]
            near(chap.grams, 108.0); near(chap.n.kcal, 218.5, 0.1); near(chap.aiUnitGrams, 108.0); eq(chap.rowId, "r1")
            val bh = meal.items[1]
            near(bh.grams, 105.0, what = "bhindi katori 150 ml x 0.7")
            near(meal.items[2].grams, 13.65, 0.01, "ghee 3 tsp")
            eq(meal.items[3].foodId, null); near(meal.items[3].grams, 8.0); eq(meal.unmatched, 1)
            val expected = 218.5 + 110.808 * 1.05 + 878 * 0.1365
            near(meal.total.kcal, expected, 0.2, "meal kcal, unmatched excluded")
        }
        check("confirm: a zero-gram line is dropped") {
            val d = listOf(DraftItem(foodId = "dal", seenAs = "Dal", qty = 0.0, unit = "katori"))
            eq(Meal.confirm(d, db, p0, 0L, "Lunch", "", null, null, "", false, 0).items.size, 0)
        }
        check("sheet rows carry exactly the Log columns, in order") {
            val a = Analysis.parse(sample, db)
            val meal = Meal.confirm(a.items, db, p0, ist(2026, 11, 14, 14, 10), "Lunch", "", null, null, "m", false, 0)
            for (r in meal.sheetRows("2026.11a", "0.1.0", "2026-11-14 14:10")) eq(r.keys.toList(), Meal.LOG_COLUMNS)
            eq(meal.sheetRows("v", "a", "t")[3]["food_id"], "UNMATCHED")
        }

        // ---------- accuracy ----------
        fun item(food: String, grams: Double, weighed: Double, ai: Double?, noHint: Double? = null, aiFood: String? = food): LoggedItem {
            val f = db.byId(food)!!
            return LoggedItem("r$food$grams", "i$food$grams", food, f.name, f.source, 1.0, "g", aiFood, 1.0, ai, noHint, if (ai == null) null else grams,
                grams, "units", weighed, f.nutrientsFor(grams), f.per100, 0.8, null)
        }
        check("median: odd, even and empty") {
            near(Accuracy.median(listOf(3.0, 1.0, 2.0)), 2.0); near(Accuracy.median(listOf(4.0, 1.0, 2.0, 3.0)), 2.5)
            eq(Accuracy.median(emptyList()), null)
        }
        check("accuracy: two-item meal error is worked out on kcal, not grams") {
            val m = Meal("m1", 0L, "2026-11-14", "Lunch", "", listOf(item("chapati", 108.0, 100.0, 90.0), item("dal", 150.0, 200.0, 180.0)), testMode = true)
            val r = Accuracy.report(listOf(m))
            val c = r.confirmed
            // est 2.02311*108 + 0.61928*150 = 311.39; truth 202.311 + 123.856 = 326.17; |diff| / truth = 4.53%
            near(c.mealKcalMedian, 0.0453, 0.0005)
            // items: chapati +8%, dal -25% -> median of |8%|, |25%| = 16.5%
            near(c.itemMedian, 0.165, 0.0005); eq(c.items, 2); eq(r.testMeals, 1); eq(r.identified, 1)
            yes(!r.passes, "one meal must not pass the 20-meal test")
        }
        check("accuracy: the photo-only method is skipped on meals where it never ran") {
            val m = Meal("m1", 0L, "d", "Lunch", "", listOf(item("chapati", 108.0, 100.0, 90.0), item("dal", 150.0, 200.0, 180.0)), testMode = true)
            val photo = Accuracy.report(listOf(m)).stats.first { it.method == Accuracy.Method.PHOTO }
            eq(photo.meals, 0); eq(photo.items, 0)
        }
        check("accuracy: a food the AI missed (you added it) counts as 0 g for the AI, not as a skipped item") {
            val chaas = item("chaas", 170.0, 170.0, null, aiFood = null)
            val m = Meal("m1", 0L, "d", "Lunch", "", listOf(item("chapati", 100.0, 100.0, 100.0, noHint = 80.0), chaas), testMode = true)
            val r = Accuracy.report(listOf(m))
            val hint = r.stats.first { it.method == Accuracy.Method.PHOTO_HINT }
            // est 2.02311 x 100 = 202.31; truth 202.31 + 0.18845 x 170 = 234.35; error 32.04 / 234.35 = 13.67%
            near(hint.mealKcalMedian, 0.1367, 0.0005); eq(hint.items, 2)
            val photo = r.stats.first { it.method == Accuracy.Method.PHOTO }
            // est 2.02311 x 80 = 161.85; error (234.35 - 161.85) / 234.35 = 30.94%
            near(photo.mealKcalMedian, 0.3094, 0.0005)
            near(r.confirmed.mealKcalMedian, 0.0)
        }
        check("accuracy: a swapped food or a deleted AI item counts as not identified") {
            val ok = Meal("a", 0L, "d", "Lunch", "", listOf(item("chapati", 100.0, 100.0, 100.0)), testMode = true)
            val swapped = ok.copy(mealId = "b", items = listOf(item("chapati", 100.0, 100.0, 100.0, aiFood = "thepla")))
            val deleted = ok.copy(mealId = "c", aiItemsDeleted = 1)
            eq(Accuracy.report(listOf(ok, swapped, deleted)).identified, 1)
        }
        check("accuracy: 20 good meals pass; worst foods are ranked by the model's error") {
            val meals = (1..20).map { i ->
                Meal("m$i", 0L, "2026-11-${10 + i % 5}", "Lunch", "", listOf(item("chapati", 105.0, 100.0, 110.0), item("rice", 190.0, 200.0, 140.0)), testMode = true)
            }
            val r = Accuracy.report(meals)
            yes(r.passes, "should pass: ${r.confirmed}")
            eq(r.worstFoods.first().foodId, "rice"); near(r.worstFoods.first().bias, -0.30, 0.001)
            yes(meals.filter { !it.testMode }.isEmpty(), "")
            eq(Accuracy.report(meals.map { it.copy(testMode = false) }).testMeals, 0)
        }
        check("accuracy: percentages print as whole numbers") { eq(Accuracy.pct(0.204), "20%"); eq(Accuracy.pct(null), "-") }

        // ---------- stored data ----------
        check("app data survives a save and load unchanged") {
            val a = Analysis.parse(sample, db)
            val meal = Meal.confirm(a.items, db, p0, ist(2026, 11, 14, 14, 10), "Lunch", "ghee roti", "/p/1.jpg", null, "m", true, 1)
            val data = AppData(
                Settings("https://script.google.com/macros/s/x/exec", "t".repeat(32), Portions(160.0, mapOf("chapati" to 40.0)), true),
                listOf(meal), listOf(DayNote("2026-11-14", "Gym", 84.6)),
                listOf(Food.fromLabel("whey", "MuscleBlaze whey", Nutrients(390.0, 75.0, 10.0, 5.0, 0.0), scoopG = 35.0)),
            )
            eq(AppData.parse(data.toJson()), data)
        }
        check("pending meals: saved first, marked sent once, marking twice is harmless") {
            val m = Meal("m1", 0L, "d", "Lunch", "", emptyList())
            var d = AppData().addMeal(m).addMeal(m.copy(mealId = "m2"))
            eq(d.pendingMeals.size, 2)
            d = d.markSynced(listOf("m1"), mapOf("m1" to "https://drive/x")).markSynced(listOf("m1"))
            eq(d.pendingMeals.map { it.mealId }, listOf("m2")); eq(d.meals.first { it.mealId == "m1" }.photoUrl, "https://drive/x")
        }
        check("day notes: type and weight are kept together and re-sent after a change") {
            var d = AppData().setDay("2026-11-14", dayType = "Gym").markDaysSynced(listOf("2026-11-14"))
            eq(d.pendingDays.size, 0)
            d = d.setDay("2026-11-14", weightKg = 84.2)
            eq(d.dayNote("2026-11-14"), DayNote("2026-11-14", "Gym", 84.2, false))
        }
        check("settings: only an https endpoint with a long token counts as connected; 'demo' is demo") {
            yes(!Settings().connected, "empty")
            yes(!Settings("http://x", "t".repeat(32)).connected, "http")
            yes(!Settings("https://x", "short").connected, "short token")
            yes(Settings("https://x", "t".repeat(32)).connected, "good")
            yes(Settings("demo").demo, "demo")
        }
        check("custom label food: per-100 g values and scoop weight") {
            val w = Food.fromLabel("whey", "Whey", Nutrients(390.0, 75.0, 10.0, 5.0, 0.0), scoopG = 35.0)
            near(Units.grams(w, 1.0, "scoop", p0), 35.0); near(w.nutrientsFor(35.0).protein, 26.25)
            eq(w.unitChoices(), listOf("scoop", "g"))
        }
        check("JSON: quotes, newlines and unicode survive") {
            val s = "katori \"vati\"\nघी ₹"
            eq((Json.parse(Json.write(mapOf("s" to s))) as Map<*, *>)["s"], s)
        }
        return count to failures.toList()
    }
}

fun main() {
    val (n, f) = Checks.runAll()
    f.forEach { println("FAIL  ${it.name}\n      ${it.why}") }
    println("${n - f.size}/$n checks passed")
    if (f.isNotEmpty()) kotlin.system.exitProcess(1)
}
