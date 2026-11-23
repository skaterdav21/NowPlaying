package com.skaterdav21.nowplaying

import kotlinx.coroutines.flow.MutableStateFlow

enum class PlayState { PLAYING, PAUSED, BUFFERING, IDLE, OFFLINE }

enum class SourceType { ADB, HA, PLEX }

data class NowPlaying(
    /** Stable id, e.g. "adb:192.168.0.20:5555", "ha:media_player.lg_tv", "plex:abc123". */
    val key: String,
    val source: SourceType,
    val device: String,
    val app: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val state: PlayState,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val artUrl: String? = null,
    /** Bearer token for fetching art, if needed. */
    val artAuth: String? = null,
    /** ADB device key or HA entity id used for transport controls. */
    val controlTarget: String? = null,
    val error: String? = null,
) {
    val isActive: Boolean
        get() = state == PlayState.PLAYING || state == PlayState.PAUSED || state == PlayState.BUFFERING

    val stateLabel: String
        get() = when (state) {
            PlayState.PLAYING -> "Playing"
            PlayState.PAUSED -> "Paused"
            PlayState.BUFFERING -> "Buffering"
            PlayState.IDLE -> "Idle"
            PlayState.OFFLINE -> "Offline"
        }

    /** One-line summary, used in the inbox-style summary notification and the app UI. */
    fun oneLine(): String {
        val what = when {
            title != null && app != null -> "$app: $title"
            title != null -> title
            app != null -> app
            else -> null
        }
        return when {
            state == PlayState.OFFLINE -> "$device · offline"
            what == null -> "$device · ${stateLabel.lowercase()}"
            isActive -> "$device · $what" + if (state != PlayState.PLAYING) " (${stateLabel.lowercase()})" else ""
            else -> "$device · $what (idle)"
        }
    }
}

data class AdbDevice(val name: String, val host: String, val port: Int = 5555) {
    val key: String get() = "$host:$port"
}

/** Shared live state between the service and the UI. */
object StateBus {
    val items = MutableStateFlow<List<NowPlaying>>(emptyList())
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("Stopped")
    val lastUpdate = MutableStateFlow(0L)
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
