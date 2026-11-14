package com.microfoods.app

import android.graphics.Bitmap
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import androidx.test.espresso.Espresso
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.io.FileOutputStream

/**
 * Walks the whole 0.1 loop on an emulator in demo mode (sample AI answers, no Sheet):
 * settings, day type, capture, draft card edits, weighed grams, confirm, today, accuracy report.
 * Expected numbers are worked out by hand from data/foods.json (see comments).
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UiFlowTest {
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()

    /** A screenshot of whatever was on screen when a test failed. */
    @get:Rule(order = 2) val onFail = object : TestWatcher() {
        override fun failed(e: Throwable?, d: Description) { runCatching { shot("fail-" + d.methodName) } }
    }

    private fun hideKeyboard() { runCatching { Espresso.closeSoftKeyboard() }; settle(300) }

    private fun settle(ms: Long = 600) { rule.waitForIdle(); Thread.sleep(ms); rule.waitForIdle() }

    private fun shot(name: String) {
        settle()
        runCatching { UiDevice.getInstance(inst).findObject(By.text("Wait"))?.click() }
        val bmp = inst.uiAutomation.takeScreenshot() ?: return
        val dir = File(inst.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tag(t: String) = rule.onNodeWithTag(t)
    private fun type(t: String, text: String) { tag(t).performScrollTo(); tag(t).performTextClearance(); tag(t).performTextInput(text); hideKeyboard() }
    private fun waitTag(t: String, ms: Long = 15_000) = rule.waitUntil(ms) { rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }
    private fun waitText(t: String, ms: Long = 15_000) = rule.waitUntil(ms) { rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun draftTo(t: String) { tag("draft-list").performScrollToNode(hasTestTag(t)) }

    @Test
    fun a_logOneMeal() {
        shot("01-today-empty")

        // settings: demo backend, test mode, a 160 ml katori and 40 g chapatis
        tag("open-settings").performClick()
        settle()
        shot("02-settings")
        type("endpoint", "demo")
        type("katori", "160")
        type("piece-chapati", "40")
        tag("test-mode").performScrollTo(); tag("test-mode").performClick()
        tag("test-mode").assertIsOn()
        tag("save-settings").performScrollTo(); tag("save-settings").performClick()
        settle()
        tag("endpoint").performScrollTo()
        shot("03-settings-filled")
        rule.onNodeWithContentDescription("Back").performClick()
        settle()
        waitText("Accuracy test is on")

        tag("daytype-Gym").performClick()
        tag("log-meal").performClick()
        settle()
        type("hint", "ghee roti, 3 chapati, bhendi, varan")
        shot("04-capture")
        tag("analyse").performClick()
        // item cards below the fold are not composed yet, so wait for the first and scroll to the last
        waitTag("item-0")
        shot("05-draft-from-ai")
        draftTo("item-3")
        rule.onNodeWithTag("meal-total").assert(hasText("586 kcal", substring = true))
        draftTo("item-0")

        // stepper: 3 -> 3.5 -> 3 chapati
        tag("plus-0").performClick(); settle(200)
        rule.onNodeWithTag("qty-0").assert(hasText("3.5"))
        tag("minus-0").performClick(); settle(200)
        rule.onNodeWithTag("qty-0").assert(hasText("3"))

        // swap varan (dal) for moong dal through the picker
        draftTo("item-name-2"); tag("item-name-2").performClick()
        settle()
        waitTag("food-search")
        tag("food-search").performTextInput("moong")
        waitTag("food-moong_dal")
        shot("06-food-picker")
        tag("food-moong_dal").performClick()
        settle()

        // add a glass of chaas by its Marathi name
        draftTo("add-item"); tag("add-item").performClick()
        waitTag("food-search")
        tag("food-search").performTextInput("taak")
        waitTag("food-chaas")
        tag("food-chaas").performClick()
        settle()

        // weighed grams for the accuracy test
        listOf("112", "120", "150", "12", "170").forEachIndexed { i, g -> draftTo("weighed-$i"); type("weighed-$i", g) }
        draftTo("item-0")
        shot("07-draft-edited")

        // chapati 3 x 40 g = 120 g -> 242.77 kcal; bhindi 160 ml x 0.7 = 112 g -> 124.10;
        // moong dal 160 g -> 80.00; ghee 3 tsp = 13.65 g -> 119.85; chaas 160 g -> 30.15; total 596.9
        rule.onNodeWithTag("meal-total").assert(hasText("597 kcal", substring = true))
        tag("confirm").performClick()
        waitText("597 kcal")
        shot("08-today-with-meal")
        assertTrue(rule.onAllNodesWithText("597 kcal", substring = true).fetchSemanticsNodes().isNotEmpty())

        // the meal reaches the (demo) Sheet: no "waiting" line left
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("sync-now").fetchSemanticsNodes().isEmpty() }

        tag("open-settings").performClick()
        settle()
        tag("accuracy").performScrollTo()
        shot("09-accuracy-report")
        assertTrue(rule.onAllNodesWithText("1 of 20 weighed meals").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun b_dataSurvivesRestart() {
        // the meal from test a is still there after the activity is recreated
        rule.activityRule.scenario.recreate()
        settle()
        waitText("597 kcal")
        val saved = File(inst.targetContext.filesDir, "microfoods.json").readText()
        assertTrue("meal saved to disk", saved.contains("\"slot\""))
        assertEquals(1, Regex("\"meal_id\"").findAll(saved).count())
        shot("10-after-restart")
    }

    @Test
    fun c_manualEntryAndLabelFood() {
        // a whey from its label: 1 scoop 35 g, 390 kcal and 75 g protein per 100 g -> 136.5 kcal, 26.25 g protein
        tag("open-settings").performClick()
        settle()
        type("label-name", "MuscleBlaze whey")
        type("label-kcal", "390"); type("label-protein", "75"); type("label-carbs", "10"); type("label-fat", "5")
        type("label-scoop", "35")
        tag("label-add").performScrollTo(); tag("label-add").performClick()
        settle()
        shot("11-label-food-added")
        rule.onNodeWithContentDescription("Back").performClick()
        settle()

        tag("log-meal").performClick()
        settle()
        tag("manual").performClick()
        settle()
        tag("add-item").performClick()
        waitTag("food-search")
        tag("food-search").performTextInput("whey")
        waitTag("food-my_muscleblaze_whey")
        tag("food-my_muscleblaze_whey").performClick()
        settle()
        rule.onNodeWithTag("meal-total").assert(hasText("137 kcal", substring = true))
        shot("12-manual-whey")
        tag("confirm").performClick()
        waitText("137 kcal")
        shot("13-today-two-meals")
    }
}
