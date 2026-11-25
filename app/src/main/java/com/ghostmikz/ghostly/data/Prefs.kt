package com.ghostmikz.ghostly.data

import android.content.Context
import android.content.SharedPreferences
import com.ghostmikz.ghostly.BuildConfig
import com.ghostmikz.ghostly.lastfm.LastFM
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** One on/off setting: readable as a flow by the UI and the playback service. */
class BoolPref internal constructor(private val key: String, private val default: Boolean, internal val onDevice: Boolean = false) {
    private val flow = MutableStateFlow(default)
    val state: StateFlow<Boolean> = flow
    val value: Boolean get() = flow.value

    internal fun load(sp: SharedPreferences) { flow.value = sp.getBoolean(key, default) }

    fun set(on: Boolean) {
        flow.value = on
        Prefs.edit(onDevice) { putBoolean(key, on) }
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

/** One choice among an enum's values, saved by name (BitChord's output settings). */
class EnumPref<E : Enum<E>> internal constructor(
    private val key: String,
    private val default: E,
    private val values: List<E>,
    internal val onDevice: Boolean = false,
) {
    private val flow = MutableStateFlow(default)
    val state: StateFlow<E> = flow
    val value: E get() = flow.value

    internal fun load(sp: SharedPreferences) {
        // Defensive: an older import could have stored this key as a Boolean,
        // and getString on that throws, which would crash every launch.
        val saved = runCatching { sp.getString(key, null) }.getOrNull()
        flow.value = values.firstOrNull { it.name == saved } ?: default
    }

    fun set(v: E) {
        flow.value = v
        Prefs.edit(onDevice) { putString(key, v.name) }
    }
}

/**
 * The PCM handed to Android's AudioTrack (BitChord). Media3 opens either a
 * 16-bit or a float track, so these are the only two; float is what carries a
 * 24-bit file's extra depth through, on outputs that take it.
 */
enum class OutputPcmMode(val label: String) {
    PCM_16("16-bit PCM"),
    FLOAT_32("32-bit float"),
}

/**
 * Which exclusive output, if any, a track is sent to unchanged (BitChord).
 * Declared in the order the settings picker lists them; saved by name.
 *
 * Bit-perfect output bypasses crossfade, playback speed, skip silence and
 * software volume: any of them would change the samples. Volume belongs to
 * the DAC. See [com.ghostmikz.ghostly.playback.bitperfect.BitPerfectOutput].
 */
enum class BitPerfectMode {
    OFF,

    /** BitChord's own USB Audio Class driver on libusb: any Android version, volume set on the DAC. */
    USB_DIRECT,

    /** An AAudio exclusive stream to a built-in DAC, for digital audio players. */
    DAP_EXCLUSIVE,

    /** Android 14's bit-perfect mixer for a USB DAC. */
    USB_ANDROID,
}

/** Small settings store shared by the UI and the playback service. */
object Prefs {
    private lateinit var sp: SharedPreferences
    private lateinit var account: SharedPreferences
    /**
     * Settings that only mean something on this phone (or must never come back
     * without their warning), in a file the backup rules leave out.
     */
    private lateinit var device: SharedPreferences
    private const val ACCOUNT_FILE = "ghostly_account"
    private const val DEVICE_FILE = "ghostly_device"

    private val _lastfmUser = MutableStateFlow("")
    val lastfmUser: StateFlow<String> = _lastfmUser

    /** BitChord's LocalMusicSort for the library's songs, kept until changed. */
    private val _localSort = MutableStateFlow(LocalMusicSort.TITLE_ASC)
    val localSort: StateFlow<LocalMusicSort> = _localSort
    fun setLocalSort(order: LocalMusicSort) {
        _localSort.value = order
        edit { putString("local_music_sort", order.name) }
    }

    /**
     * The folder the listener granted for lyrics files (a document tree URI), or
     * blank. Only good on this phone, so never in a backup.
     */
    private val _lyricsFolder = MutableStateFlow("")
    val lyricsFolder: StateFlow<String> = _lyricsFolder
    fun setLyricsFolder(uri: String) {
        _lyricsFolder.value = uri
        edit(onDevice = true) { putString("lyrics_folder", uri) }
    }

    /**
     * Songs hidden from the library (Settings > Local Music > Duplicates). Kept by
     * MediaStore id, which only means something on this phone, so never in a backup.
     */
    private val _hiddenSongs = MutableStateFlow<Set<Long>>(emptySet())
    val hiddenSongs: StateFlow<Set<Long>> = _hiddenSongs
    fun hideSongs(ids: Collection<Long>) = setHiddenSongs(_hiddenSongs.value + ids)
    fun unhideSongs(ids: Collection<Long>) = setHiddenSongs(_hiddenSongs.value - ids.toSet())
    private fun setHiddenSongs(ids: Set<Long>) {
        _hiddenSongs.value = ids
        edit(onDevice = true) { putString("hidden_songs", ids.joinToString(",")) }
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
    // Audio output, as BitChord's AppSettings
    val outputPcmMode = EnumPref("output_pcm_mode", OutputPcmMode.PCM_16, OutputPcmMode.entries)
    /** Prefer an attached USB audio output over the system's normal route. */
    val preferUsbDac = BoolPref("prefer_usb_dac", false)
    /** Unprocessed output to a DAC; see [BitPerfectMode]. */
    val bitPerfectMode = EnumPref("bit_perfect_mode", BitPerfectMode.OFF, BitPerfectMode.entries, onDevice = true)
    /**
     * Lets direct USB play to a DAC with no volume control Ghostly can set: full
     * scale, with the DAC's own knob (if any) the only volume. Off unless the
     * listener accepts a second warning.
     */
    val usbAllowFixedVolume = BoolPref("usb_allow_fixed_volume", false, onDevice = true)
    /**
     * Lets the exclusive output convert a track its DAC can't take as it is, to
     * the DAC's highest format. Off, such a track goes to normal output and no
     * sample on the exclusive path is ever changed.
     */
    val convertForLimitedDac = BoolPref("convert_for_limited_dac", false)
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
        preferUsbDac, usbAllowFixedVolume, convertForLimitedDac,
    )
    private val choices = listOf(outputPcmMode, bitPerfectMode)

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
        device = context.applicationContext.getSharedPreferences(DEVICE_FILE, Context.MODE_PRIVATE)
        moveDeviceSettings(context)
        reload()
        if (lastfmAvailable) LastFM.initialize(BuildConfig.LASTFM_API_KEY, BuildConfig.LASTFM_SECRET)
    }

    /**
     * Older installs kept the phone-only settings in the main file, which a cloud
     * backup carries to a new phone. Moved out once. Full-scale DAC output isn't
     * moved but reset to off, since it may have come from another phone's backup:
     * it goes back on only behind its warning. A lyrics folder is kept only if
     * this phone still holds the permission to read it.
     */
    private fun moveDeviceSettings(context: Context) {
        val old = DEVICE_KEYS.filter { sp.contains(it) }
        if (old.isEmpty()) return
        val granted = context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        val moved = device.edit()
        runCatching { sp.getString("hidden_songs", null) }.getOrNull()?.let { moved.putString("hidden_songs", it) }
        runCatching { sp.getString("lyrics_folder", null) }.getOrNull()
            ?.takeIf { it in granted }?.let { moved.putString("lyrics_folder", it) }
        moved.apply()
        sp.edit().apply { old.forEach { remove(it) } }.apply()
    }

    private fun reload() {
        _lastfmUser.value = account.getString("lastfm_user", "").orEmpty()
        _theme.value = sp.getString("theme", "system") ?: "system"
        // Defensive, like the enum settings: a stray import must not crash every launch.
        _lyricsFolder.value = runCatching { device.getString("lyrics_folder", "") }.getOrNull() ?: ""
        _hiddenSongs.value = (runCatching { device.getString("hidden_songs", "") }.getOrNull() ?: "")
            .split(',').mapNotNull { it.toLongOrNull() }.toSet()
        _localSort.value = sp.getString("local_music_sort", null)
            ?.let { saved -> LocalMusicSort.entries.firstOrNull { it.name == saved } } ?: LocalMusicSort.TITLE_ASC
        all.forEach { it.load(if (it.onDevice) device else sp) }
        numbers.forEach { it.load(sp) }
        floats.forEach { it.load(sp) }
        choices.forEach { it.load(if (it.onDevice) device else sp) }
        if (!scrobbleOn.value && nowPlayingOn.value) nowPlayingOn.set(false)
        LastFM.sessionKey = account.getString("lastfm_session", null)?.takeIf { it.isNotBlank() }
    }

    internal fun edit(onDevice: Boolean = false, block: SharedPreferences.Editor.() -> Unit) =
        (if (onDevice) device else sp).edit().apply(block).apply()

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

    /**
     * Never carried by a backup (they live in [DEVICE_FILE]): the DAC two turn on
     * full-scale output, which only happens behind its own warning on this phone,
     * and the other two are this phone's folder grant and MediaStore ids.
     */
    private val DEVICE_KEYS = setOf("bit_perfect_mode", "usb_allow_fixed_volume", "lyrics_folder", "hidden_songs")
    private val NOT_BACKED_UP = DEVICE_KEYS

    /** Settings as JSON for "Export data". The Last.fm session is left out on purpose. */
    fun exportJson(): JSONObject {
        val o = JSONObject()
        sp.all.forEach { (k, v) ->
            if ((v is Boolean || v is Int || v is Float || k == "theme" || k == "local_music_sort" || k == "output_pcm_mode") &&
                !k.startsWith("lastfm_user") && !k.startsWith("lastfm_session") && k !in NOT_BACKED_UP
            ) o.put(k, v)
        }
        return o
    }

    fun importJson(o: JSONObject) {
        edit {
            o.keys().forEach { k ->
                when {
                    k.startsWith("lastfm_user") || k.startsWith("lastfm_session") || k in NOT_BACKED_UP -> Unit
                    k == "theme" -> putString(k, o.optString(k, "system"))
                    k == "local_music_sort" || k == "output_pcm_mode" -> putString(k, o.optString(k))
                    k == "scrobble_delay_percent" -> putFloat(k, o.optDouble(k, 0.5).toFloat())
                    o.opt(k) is Int -> putInt(k, o.optInt(k))
                    else -> putBoolean(k, o.optBoolean(k))
                }
            }
        }
        reload()
    }
}
