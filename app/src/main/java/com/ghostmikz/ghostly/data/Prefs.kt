package com.ghostmikz.ghostly.data

import android.content.Context
import android.content.SharedPreferences
import com.ghostmikz.ghostly.BuildConfig
import com.ghostmikz.ghostly.lastfm.LastFM
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small settings store shared by the UI and the playback service. */
object Prefs {
    private lateinit var sp: SharedPreferences

    private val _lastfmUser = MutableStateFlow("")
    val lastfmUser: StateFlow<String> = _lastfmUser
    private val _scrobble = MutableStateFlow(true)
    val scrobble: StateFlow<Boolean> = _scrobble
    private val _nowPlaying = MutableStateFlow(true)
    val nowPlaying: StateFlow<Boolean> = _nowPlaying

    /** True when this build has a Last.fm API key + secret baked in. */
    val lastfmAvailable: Boolean
        get() = BuildConfig.LASTFM_API_KEY.isNotBlank() && BuildConfig.LASTFM_SECRET.isNotBlank()

    val lastfmLinked: Boolean get() = !LastFM.sessionKey.isNullOrBlank()

    @Synchronized
    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("ghostly", Context.MODE_PRIVATE)
        _lastfmUser.value = sp.getString("lastfm_user", "").orEmpty()
        _scrobble.value = sp.getBoolean("scrobble", true)
        _nowPlaying.value = sp.getBoolean("now_playing", true)
        if (lastfmAvailable) LastFM.initialize(BuildConfig.LASTFM_API_KEY, BuildConfig.LASTFM_SECRET)
        LastFM.sessionKey = sp.getString("lastfm_session", null)?.takeIf { it.isNotBlank() }
    }

    fun linkLastfm(user: String, sessionKey: String) {
        LastFM.sessionKey = sessionKey
        _lastfmUser.value = user
        sp.edit().putString("lastfm_user", user).putString("lastfm_session", sessionKey).apply()
    }

    fun unlinkLastfm() {
        LastFM.sessionKey = null
        _lastfmUser.value = ""
        sp.edit().remove("lastfm_user").remove("lastfm_session").apply()
    }

    fun setScrobble(on: Boolean) {
        _scrobble.value = on
        sp.edit().putBoolean("scrobble", on).apply()
    }

    fun setNowPlaying(on: Boolean) {
        _nowPlaying.value = on
        sp.edit().putBoolean("now_playing", on).apply()
    }
}
