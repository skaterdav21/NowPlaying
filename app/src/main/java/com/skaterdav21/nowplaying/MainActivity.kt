package com.skaterdav21.nowplaying

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.createChannels(this)
        val prefs = Prefs(this)
        if (prefs.serviceEnabled && !StateBus.running.value) runCatching { ServiceController.start(this) }
        setContent {
            val ctx = LocalContext.current
            val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(ctx) else darkColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0B0F)) { App(prefs) }
            }
        }
    }
}

private fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16171D))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Color(0xFF9A9AA5))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun App(prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by StateBus.running.collectAsState()
    val status by StateBus.status.collectAsState()
    val items by StateBus.items.collectAsState()

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ServiceController.start(ctx) else toast(ctx, "Notifications are required for the persistent card")
    }
    fun startService() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else ServiceController.start(ctx)
    }

    val devices = remember { mutableStateListOf<AdbDevice>().apply { addAll(prefs.devices) } }
    fun saveDevices() { prefs.devices = devices.toList(); ServiceController.refresh(ctx) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text("Now Playing", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Live status of your TVs, pinned in your notifications", fontSize = 13.sp, color = Color(0xFF9A9AA5))
        Spacer(Modifier.height(8.dp))

        // ---------- Service ----------
        Section("Service") {
            ToggleRow(
                "Persistent notification",
                status,
                checked = running,
            ) { on -> if (on) startService() else ServiceController.stop(ctx) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ServiceController.refresh(ctx) }, enabled = running) { Text("Refresh now") }
                val pm = ctx.getSystemService(PowerManager::class.java)
                if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    OutlinedButton(onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                        )
                    }) { Text("Allow background") }
                }
            }
        }

        // ---------- Live ----------
        Section("Now playing") {
            if (items.isEmpty()) {
                Text(if (running) "Waiting for first update…" else "Start the service to see live status.", color = Color(0xFF9A9AA5))
            }
            items.sortedWith(compareBy({ !it.isActive }, { it.state == PlayState.OFFLINE }, { it.device })).forEach {
                LiveRow(it)
            }
        }

        // ---------- ADB devices ----------
        Section("Android TV devices (ADB)") {
            Text(
                "On each TV: Settings › Device Preferences › About › tap Build 7×, then Developer options › USB debugging (and Network debugging on Shield). " +
                    "Add it here, tap Connect, and accept \"Always allow\" on the TV.",
                fontSize = 12.sp, color = Color(0xFF9A9AA5)
            )
            devices.forEachIndexed { idx, d ->
                DeviceRow(d,
                    onTest = {
                        scope.launch {
                            toast(ctx, "Connecting to ${d.host}… check the TV for a prompt")
                            val res = runCatching {
                                AdbPool.shell(ctx, d, "getprop ro.product.model", timeoutMs = 30_000)?.trim()
                            }
                            res.onSuccess { model ->
                                toast(ctx, "Connected: ${model ?: "ok"}")
                                if (!model.isNullOrBlank() && (d.name.isBlank() || d.name == d.host)) {
                                    devices[idx] = d.copy(name = model); saveDevices()
                                } else ServiceController.refresh(ctx)
                            }.onFailure { toast(ctx, "Failed: ${it.message}") }
                        }
                    },
                    onDelete = { devices.removeAt(idx); saveDevices() })
            }
            AddDevice { dev ->
                if (devices.none { it.key == dev.key }) { devices.add(dev); saveDevices() }
            }
            Scanner(existing = devices.map { it.host }.toSet()) { host ->
                if (devices.none { it.host == host }) { devices.add(AdbDevice(host, host)); saveDevices() }
            }
        }

        // ---------- Home Assistant ----------
        Section("Home Assistant") {
            var enabled by remember { mutableStateOf(prefs.haEnabled) }
            var url by remember { mutableStateOf(prefs.haUrl) }
            var token by remember { mutableStateOf(prefs.haToken) }
            var ents by remember { mutableStateOf(prefs.haEntities) }
            ToggleRow("Include media_player entities", "LG webOS, Cast, Spotify, anything HA tracks", enabled) {
                enabled = it; prefs.haEnabled = it; ServiceController.refresh(ctx)
            }
            OutlinedTextField(url, { url = it; prefs.haUrl = it }, label = { Text("URL") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(token, { token = it; prefs.haToken = it }, label = { Text("Long-lived access token") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(ents, { ents = it; prefs.haEntities = it }, label = { Text("Entity filter (blank = all)") },
                placeholder = { Text("media_player.lg_tv, media_player.living_room") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching { HaSource.poll(prefs) }
                        .onSuccess { l -> toast(ctx, "OK: ${l.size} media players, ${l.count { it.isActive }} active") }
                        .onFailure { toast(ctx, "Failed: ${it.message}") }
                }
            }) { Text("Test") }
        }

        // ---------- Plex ----------
        Section("Plex server") {
            var enabled by remember { mutableStateOf(prefs.plexEnabled) }
            var url by remember { mutableStateOf(prefs.plexUrl) }
            var token by remember { mutableStateOf(prefs.plexToken) }
            ToggleRow("Include Plex sessions", "Shows who is watching, with progress and poster", enabled) {
                enabled = it; prefs.plexEnabled = it; ServiceController.refresh(ctx)
            }
            OutlinedTextField(url, { url = it; prefs.plexUrl = it }, label = { Text("Server URL") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(token, { token = it; prefs.plexToken = it }, label = { Text("X-Plex-Token") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching { PlexSource.poll(prefs) }
                        .onSuccess { l -> toast(ctx, "OK: ${l.size} active sessions") }
                        .onFailure { toast(ctx, "Failed: ${it.message}") }
                }
            }) { Text("Test") }
        }

        // ---------- Options ----------
        Section("Options") {
            var interval by remember { mutableFloatStateOf(prefs.intervalSec.toFloat()) }
            Text("Update every ${interval.roundToInt()} s")
            Slider(value = interval, onValueChange = { interval = it }, valueRange = 3f..60f,
                onValueChangeFinished = { prefs.intervalSec = interval.roundToInt(); ServiceController.refresh(ctx) })
            var perDevice by remember { mutableStateOf(prefs.perDevice) }
            ToggleRow("Card per device", "Separate notification with controls for each TV that's playing", perDevice) {
                perDevice = it; prefs.perDevice = it; ServiceController.refresh(ctx)
            }
            var showIdle by remember { mutableStateOf(prefs.showIdle) }
            ToggleRow("Cards for idle devices", "Also show TVs that are on but not playing", showIdle) {
                showIdle = it; prefs.showIdle = it; ServiceController.refresh(ctx)
            }
            var screenOff by remember { mutableStateOf(prefs.pauseScreenOff) }
            ToggleRow("Pause while screen is off", "Saves battery; refreshes instantly on unlock", screenOff) {
                screenOff = it; prefs.pauseScreenOff = it; ServiceController.refresh(ctx)
            }
            var wifi by remember { mutableStateOf(prefs.wifiOnly) }
            ToggleRow("Only on Wi-Fi", "Turn off if you reach the TVs over Tailscale", wifi) {
                wifi = it; prefs.wifiOnly = it; ServiceController.refresh(ctx)
            }
            var boot by remember { mutableStateOf(prefs.startOnBoot) }
            ToggleRow("Start on boot", null, boot) { boot = it; prefs.startOnBoot = it }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LiveRow(n: NowPlaying) {
    val dot = when (n.state) {
        PlayState.PLAYING -> Color(0xFF3DDC84)
        PlayState.PAUSED, PlayState.BUFFERING -> Color(0xFFFFC107)
        PlayState.IDLE -> Color(0xFF7A7A85)
        PlayState.OFFLINE -> Color(0xFFE5484D)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("●", color = dot, fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Text(n.device, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text(listOfNotNull(n.app, n.stateLabel).joinToString(" · "), fontSize = 12.sp, color = Color(0xFF9A9AA5))
        }
        n.title?.let { Text(it, fontSize = 15.sp) }
        n.subtitle?.let { Text(it, fontSize = 13.sp, color = Color(0xFFB8B8C2)) }
        val d = n.durationMs
        val p = n.positionMs
        if (d != null && d > 0 && p != null) {
            LinearProgressIndicator(progress = { (p.toFloat() / d).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Text("${formatTime(p)} / ${formatTime(d)}", fontSize = 11.sp, color = Color(0xFF9A9AA5))
        } else if (p != null && p > 0) {
            Text(formatTime(p), fontSize = 11.sp, color = Color(0xFF9A9AA5))
        }
        n.error?.let { Text(it, fontSize = 11.sp, color = Color(0xFFE5484D)) }
        HorizontalDivider(Modifier.padding(top = 6.dp), color = Color(0xFF24252C))
    }
}

@Composable
private fun DeviceRow(d: AdbDevice, onTest: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.name.ifBlank { d.host }, fontWeight = FontWeight.SemiBold)
            Text("${d.host}:${d.port}", fontSize = 12.sp, color = Color(0xFF9A9AA5))
        }
        TextButton(onClick = onTest) { Text("Connect") }
        TextButton(onClick = onDelete) { Text("Remove", color = Color(0xFFE5484D)) }
    }
}

@Composable
private fun AddDevice(onAdd: (AdbDevice) -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5555") }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(host, { host = it.trim() }, label = { Text("IP") }, singleLine = true, modifier = Modifier.weight(1.2f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
            modifier = Modifier.weight(0.7f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    Button(onClick = {
        if (host.isNotBlank()) {
            onAdd(AdbDevice(name.trim().ifBlank { host }, host, port.toIntOrNull() ?: 5555))
            name = ""; host = ""; port = "5555"
        }
    }) { Text("Add device") }
}

@Composable
private fun Scanner(existing: Set<String>, onAdd: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var found by remember { mutableStateOf<List<String>?>(null) }
    OutlinedButton(enabled = !scanning, onClick = {
        scanning = true; progress = 0f; found = null
        scope.launch {
            runCatching {
                NetworkScanner.scan(ctx) { done, total -> progress = done.toFloat() / total }
            }.onSuccess { found = it }.onFailure { toast(ctx, "Scan failed: ${it.message}") }
            scanning = false
        }
    }) { Text(if (scanning) "Scanning…" else "Scan network for ADB (port 5555)") }
    if (scanning) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    found?.let { list ->
        if (list.isEmpty()) Text("No devices with network ADB found.", fontSize = 12.sp, color = Color(0xFF9A9AA5))
        list.forEach { h ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(h, Modifier.weight(1f))
                if (h in existing) Text("added", fontSize = 12.sp, color = Color(0xFF9A9AA5))
                else TextButton(onClick = { onAdd(h) }) { Text("Add") }
            }
        }
    }
}
