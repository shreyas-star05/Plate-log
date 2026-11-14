package com.microfoods.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.microfoods.app.AppVm
import com.microfoods.app.Photos
import com.microfoods.core.Days
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(vm: AppVm) {
    val d = vm.draft ?: return
    val ctx = LocalContext.current
    val host = rememberMessages(vm)
    var pending by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending
        if (ok && f != null && f.length() > 0) vm.setPhoto(f) else f?.delete()
        pending = null
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runCatching { Photos.copyIn(ctx, uri) }.onSuccess(vm::setPhoto).onFailure { vm.message = "Could not read that photo." }
    }
    val thumb = remember(d.photoFile) { Photos.thumb(d.photoFile, 900)?.asImageBitmap() }

    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Text("New meal") },
                navigationIcon = { IconButton(onClick = vm::cancelDraft) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
            )
        },
        snackbarHost = { SnackbarHost(host, Modifier.imePadding()) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) Image(thumb, "Meal photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text("No photo yet", color = Quiet)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val f = Photos.newFile(ctx)
                        pending = f
                        camera.launch(Photos.uriFor(ctx, f))
                    },
                    modifier = Modifier.weight(1f).testTag("camera"),
                    colors = ButtonDefaults.buttonColors(containerColor = Leaf),
                ) { Icon(Icons.Filled.CameraAlt, null); Text(if (thumb == null) "  Take photo" else "  Retake") }
                OutlinedButton(
                    onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.weight(1f).testTag("gallery"),
                ) { Icon(Icons.Filled.PhotoLibrary, null); Text("  Gallery") }
            }
            OutlinedTextField(
                value = d.hint, onValueChange = vm::setHint, modifier = Modifier.fillMaxWidth().testTag("hint"),
                label = { Text("Hint (optional with a photo)") },
                placeholder = { Text("ghee roti, 3 chapati, half katori dal, home-cooked") },
                minLines = 2,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Days.SLOTS) { s -> FilterChip(selected = d.slot == s, onClick = { vm.setSlot(s) }, label = { Text(s) }) }
            }
            Button(
                onClick = vm::analyse, enabled = d.photoFile != null || d.hint.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("analyse"),
                colors = ButtonDefaults.buttonColors(containerColor = Leaf),
            ) { Text(if (d.photoFile == null) "Read my description" else "Identify the food") }
            TextButton(onClick = vm::manualEntry, modifier = Modifier.testTag("manual")) { Text("Skip the AI and add foods myself") }
        }
    }
}
