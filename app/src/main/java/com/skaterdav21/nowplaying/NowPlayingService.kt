package com.skaterdav21.nowplaying

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date

class NowPlayingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON || i.action == Intent.ACTION_USER_PRESENT) kick.trySend(Unit)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.SUMMARY_ID, Notifier.initial(this), type)
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        StateBus.running.value = true
        StateBus.status.value = "Starting"
        loop = scope.launch { runLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs(this).serviceEnabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> kick.trySend(Unit)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        Notifier.clearAll(this)
        AdbPool.closeAll()
        StateBus.running.value = false
        StateBus.status.value = "Stopped"
        super.onDestroy()
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            val p = Prefs(this)
            val blocked = pauseReason(p)
            if (blocked != null) {
                StateBus.status.value = "Paused: $blocked"
                Notifier.paused(this, blocked)
            } else {
                runCatching { pollOnce(p) }.onFailure { StateBus.status.value = "Error: ${it.message}" }
            }
            val waitMs = if (blocked != null) 30_000L else p.intervalSec.coerceIn(3, 120) * 1000L
            withTimeoutOrNull(waitMs) { kick.receive() }
        }
    }

    private fun pauseReason(p: Prefs): String? {
        if (p.pauseScreenOff) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isInteractive) return "phone screen is off"
        }
        if (p.wifiOnly && !onLocalNetwork()) return "not on Wi-Fi"
        return null
    }

    @Suppress("DEPRECATION")
    private fun onLocalNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }

    private suspend fun pollOnce(p: Prefs) {
        val ctx = this
        val results = coroutineScope {
            val adb = p.devices.map { d -> async { AdbSource.poll(ctx, d) } }
            val ha = if (p.haEnabled && p.haToken.isNotBlank()) async {
                runCatching { HaSource.poll(p) }.getOrElse {
                    listOf(NowPlaying("ha:error", SourceType.HA, "Home Assistant", state = PlayState.OFFLINE, error = it.message))
                }
            } else null
            val plex = if (p.plexEnabled && p.plexToken.isNotBlank()) async {
                runCatching { PlexSource.poll(p) }.getOrElse {
                    listOf(NowPlaying("plex:error", SourceType.PLEX, "Plex server", state = PlayState.OFFLINE, error = it.message))
                }
            } else null
            adb.awaitAll() + (ha?.await() ?: emptyList()) + (plex?.await() ?: emptyList())
        }
        StateBus.items.value = results
        StateBus.lastUpdate.value = System.currentTimeMillis()
        StateBus.status.value = "Updated " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
        Notifier.update(this, results, p)
    }

    companion object {
        const val ACTION_STOP = "com.skaterdav21.nowplaying.STOP"
        const val ACTION_REFRESH = "com.skaterdav21.nowplaying.REFRESH"
    }
}

object ServiceController {
    fun start(ctx: Context) {
        Prefs(ctx).serviceEnabled = true
        ContextCompat.startForegroundService(ctx, Intent(ctx, NowPlayingService::class.java))
    }

    fun stop(ctx: Context) {
        Prefs(ctx).serviceEnabled = false
        ctx.stopService(Intent(ctx, NowPlayingService::class.java))
    }

    fun refresh(ctx: Context) {
        if (!StateBus.running.value) return
        runCatching {
            ctx.startService(Intent(ctx, NowPlayingService::class.java).setAction(NowPlayingService.ACTION_REFRESH))
        }
    }
}
