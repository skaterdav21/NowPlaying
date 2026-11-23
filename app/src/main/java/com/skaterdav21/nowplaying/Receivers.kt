package com.skaterdav21.nowplaying

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        when (intent.action) {
            ACTION_STOP -> { ServiceController.stop(app); return }
            ACTION_REFRESH -> { ServiceController.refresh(app); return }
        }
        if (intent.action != ACTION_CONTROL) return
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: return
        val target = intent.getStringExtra(EXTRA_TARGET) ?: return
        val cmd = intent.getStringExtra(EXTRA_CMD) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val p = Prefs(app)
                when (source) {
                    SourceType.ADB.name -> p.devices.firstOrNull { it.key == target }?.let { dev ->
                        cmd.toIntOrNull()?.let { AdbSource.sendKey(app, dev, it) }
                    }
                    SourceType.HA.name -> HaSource.call(p, cmd, target)
                }
                delay(700)
                ServiceController.refresh(app)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CONTROL = "com.skaterdav21.nowplaying.CONTROL"
        const val ACTION_STOP = "com.skaterdav21.nowplaying.ACTION_STOP"
        const val ACTION_REFRESH = "com.skaterdav21.nowplaying.ACTION_REFRESH"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_TARGET = "target"
        const val EXTRA_CMD = "cmd"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        if (!p.serviceEnabled) return
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && !p.startOnBoot) return
        runCatching { ServiceController.start(ctx.applicationContext) }
    }
}
