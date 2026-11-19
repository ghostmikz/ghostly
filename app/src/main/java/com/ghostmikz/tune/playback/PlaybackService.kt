package com.ghostmikz.tune.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ghostmikz.tune.MainActivity
import com.ghostmikz.tune.data.Prefs
import com.ghostmikz.tune.lastfm.ScrobbleManager
import com.ghostmikz.tune.lastfm.ScrobbleTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Plays music in the background, owns the notification / lock-screen controls and Last.fm scrobbling. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var scrobbler: ScrobbleManager

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        scrobbler = ScrobbleManager(scope, { Prefs.scrobble.value }, { Prefs.nowPlaying.value })

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                scrobbler.onTrackChanged(item?.toScrobbleTrack(), player.isPlaying)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                scrobbler.onPlayingChanged(isPlaying)
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scrobbler.destroy()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun MediaItem.toScrobbleTrack(): ScrobbleTrack? {
        val m = mediaMetadata
        val title = m.title?.toString() ?: return null
        val artist = m.artist?.toString()?.takeIf { it != "Unknown artist" } ?: return null
        return ScrobbleTrack(
            id = mediaId,
            title = title,
            artist = artist,
            album = m.albumTitle?.toString()?.takeIf { it != "Unknown album" },
            durationMs = m.durationMs ?: 0L,
        )
    }
}
