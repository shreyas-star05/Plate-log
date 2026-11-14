package com.microfoods.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.microfoods.app.ui.CaptureScreen
import com.microfoods.app.ui.DraftScreen
import com.microfoods.app.ui.PlateTheme
import com.microfoods.app.ui.SettingsScreen
import com.microfoods.app.ui.TodayScreen

class MainActivity : ComponentActivity() {
    private val vm: AppVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PlateTheme { App(vm) } }
    }

    override fun onResume() {
        super.onResume()
        if (vm.data.value.pendingMeals.isNotEmpty() || vm.data.value.pendingDays.isNotEmpty()) vm.syncNow(quiet = true)
    }
}

@Composable
fun App(vm: AppVm) {
    val data by vm.data.collectAsState()
    BackHandler(enabled = vm.screen != Screen.TODAY) {
        when (vm.screen) {
            Screen.CAPTURE, Screen.DRAFT -> vm.cancelDraft()
            else -> vm.screen = Screen.TODAY
        }
    }
    LaunchedEffect(Unit) { if (!data.settings.connected && !data.settings.demo && data.meals.isEmpty()) vm.message = "Start in Settings: add your Sheet link and token, or type demo to try the app." }
    when (vm.screen) {
        Screen.TODAY -> TodayScreen(vm, data)
        Screen.CAPTURE -> CaptureScreen(vm)
        Screen.DRAFT -> DraftScreen(vm, data)
        Screen.SETTINGS -> SettingsScreen(vm, data)
    }
}
