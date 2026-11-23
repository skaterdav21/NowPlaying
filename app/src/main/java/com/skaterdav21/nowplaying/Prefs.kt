package com.skaterdav21.nowplaying

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun putB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun putS(k: String, v: String) = sp.edit().putString(k, v).apply()

    var serviceEnabled: Boolean
        get() = b("service_enabled", false); set(v) = putB("service_enabled", v)
    var startOnBoot: Boolean
        get() = b("start_on_boot", true); set(v) = putB("start_on_boot", v)
    var pauseScreenOff: Boolean
        get() = b("pause_screen_off", true); set(v) = putB("pause_screen_off", v)
    var wifiOnly: Boolean
        get() = b("wifi_only", true); set(v) = putB("wifi_only", v)
    var perDevice: Boolean
        get() = b("per_device", true); set(v) = putB("per_device", v)
    var showIdle: Boolean
        get() = b("show_idle", false); set(v) = putB("show_idle", v)
    var intervalSec: Int
        get() = sp.getInt("interval_sec", 10); set(v) = sp.edit().putInt("interval_sec", v).apply()

    var haEnabled: Boolean
        get() = b("ha_enabled", false); set(v) = putB("ha_enabled", v)
    var haUrl: String
        get() = s("ha_url", "http://192.168.0.8:8123"); set(v) = putS("ha_url", v.trim())
    var haToken: String
        get() = s("ha_token", ""); set(v) = putS("ha_token", v.trim())
    /** Comma/space/newline separated entity ids. Blank = all media_player entities. */
    var haEntities: String
        get() = s("ha_entities", ""); set(v) = putS("ha_entities", v)

    var plexEnabled: Boolean
        get() = b("plex_enabled", false); set(v) = putB("plex_enabled", v)
    var plexUrl: String
        get() = s("plex_url", "http://192.168.0.10:32400"); set(v) = putS("plex_url", v.trim())
    var plexToken: String
        get() = s("plex_token", ""); set(v) = putS("plex_token", v.trim())

    var devices: List<AdbDevice>
        get() {
            val arr = runCatching { JSONArray(s("adb_devices", "[]")) }.getOrElse { JSONArray() }
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                AdbDevice(o.optString("name"), o.optString("host"), o.optInt("port", 5555))
            }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("name", it.name).put("host", it.host).put("port", it.port)) }
            putS("adb_devices", arr.toString())
        }
}
