package com.xboost

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }
}

@Composable
fun Screen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(Prefs.isActive(ctx)) }
    var leftover by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(listOf<AppEntry>()) }
    var target by remember { mutableStateOf(Prefs.target(ctx)) }
    var animOff by remember { mutableStateOf(Prefs.animOff(ctx)) }
    var wl by remember { mutableStateOf(Prefs.whitelist(ctx)) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { AppRepo.launchable(ctx) }
        while (true) {
            running = ShizukuShell.isRunning()
            ready = ShizukuShell.isReady()
            active = Prefs.isActive(ctx)
            leftover = !active && Prefs.suspended(ctx).isNotEmpty()
            delay(1000)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("XBoost", style = MaterialTheme.typography.headlineSmall)
        Text(
            when {
                ready -> "Shizuku: siap"
                running -> "Shizuku jalan, belum ada izin"
                else -> "Shizuku belum jalan (buka app Shizuku & start)"
            }
        )
        if (running && !ready) Button(onClick = { ShizukuShell.requestPermission() }) { Text("Beri izin Shizuku") }

        if (leftover) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Masih ada app terbekukan.", Modifier.weight(1f))
                    Button(onClick = { scope.launch { status = FocusEngine.restoreAll(ctx) } }) { Text("Pulihkan") }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Matikan animasi saat mode aktif", Modifier.weight(1f))
            Switch(checked = animOff, onCheckedChange = { animOff = it; Prefs.setAnimOff(ctx, it) })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!active) {
                Button(enabled = ready && target != null, onClick = {
                    val t = target ?: return@Button
                    scope.launch {
                        status = FocusEngine.start(ctx, t, animOff)
                        ctx.startForegroundService(Intent(ctx, FocusService::class.java))
                        ctx.packageManager.getLaunchIntentForPackage(t)?.let { ctx.startActivity(it) }
                    }
                }) { Text("Mulai Mode Fokus") }
            } else {
                Button(onClick = {
                    scope.launch {
                        status = FocusEngine.restoreAll(ctx)
                        ctx.stopService(Intent(ctx, FocusService::class.java))
                    }
                }) { Text("Hentikan & pulihkan") }
            }
            OutlinedButton(enabled = ready, onClick = { scope.launch { status = FocusEngine.boostMemory(ctx) } }) {
                Text("Kosongkan RAM")
            }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)

        Text(if (active) "Mode aktif. Pilih app untuk dibuka floating:" else "Pilih app yang mau difokuskan (WL = whitelist, tidak dibekukan):")
        LazyColumn(Modifier.weight(1f)) {
            items(apps, key = { it.pkg }) { a ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = target == a.pkg, enabled = !active,
                        onClick = { target = a.pkg; Prefs.setTarget(ctx, a.pkg) })
                    Text(a.label, Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { Prefs.toggleWhitelist(ctx, a.pkg); wl = Prefs.whitelist(ctx) }) {
                        Text(if (a.pkg in wl) "WL ✓" else "WL")
                    }
                    if (active) TextButton(onClick = {
                        scope.launch { status = FocusEngine.launchFloating(ctx, a.pkg) }
                    }) { Text("Float") }
                }
            }
        }
    }
}
