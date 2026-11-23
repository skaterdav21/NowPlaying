package com.skaterdav21.nowplaying

object AppNames {
    private val known = mapOf(
        "com.google.android.youtube.tv" to "YouTube",
        "com.google.android.youtube.tvunplugged" to "YouTube TV",
        "com.google.android.youtube.tvmusic" to "YouTube Music",
        "com.liskovsoft.smarttubetv" to "SmartTube",
        "com.liskovsoft.smarttubetv.beta" to "SmartTube",
        "com.teamsmart.videomanager.tv" to "SmartTube",
        "com.netflix.ninja" to "Netflix",
        "com.plexapp.android" to "Plex",
        "com.disney.disneyplus" to "Disney+",
        "com.hulu.livingroomplus" to "Hulu",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.wbd.stream" to "Max",
        "com.hbo.hbonow" to "Max",
        "ar.tvplayer.tv" to "TiviMate",
        "ar.tvplayer.companion" to "TiviMate",
        "com.stremio.one" to "Stremio",
        "org.jellyfin.androidtv" to "Jellyfin",
        "com.spotify.tv.android" to "Spotify",
        "com.apple.atve.androidtv.appletv" to "Apple TV",
        "com.peacocktv.peacockandroid" to "Peacock",
        "com.cbs.ott" to "Paramount+",
        "org.xbmc.kodi" to "Kodi",
        "is.xyz.mpv" to "mpv",
        "org.videolan.vlc" to "VLC",
        "com.mxtech.videoplayer.ad" to "MX Player",
        "com.mxtech.videoplayer.pro" to "MX Player",
        "com.google.android.videos" to "Google TV",
        "tv.pluto.android" to "Pluto TV",
        "com.crunchyroll.crunchyroid" to "Crunchyroll",
        "com.espn.score_center" to "ESPN",
        "com.sling" to "Sling",
        "com.fubo.firetv.screen" to "Fubo",
        "com.nvidia.ota" to "System update",
        "com.android.tv.settings" to "Settings",
        "com.google.android.apps.mediashell" to "Chromecast",
        "com.google.android.katniss" to "Google Assistant",
        "com.google.android.apps.tv.dreamx" to "Screensaver",
        "com.google.android.backdrop" to "Screensaver",
    )

    /** Packages that mean "sitting on the home screen". */
    val launchers = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
        "com.spocky.projengmenu",
        "com.amazon.tv.launcher",
        "com.android.launcher3",
        "com.nvidia.shield.ask",
        "nl.ndsc.atvlauncher",
        "com.wolf.firelauncher",
        "com.google.android.apps.tv.dreamx",
        "com.google.android.backdrop",
    )

    private val noise = setOf(
        "com", "org", "net", "io", "de", "ar", "is", "xyz", "dev", "tv", "android", "androidtv",
        "app", "apps", "ott", "mobile", "player", "livingroom", "atv", "leanback", "firetv", "google"
    )

    fun label(pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        known[pkg]?.let { return it }
        if (pkg.contains("nuvio", ignoreCase = true)) return "Nuvio"
        if (pkg.contains("plex", ignoreCase = true)) return "Plex"
        val seg = pkg.split('.').lastOrNull { it.lowercase() !in noise } ?: return pkg
        return seg.replaceFirstChar { it.uppercase() }
    }
}
