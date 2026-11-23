package com.skaterdav21.nowplaying

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.abs

object Notifier {
    const val SUMMARY_ID = 1
    private const val CH_SERVICE = "summary"
    private const val CH_ITEMS = "now_playing"
    private const val GROUP = "com.skaterdav21.nowplaying.GROUP"

    private val posted = mutableSetOf<Int>()

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Now Playing summary", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent overview of every device"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ITEMS, "Per-device now playing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "One card per TV that is playing something"
                setShowBadge(false)
            }
        )
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun broadcast(ctx: Context, action: String, req: Int, extras: Intent.() -> Unit = {}) =
        PendingIntent.getBroadcast(
            ctx, req, Intent(ctx, ActionReceiver::class.java).setAction(action).apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun initial(ctx: Context): Notification = summaryBuilder(ctx)
        .setContentTitle("Now Playing")
        .setContentText("Checking your devices…")
        .build()

    private fun summaryBuilder(ctx: Context) = NotificationCompat.Builder(ctx, CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_tv)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentIntent(openApp(ctx))
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .addAction(R.drawable.ic_refresh, "Refresh", broadcast(ctx, ActionReceiver.ACTION_REFRESH, 1))
        .addAction(R.drawable.ic_stop, "Stop", broadcast(ctx, ActionReceiver.ACTION_STOP, 2))

    fun idForKey(key: String) = 1000 + abs(key.hashCode() % 1_000_000)

    @SuppressLint("MissingPermission")
    fun paused(ctx: Context, reason: String) {
        val nm = NotificationManagerCompat.from(ctx)
        clearChildren(nm, emptySet())
        if (!nm.areNotificationsEnabled()) return
        nm.notify(SUMMARY_ID, summaryBuilder(ctx).setContentTitle("Now Playing paused").setContentText(reason).build())
    }

    @SuppressLint("MissingPermission")
    suspend fun update(ctx: Context, items: List<NowPlaying>, p: Prefs) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return

        val active = items.filter { it.isActive }
        val online = items.count { it.state != PlayState.OFFLINE }
        val children = if (!p.perDevice) emptyList()
        else items.filter { it.isActive || (p.showIdle && it.state == PlayState.IDLE) }

        // Children first, so the group summary always has something to group.
        val keep = mutableSetOf<Int>()
        for (item in children) {
            val id = idForKey(item.key)
            keep += id
            nm.notify(id, child(ctx, item))
        }
        clearChildren(nm, keep)

        val title = when (active.size) {
            0 -> "Nothing playing"
            1 -> active[0].let { "${it.device}: ${it.title ?: it.app ?: it.stateLabel}" }
            else -> "${active.size} devices playing"
        }
        val text = when {
            items.isEmpty() -> "No devices configured. Tap to set up."
            active.size == 1 -> listOfNotNull(active[0].subtitle, active[0].app).joinToString(" · ").ifBlank { active[0].stateLabel }
            else -> "$online of ${items.size} online"
        }
        val inbox = NotificationCompat.InboxStyle().setSummaryText("$online of ${items.size} online")
        items.sortedWith(compareBy({ !it.isActive }, { it.state == PlayState.OFFLINE }, { it.device }))
            .forEach { inbox.addLine(it.oneLine()) }

        val b = summaryBuilder(ctx)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(inbox)
        if (children.isNotEmpty()) b.setGroup(GROUP).setGroupSummary(true)
        if (!p.perDevice && active.size == 1) {
            val a = active[0]
            ArtCache.get(a.artUrl, a.artAuth)?.let { b.setLargeIcon(it) }
            val d = a.durationMs
            val pos = a.positionMs
            if (d != null && d > 0 && pos != null) b.setProgress((d / 1000).toInt(), (pos / 1000).toInt(), false)
        }
        nm.notify(SUMMARY_ID, b.build())
    }

    private fun clearChildren(nm: NotificationManagerCompat, keep: Set<Int>) {
        synchronized(posted) {
            (posted - keep).forEach { nm.cancel(it) }
            posted.clear()
            posted.addAll(keep)
        }
    }

    fun clearAll(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        clearChildren(nm, emptySet())
        nm.cancel(SUMMARY_ID)
    }

    private suspend fun child(ctx: Context, item: NowPlaying): Notification {
        val pos = item.positionMs
        val dur = item.durationMs
        val time = when {
            pos != null && dur != null && dur > 0 -> "${formatTime(pos)} / ${formatTime(dur)}"
            pos != null && pos > 0 -> formatTime(pos)
            else -> null
        }
        val status = if (item.state == PlayState.PLAYING) null else item.stateLabel
        val text = listOfNotNull(item.subtitle, status, time).joinToString(" · ")

        val b = NotificationCompat.Builder(ctx, CH_ITEMS)
            .setSmallIcon(R.drawable.ic_stat_tv)
            .setContentTitle(item.title ?: item.app ?: item.device)
            .setContentText(text.ifBlank { item.stateLabel })
            .setSubText(listOfNotNull(item.device, item.app?.takeIf { item.title != null }).joinToString(" · "))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setGroup(GROUP)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx))

        ArtCache.get(item.artUrl, item.artAuth)?.let { b.setLargeIcon(it) }
        if (dur != null && dur > 0 && pos != null) b.setProgress((dur / 1000).toInt(), (pos / 1000).toInt(), false)

        val target = item.controlTarget
        if (target != null && item.state != PlayState.OFFLINE) {
            val base = idForKey(item.key) * 10
            fun ctl(cmd: String, n: Int) = broadcast(ctx, ActionReceiver.ACTION_CONTROL, base + n) {
                putExtra(ActionReceiver.EXTRA_SOURCE, item.source.name)
                putExtra(ActionReceiver.EXTRA_TARGET, target)
                putExtra(ActionReceiver.EXTRA_CMD, cmd)
            }
            val playing = item.state == PlayState.PLAYING
            val ppIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
            val ppLabel = if (playing) "Pause" else "Play"
            when (item.source) {
                SourceType.ADB -> {
                    b.addAction(R.drawable.ic_rew, "Rew", ctl(AdbSource.KEY_REWIND.toString(), 1))
                    b.addAction(ppIcon, ppLabel, ctl(AdbSource.KEY_PLAY_PAUSE.toString(), 2))
                    b.addAction(R.drawable.ic_ff, "FF", ctl(AdbSource.KEY_FAST_FORWARD.toString(), 3))
                }
                SourceType.HA -> {
                    b.addAction(R.drawable.ic_prev, "Prev", ctl("media_previous_track", 1))
                    b.addAction(ppIcon, ppLabel, ctl("media_play_pause", 2))
                    b.addAction(R.drawable.ic_next, "Next", ctl("media_next_track", 3))
                }
                SourceType.PLEX -> Unit
            }
        }
        return b.build()
    }
}
