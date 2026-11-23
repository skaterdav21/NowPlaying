# Now Playing

Android app that pins a persistent notification showing what's playing on every TV on your home network.

**Sources**
- **Android TV over ADB** (Shield, onn 4K Pro, Chromecast with Google TV): reads `dumpsys media_session`, so it works with any app that publishes a media session (Nuvio, YouTube/SmartTube, Netflix, Plex, TiviMate, Stremio, Kodi). Play/pause, rewind and fast-forward buttons send key events.
- **Home Assistant** `media_player` entities (LG webOS, Cast, Spotify...). Prev / play-pause / next call HA services.
- **Plex** `/status/sessions` with poster, user and progress bar.

**Notification**
- One persistent summary card (foreground service) listing every device.
- Optional per-device cards with artwork, progress and transport controls.
- Pauses while the screen is off and off Wi-Fi (both toggleable); refreshes instantly on unlock.

**Build**: every push to `main` builds a signed APK and publishes it to the `latest` release.
