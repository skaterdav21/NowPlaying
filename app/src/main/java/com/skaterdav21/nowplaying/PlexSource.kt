package com.skaterdav21.nowplaying

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** Active Plex sessions from your server, including who is watching and progress. */
object PlexSource {
    suspend fun poll(p: Prefs): List<NowPlaying> = withContext(Dispatchers.IO) {
        val base = p.plexUrl.trimEnd('/')
        val body = Http.get(
            "$base/status/sessions",
            mapOf("X-Plex-Token" to p.plexToken, "Accept" to "application/json")
        )
        val mc = JSONObject(body).optJSONObject("MediaContainer") ?: return@withContext emptyList()
        val arr = mc.optJSONArray("Metadata") ?: return@withContext emptyList()
        (0 until arr.length()).map { i -> parse(base, p.plexToken, arr.getJSONObject(i)) }
    }

    private fun parse(base: String, token: String, m: JSONObject): NowPlaying {
        val player = m.optJSONObject("Player") ?: JSONObject()
        val user = m.optJSONObject("User")?.str("title")
        val session = m.optJSONObject("Session")?.str("id")
        val type = m.optString("type")

        val title: String?
        val subtitle: String?
        val thumb: String?
        when (type) {
            "episode" -> {
                title = m.str("grandparentTitle") ?: m.str("title")
                val s = m.str("parentIndex")
                val e = m.str("index")
                val se = if (s != null && e != null) "S${s}E$e" else null
                subtitle = listOfNotNull(se, m.str("title")).joinToString(" · ")
                thumb = m.str("grandparentThumb") ?: m.str("thumb")
            }
            "track" -> {
                title = m.str("title")
                subtitle = listOfNotNull(m.str("grandparentTitle"), m.str("parentTitle")).joinToString(" · ")
                thumb = m.str("parentThumb") ?: m.str("thumb")
            }
            else -> {
                title = m.str("title")
                subtitle = m.str("year")
                thumb = m.str("thumb")
            }
        }

        val state = when (player.optString("state")) {
            "playing" -> PlayState.PLAYING
            "paused" -> PlayState.PAUSED
            "buffering" -> PlayState.BUFFERING
            else -> PlayState.IDLE
        }

        val art = thumb?.let {
            "$base/photo/:/transcode?width=320&height=320&minSize=1&upscale=1&url=" +
                URLEncoder.encode(it, "UTF-8") + "&X-Plex-Token=" + URLEncoder.encode(token, "UTF-8")
        }

        return NowPlaying(
            key = "plex:" + (session ?: player.str("machineIdentifier") ?: m.optString("ratingKey")),
            source = SourceType.PLEX,
            device = player.str("title") ?: player.str("product") ?: "Plex player",
            app = if (user != null) "Plex · $user" else "Plex",
            title = title,
            subtitle = subtitle,
            state = state,
            positionMs = m.optLong("viewOffset", -1).takeIf { it >= 0 },
            durationMs = m.optLong("duration", -1).takeIf { it > 0 },
            artUrl = art,
        )
    }
}
