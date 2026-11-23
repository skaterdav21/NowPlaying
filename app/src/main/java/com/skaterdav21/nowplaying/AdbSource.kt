package com.skaterdav21.nowplaying

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads what an Android TV / Google TV / Shield / onn box is playing via `dumpsys media_session`
 * over network ADB. Works for any app that publishes a MediaSession (Nuvio, YouTube, SmartTube,
 * Netflix, Plex, TiviMate, Stremio, Kodi...). Falls back to the foreground app when nothing
 * has an active session.
 */
object AdbSource {
    private const val SPLIT = "__NP_SPLIT__"
    private const val CMD =
        "dumpsys media_session; echo $SPLIT; cat /proc/uptime; echo $SPLIT; " +
            "dumpsys activity activities | grep -m 2 -E 'mResumedActivity|topResumedActivity'"

    private val last = ConcurrentHashMap<String, NowPlaying>()

    private data class Session(
        val pkg: String,
        var active: Boolean = false,
        var state: Int = 0,
        var position: Long = -1,
        var speed: Float = 1f,
        var updated: Long = 0,
        var title: String? = null,
        var subtitle: String? = null,
    )

    private val stateRe = Regex("""state=PlaybackState \{state=(-?\d+), position=(-?\d+),.*?speed=(-?[\d.]+), updated=(\d+)""")
    private val descRe = Regex("""description=(.*)$""")
    private val fgRe = Regex("""u\d+ ([\w.]+)/""")

    fun keyFor(dev: AdbDevice) = "adb:${dev.key}"

    suspend fun poll(ctx: Context, dev: AdbDevice): NowPlaying {
        val key = keyFor(dev)
        val name = dev.name.ifBlank { dev.host }
        if (!AdbPool.reachable(dev.host, dev.port)) {
            return NowPlaying(key, SourceType.ADB, name, state = PlayState.OFFLINE, error = "Not reachable on port ${dev.port}")
                .also { last[key] = it }
        }
        val out = try {
            AdbPool.shell(ctx, dev, CMD, timeoutMs = 8000, skipIfBusy = true)
                ?: return last[key] ?: NowPlaying(key, SourceType.ADB, name, state = PlayState.IDLE)
        } catch (e: Exception) {
            return NowPlaying(key, SourceType.ADB, name, state = PlayState.OFFLINE, error = e.message ?: "ADB error")
                .also { last[key] = it }
        }
        return parse(dev, name, out).also { last[key] = it }
    }

    private fun parse(dev: AdbDevice, name: String, out: String): NowPlaying {
        val parts = out.split(SPLIT)
        val sessions = parseSessions(parts[0])
        val uptimeMs = parts.getOrNull(1)?.trim()?.split(" ")?.firstOrNull()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        val fgPkg = fgRe.find(parts.getOrNull(2) ?: "")?.groupValues?.get(1)

        val best = sessions
            .filter { it.state in setOf(2, 3, 4, 5, 6, 8, 9, 10) }
            .maxByOrNull { s ->
                var score = 0
                if (s.state == 3 || s.state == 4 || s.state == 5) score += 100
                if (s.state == 6 || s.state == 8) score += 60
                if (s.state == 2) score += 40
                if (s.active) score += 20
                if (s.title != null) score += 10
                if (s.pkg == fgPkg) score += 5
                score
            }

        val key = keyFor(dev)
        if (best != null) {
            val st = when (best.state) {
                3, 4, 5, 9, 10 -> PlayState.PLAYING
                6, 8 -> PlayState.BUFFERING
                else -> PlayState.PAUSED
            }
            var pos = best.position.takeIf { it >= 0 }
            if (pos != null && st == PlayState.PLAYING && uptimeMs != null && best.updated > 0 && uptimeMs > best.updated) {
                pos += ((uptimeMs - best.updated) * best.speed).toLong()
            }
            return NowPlaying(
                key = key,
                source = SourceType.ADB,
                device = name,
                app = AppNames.label(best.pkg),
                title = best.title,
                subtitle = best.subtitle,
                state = st,
                positionMs = pos,
                controlTarget = dev.key,
            )
        }

        val home = fgPkg == null || fgPkg in AppNames.launchers
        return NowPlaying(
            key = key,
            source = SourceType.ADB,
            device = name,
            app = if (home) "Home" else AppNames.label(fgPkg),
            state = PlayState.IDLE,
            controlTarget = dev.key,
        )
    }

    private fun parseSessions(text: String): List<Session> {
        val list = mutableListOf<Session>()
        var cur: Session? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("package=")) {
                cur = Session(line.removePrefix("package=").trim()).also { list += it }
                continue
            }
            val c = cur ?: continue
            when {
                line.startsWith("active=") -> c.active = line.removePrefix("active=").trim() == "true"
                line.startsWith("state=PlaybackState") -> stateRe.find(line)?.let { m ->
                    c.state = m.groupValues[1].toIntOrNull() ?: 0
                    c.position = m.groupValues[2].toLongOrNull() ?: -1
                    c.speed = m.groupValues[3].toFloatOrNull() ?: 1f
                    c.updated = m.groupValues[4].toLongOrNull() ?: 0
                }
                line.startsWith("metadata:") -> descRe.find(line)?.let { m ->
                    val (t, sub) = splitDescription(m.groupValues[1])
                    c.title = t
                    c.subtitle = sub
                }
            }
        }
        // The same session can be listed in several stacks; keep the most informative copy.
        return list.groupBy { it.pkg }.map { (_, v) -> v.maxBy { (if (it.title != null) 2 else 0) + (if (it.state == 3) 1 else 0) } }
    }

    /** MediaDescription.toString() is "title, subtitle, description"; titles can contain commas. */
    private fun splitDescription(d: String): Pair<String?, String?> {
        fun clean(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        val p = d.split(", ")
        return when {
            p.size >= 3 -> clean(p.dropLast(2).joinToString(", ")) to clean(p[p.size - 2])
            p.size == 2 -> clean(p[0]) to clean(p[1])
            else -> clean(d) to null
        }
    }

    /** Transport control via key events. */
    suspend fun sendKey(ctx: Context, dev: AdbDevice, keycode: Int) {
        AdbPool.shell(ctx, dev, "input keyevent $keycode", timeoutMs = 6000)
    }

    const val KEY_PLAY_PAUSE = 85
    const val KEY_REWIND = 89
    const val KEY_FAST_FORWARD = 90
}
