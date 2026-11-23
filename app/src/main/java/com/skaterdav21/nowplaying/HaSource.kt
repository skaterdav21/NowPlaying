package com.skaterdav21.nowplaying

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * Home Assistant media_player entities. Covers anything HA already knows about: LG webOS TVs,
 * Cast devices, Android TV Remote integration, Spotify, Sonos, etc.
 */
object HaSource {
    private fun headers(token: String) = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")

    suspend fun poll(p: Prefs): List<NowPlaying> = withContext(Dispatchers.IO) {
        val base = p.haUrl.trimEnd('/')
        val arr = JSONArray(Http.get("$base/api/states", headers(p.haToken)))
        val filter = p.haEntities.split(',', '\n', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val id = o.optString("entity_id")
            if (!id.startsWith("media_player.")) return@mapNotNull null
            if (filter.isNotEmpty() && id !in filter) return@mapNotNull null
            parse(base, p.haToken, o)
        }
    }

    private fun parse(base: String, token: String, o: JSONObject): NowPlaying {
        val id = o.getString("entity_id")
        val a = o.optJSONObject("attributes") ?: JSONObject()
        val state = when (o.optString("state")) {
            "playing" -> PlayState.PLAYING
            "paused" -> PlayState.PAUSED
            "buffering" -> PlayState.BUFFERING
            "off", "unavailable", "unknown", "standby" -> PlayState.OFFLINE
            else -> PlayState.IDLE
        }
        val mediaTitle = a.str("media_title")
        val series = a.str("media_series_title")
        val artist = a.str("media_artist")
        val season = a.str("media_season")
        val episode = a.str("media_episode")

        val title: String?
        val subtitle: String?
        when {
            series != null -> {
                title = series
                val se = if (season != null && episode != null) "S${season}E$episode" else null
                subtitle = listOfNotNull(se, mediaTitle).joinToString(" · ").ifBlank { null }
            }
            artist != null -> {
                title = mediaTitle
                subtitle = listOfNotNull(artist, a.str("media_album_name")).joinToString(" · ")
            }
            else -> {
                title = mediaTitle
                subtitle = null
            }
        }

        val durMs = a.optDouble("media_duration", Double.NaN).takeIf { !it.isNaN() }?.let { (it * 1000).toLong() }
        var posMs = a.optDouble("media_position", Double.NaN).takeIf { !it.isNaN() }?.let { (it * 1000).toLong() }
        if (posMs != null && state == PlayState.PLAYING) {
            a.str("media_position_updated_at")?.let { ts ->
                runCatching {
                    val updated = OffsetDateTime.parse(ts).toInstant().toEpochMilli()
                    posMs = posMs!! + (System.currentTimeMillis() - updated).coerceAtLeast(0)
                }
            }
            if (durMs != null) posMs = posMs!!.coerceAtMost(durMs)
        }

        val pic = a.str("entity_picture")
        val artUrl = when {
            pic == null -> null
            pic.startsWith("http") -> pic
            else -> base + pic
        }

        return NowPlaying(
            key = "ha:$id",
            source = SourceType.HA,
            device = a.str("friendly_name") ?: id.removePrefix("media_player."),
            app = a.str("app_name") ?: a.str("source"),
            title = title,
            subtitle = subtitle,
            state = state,
            positionMs = posMs,
            durationMs = durMs,
            artUrl = artUrl,
            artAuth = token,
            controlTarget = id,
        )
    }

    /** service: media_play_pause, media_next_track, media_previous_track, media_stop */
    suspend fun call(p: Prefs, service: String, entityId: String) = withContext(Dispatchers.IO) {
        val base = p.haUrl.trimEnd('/')
        Http.post("$base/api/services/media_player/$service", headers(p.haToken), JSONObject().put("entity_id", entityId).toString())
    }
}
