package com.xboost

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
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
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    if (bmp != null) Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(size))
    else Spacer(Modifier.size(size))
}

/** Daftar app dengan checkbox; dipakai untuk tambah game, app floating, dan whitelist. */
@Composable
fun AppPicker(apps: List<AppEntry>, selected: Set<String>, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier) {
        items(apps, key = { it.pkg }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable { onToggle(a.pkg) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = a.pkg in selected, onCheckedChange = { onToggle(a.pkg) })
                AppIcon(a.pkg, 32.dp)
                Spacer(Modifier.width(10.dp))
                Text(a.label, maxLines = 1)
            }
        }
    }
}

@Composable
fun Screen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(Prefs.isActive(ctx)) }
    var activeTarget by remember { mutableStateOf(Prefs.target(ctx)) }
    var leftover by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf(listOf<AppEntry>()) }
    var games by remember { mutableStateOf(Prefs.games(ctx)) }
    var wl by remember { mutableStateOf(Prefs.whitelist(ctx)) }
    var lockPerf by remember { mutableStateOf(Prefs.lockPerf(ctx)) }
    var maxPerf by remember { mutableStateOf(Prefs.maxPerf(ctx)) }
    var animOff by remember { mutableStateOf(Prefs.animOff(ctx)) }
    var notifOff by remember { mutableStateOf(Prefs.notifOff(ctx)) }
    var status by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) { AppRepo.launchable(ctx) }
        while (true) {
            running = ShizukuShell.isRunning()
            ready = ShizukuShell.isReady()
            active = Prefs.isActive(ctx)
            activeTarget = Prefs.target(ctx)
            leftover = !active && Prefs.suspended(ctx).isNotEmpty()
            delay(1000)
        }
    }

    fun launchApp(pkg: String) {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(it)
        }
    }

    fun startGame(pkg: String) {
        if (active) {
            if (activeTarget == pkg) launchApp(pkg) else status = "XBoost masih aktif untuk game lain, hentikan dulu"
            return
        }
        scope.launch {
            status = FocusEngine.start(ctx, pkg)
            if (Prefs.isActive(ctx)) {
                ctx.startForegroundService(Intent(ctx, BoostService::class.java))
                launchApp(pkg)
            }
        }
    }

    fun stopAll() {
        scope.launch {
            status = FocusEngine.restoreAll(ctx)
            ctx.stopService(Intent(ctx, BoostService::class.java))
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

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Game") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Pengaturan") })
        }

        when (tab) {
            0 -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { showAdd = true }) { Text("+ Tambah game") }
                    if (active) OutlinedButton(onClick = { stopAll() }) { Text("Hentikan XBoost") }
                }
                val gameApps = all.filter { it.pkg in games }
                if (gameApps.isEmpty()) Text("Belum ada game. Tekan \"Tambah game\".")
                LazyColumn(Modifier.weight(1f)) {
                    items(gameApps, key = { it.pkg }) { g ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { startGame(g.pkg) }) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(g.pkg, 44.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(g.label, Modifier.weight(1f), maxLines = 1)
                                Text(if (active && activeTarget == g.pkg) "Berjalan" else "Mulai")
                            }
                        }
                    }
                }
            }
            else -> {
                @Composable
                fun Opt(title: String, desc: String, on: Boolean, set: (Boolean) -> Unit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title)
                            Text(desc, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = on, onCheckedChange = set)
                    }
                }
                Opt("Mode performa", "Lock CPU & GPU ke game (lock penuh butuh Shizuku root)", lockPerf) {
                    lockPerf = it; Prefs.setLockPerf(ctx, it)
                }
                Opt("Optimasi game", "Game Mode performance, refresh rate maksimum, anti throttle (hp lebih panas)", maxPerf) {
                    maxPerf = it; Prefs.setMaxPerf(ctx, it)
                }
                Opt("Matikan animasi", "Dikembalikan saat XBoost dihentikan", animOff) {
                    animOff = it; Prefs.setAnimOff(ctx, it)
                }
                Opt("Matikan notifikasi", "Mode senyap total selama XBoost aktif", notifOff) {
                    notifOff = it; Prefs.setNotifOff(ctx, it)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = ready, onClick = { scope.launch { status = FocusEngine.boostMemory(ctx) } }) {
                        Text("Kosongkan RAM")
                    }
                    OutlinedButton(onClick = { scope.launch { status = FocusEngine.restoreAll(ctx) } }) { Text("Pulihkan semua") }
                }
                Text("Whitelist (tidak pernah dibekukan):")
                AppPicker(all, wl, { Prefs.toggleWhitelist(ctx, it); wl = Prefs.whitelist(ctx) }, Modifier.weight(1f))
            }
        }

        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            confirmButton = { TextButton(onClick = { showAdd = false }) { Text("Selesai") } },
            title = { Text("Tambah game") },
            text = {
                AppPicker(all, games, { Prefs.toggleGame(ctx, it); games = Prefs.games(ctx) },
                    Modifier.heightIn(max = 420.dp))
            }
        )
    }
}
