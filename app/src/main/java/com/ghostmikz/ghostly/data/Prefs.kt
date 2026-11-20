package com.ghostmikz.ghostly.data

import android.content.Context
import android.content.SharedPreferences
import com.ghostmikz.ghostly.BuildConfig
import com.ghostmikz.ghostly.lastfm.LastFM
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** One on/off setting: readable as a flow by the UI and the playback service. */
class BoolPref internal constructor(private val key: String, private val default: Boolean) {
    private val flow = MutableStateFlow(default)
    val state: StateFlow<Boolean> = flow
    val value: Boolean get() = flow.value

    internal fun load(sp: SharedPreferences) { flow.value = sp.getBoolean(key, default) }

    fun set(on: Boolean) {
        flow.value = on
        Prefs.edit { putBoolean(key, on) }
    }
}

/** One number setting (BitChord's scrobble timing sliders). */
class IntPref internal constructor(private val key: String, private val default: Int) {
    private val flow = MutableStateFlow(default)
    val state: StateFlow<Int> = flow
    val value: Int get() = flow.value

    internal fun load(sp: SharedPreferences) { flow.value = sp.getInt(key, default) }

    fun set(v: Int) {
        flow.value = v
        Prefs.edit { putInt(key, v) }
    }
}

class FloatPref internal constructor(private val key: String, private val default: Float) {
    private val flow = MutableStateFlow(default)
    val state: StateFlow<Float> = flow
    val value: Float get() = flow.value

    internal fun load(sp: SharedPreferences) { flow.value = sp.getFloat(key, default) }

    fun set(v: Float) {
        flow.value = v
        Prefs.edit { putFloat(key, v) }
    }
}

/** Small settings store shared by the UI and the playback service. */
object Prefs {
    private lateinit var sp: SharedPreferences
    private lateinit var account: SharedPreferences
    private const val ACCOUNT_FILE = "ghostly_account"

    private val _lastfmUser = MutableStateFlow("")
    val lastfmUser: StateFlow<String> = _lastfmUser

    /** BitChord's LocalMusicSort for the library's songs, kept until changed. */
    private val _localSort = MutableStateFlow(LocalMusicSort.TITLE_ASC)
    val localSort: StateFlow<LocalMusicSort> = _localSort
    fun setLocalSort(order: LocalMusicSort) {
        _localSort.value = order
        edit { putString("local_music_sort", order.name) }
    }

    /** "system", "light" or "dark" (Settings > Appearance > Theme). */
    private val _theme = MutableStateFlow("system")
    val theme: StateFlow<String> = _theme
    fun setTheme(mode: String) {
        _theme.value = mode
        edit { putString("theme", mode) }
    }

    // Account & integrations, as BitChord's AppSettings: Last.fm on, then what it does.
    // Scrobbling and Now playing start off after sign-in, as in BitChord.
    val lastfmEnabled = BoolPref("lastfm_enabled", false)
    val scrobbleOn = BoolPref("lastfm_scrobble_enabled", false)
    val nowPlayingOn = BoolPref("lastfm_now_playing", false)
    val lastfmPrimaryArtistOnly = BoolPref("lastfm_primary_artist_only", false)
    val scrobbleMinDuration = IntPref("scrobble_min_duration", 30)
    val scrobbleDelayPercent = FloatPref("scrobble_delay_percent", 0.5f)
    val scrobbleDelaySeconds = IntPref("scrobble_delay_seconds", 180)
    // Playback
    val skipSilenceOn = BoolPref("skip_silence", false)
    /** BitChord's crossfade, in whole seconds; 0 is off (the default). */
    val crossfadeSeconds = IntPref("crossfade_seconds", 0)
    // Appearance
    val reduceAnimation = BoolPref("reduce_animation", false)
    val reduceBlur = BoolPref("reduce_blur", false)
    val noBlend = BoolPref("no_blend", false)
    val fullCover = BoolPref("full_cover", false)
    val fullAlbumCover = BoolPref("full_album_cover", false)
    val liquidGlass = BoolPref("liquid_glass", false)
    val glassPerformance = BoolPref("glass_performance", false)
    // Local music
    val filterNonMusic = BoolPref("filter_non_music", true)
    // Miscellaneous
    val swipePlayNext = BoolPref("swipe_play_next", true)
    val stopOnClose = BoolPref("stop_on_close", false)
    val hideVolume = BoolPref("hide_volume", false)
    val hideStatusBar = BoolPref("hide_status_bar", false)
    val queueHistory = BoolPref("queue_history", true)
    val hideSongStatus = BoolPref("hide_song_status", false)
    val nerdStats = BoolPref("nerd_stats", false)

    private val all = listOf(
        lastfmEnabled, scrobbleOn, nowPlayingOn, lastfmPrimaryArtistOnly, skipSilenceOn, reduceAnimation, reduceBlur, noBlend, fullCover, fullAlbumCover,
        liquidGlass, glassPerformance, filterNonMusic, swipePlayNext, stopOnClose, hideVolume, hideStatusBar, queueHistory, hideSongStatus, nerdStats,
    )

    private val numbers = listOf(scrobbleMinDuration, scrobbleDelaySeconds, crossfadeSeconds)
    private val floats = listOf(scrobbleDelayPercent)

    // Older names kept so the rest of the app reads naturally.
    val scrobble: StateFlow<Boolean> get() = scrobbleOn.state
    val nowPlaying: StateFlow<Boolean> get() = nowPlayingOn.state
    val skipSilence: StateFlow<Boolean> get() = skipSilenceOn.state
    /** Turning scrobbling off turns Now playing off with it (BitChord). */
    fun setScrobble(on: Boolean) {
        scrobbleOn.set(on)
        if (!on) nowPlayingOn.set(false)
    }

    /** Now playing only goes on while scrobbling is on (BitChord). */
    fun setNowPlaying(on: Boolean) {
        if (on && !scrobbleOn.value) return
        nowPlayingOn.set(on)
    }
    fun setSkipSilence(on: Boolean) = skipSilenceOn.set(on)

    /** True when this build has a Last.fm API key + secret baked in. */
    val lastfmAvailable: Boolean
        get() = BuildConfig.LASTFM_API_KEY.isNotBlank() && BuildConfig.LASTFM_SECRET.isNotBlank()

    @Synchronized
    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("ghostly", Context.MODE_PRIVATE)
        // The Last.fm sign-in lives in its own file, which the backup rules leave
        // out of Google's cloud backup (res/xml/backup_rules.xml). Moved there
        // from the main file once, for installs that had it there.
        account = context.applicationContext.getSharedPreferences(ACCOUNT_FILE, Context.MODE_PRIVATE)
        if (sp.contains("lastfm_session")) {
            account.edit()
                .putString("lastfm_user", sp.getString("lastfm_user", ""))
                .putString("lastfm_session", sp.getString("lastfm_session", ""))
                .apply()
            sp.edit().remove("lastfm_user").remove("lastfm_session").apply()
        }
        reload()
        if (lastfmAvailable) LastFM.initialize(BuildConfig.LASTFM_API_KEY, BuildConfig.LASTFM_SECRET)
    }

    private fun reload() {
        _lastfmUser.value = account.getString("lastfm_user", "").orEmpty()
        _theme.value = sp.getString("theme", "system") ?: "system"
        _localSort.value = sp.getString("local_music_sort", null)
            ?.let { saved -> LocalMusicSort.entries.firstOrNull { it.name == saved } } ?: LocalMusicSort.TITLE_ASC
        all.forEach { it.load(sp) }
        numbers.forEach { it.load(sp) }
        floats.forEach { it.load(sp) }
        if (!scrobbleOn.value && nowPlayingOn.value) nowPlayingOn.set(false)
        LastFM.sessionKey = account.getString("lastfm_session", null)?.takeIf { it.isNotBlank() }
    }

    internal fun edit(block: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()

    fun linkLastfm(user: String, sessionKey: String) {
        LastFM.sessionKey = sessionKey
        _lastfmUser.value = user
        account.edit().putString("lastfm_user", user).putString("lastfm_session", sessionKey).apply()
        lastfmEnabled.set(true)
    }

    fun unlinkLastfm() {
        LastFM.sessionKey = null
        _lastfmUser.value = ""
        account.edit().remove("lastfm_user").remove("lastfm_session").apply()
        lastfmEnabled.set(false)
        scrobbleOn.set(false)
        nowPlayingOn.set(false)
    }

    /** Settings as JSON for "Export data". The Last.fm session is left out on purpose. */
    fun exportJson(): JSONObject {
        val o = JSONObject()
        sp.all.forEach { (k, v) -> if ((v is Boolean || v is Int || v is Float || k == "theme" || k == "local_music_sort") && !k.startsWith("lastfm_user") && !k.startsWith("lastfm_session")) o.put(k, v) }
        return o
    }

    fun importJson(o: JSONObject) {
        edit {
            o.keys().forEach { k ->
                when {
                    k.startsWith("lastfm_user") || k.startsWith("lastfm_session") -> Unit
                    k == "theme" -> putString(k, o.optString(k, "system"))
                    k == "local_music_sort" -> putString(k, o.optString(k))
                    k == "scrobble_delay_percent" -> putFloat(k, o.optDouble(k, 0.5).toFloat())
                    o.opt(k) is Int -> putInt(k, o.optInt(k))
                    else -> putBoolean(k, o.optBoolean(k))
                }
            }
        }
        reload()
    }
}
