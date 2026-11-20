package com.ghostmikz.ghostly.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.guava.future
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import android.os.Bundle
import com.ghostmikz.ghostly.data.Playlists
import com.ghostmikz.ghostly.data.stats.ListeningRecorder
import com.ghostmikz.ghostly.data.stats.ListeningStats
import com.ghostmikz.ghostly.data.stats.toListeningSong
import com.ghostmikz.ghostly.widget.MediaWidget
import com.ghostmikz.ghostly.widget.MediaWidgetSnapshot
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ghostmikz.ghostly.BuildConfig
import com.ghostmikz.ghostly.MainActivity
import com.ghostmikz.ghostly.lastfm.LastFM
import com.ghostmikz.ghostly.data.History
import com.ghostmikz.ghostly.data.Prefs
import com.ghostmikz.ghostly.lastfm.ScrobbleManager
import com.ghostmikz.ghostly.lastfm.ScrobbleTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Session command sent by the 4x1 widget's star (BitChord's ACTION_TOGGLE_FAVORITE). */
const val ACTION_TOGGLE_FAVORITE = "com.ghostmikz.ghostly.action.TOGGLE_FAVORITE"

/** Session command sent by the 4x1 widget's Shuffle (BitChord's ACTION_TOGGLE_SHUFFLE). */
const val ACTION_TOGGLE_SHUFFLE = "com.ghostmikz.ghostly.action.TOGGLE_SHUFFLE"

/** Plays music in the background, owns the notification / lock-screen controls and Last.fm scrobbling. */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    /** What Android Auto browses (BitChord's MediaLibraryCallback tree). */
    private val autoLibrary by lazy { AutoLibrary(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** Only exists while Last.fm scrobbling is fully set up and on (BitChord). */
    private var scrobbler: ScrobbleManager? = null
    private var recorded = false

    /** The player the session is on; changes at every crossfade handoff. */
    private lateinit var player: ExoPlayer
    /** The standby the next track fades in on. */
    private lateinit var spare: ExoPlayer
    private var crossfade: CrossfadeController? = null
    private lateinit var playbackListener: Player.Listener // has the current track been added to History yet

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        History.init(this)
        com.ghostmikz.ghostly.data.Playlists.init(this)
        ListeningStats.init(this)
        com.ghostmikz.ghostly.ui.ArtCache.sizeFor(this)

        // Two identical players, as BitChord: the session's, and a standby the next
        // track fades in on (see CrossfadeController). They share one audio session,
        // so the system equalizer doesn't jump at a handoff, and only the session's
        // player takes audio focus and pauses on unplugged headphones.
        player = buildPlayer(owns = true)
        spare = buildPlayer(owns = false)
        spare.setAudioSessionId(player.audioSessionId)

        playbackListener = object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                // "After this song" (BitChord): the queue moving on by itself is the moment the
                // song finished. REPEAT counts too, or the timer never fires with repeat-one on.
                val ended = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                // The local record needs the transition even when the id doesn't change:
                // repeat-one plays the same song again, and that is a second play (BitChord).
                ListeningRecorder.onStopped()
                if (ended && SleepTimer.afterTrack.value) {
                    player.pause()
                    SleepTimer.cancel()
                }
                // Scrobbling: stop old song, start new song (BitChord).
                scrobbler?.onSongStop()
                if (player.isPlaying) {
                    scrobbler?.onSongStart(item?.toScrobbleTrack(), player.duration.takeIf { it > 0 })
                }
                recorded = false
                if (player.isPlaying) record(item)
                publishWidgetState()
                QueueStore.save(this@PlaybackService, player)
            }

            override fun onPlaybackStateChanged(state: Int) {
                // "After this song" on the last song: the queue just ends, with no
                // transition to catch, so the timer is done here instead of lingering
                // to pause some later song.
                if (state == Player.STATE_ENDED && SleepTimer.afterTrack.value) SleepTimer.cancel()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                publishWidgetState(playing = playWhenReady)
                if (!playWhenReady) QueueStore.save(this@PlaybackService, player)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publishWidgetState()

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    publishWidgetState()
                    QueueStore.save(this@PlaybackService, player)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) ListeningRecorder.onPaused()
                scrobbler?.onPlayerStateChanged(isPlaying, player.currentMediaItem?.toScrobbleTrack(), player.duration.takeIf { it > 0 })
                if (isPlaying) record(player.currentMediaItem)
            }

            private fun record(item: MediaItem?) {
                if (recorded) return
                item?.mediaId?.toLongOrNull()?.let { History.recordPlay(it); recorded = true }
            }
        }
        player.addListener(playbackListener)

        // Sleep timer (BitChord): pause once the deadline passes. collectLatest restarts the
        // wait when the timer is set again instead of both firing.
        scope.launch {
            SleepTimer.deadline.collectLatest { deadline ->
                if (deadline == null) return@collectLatest
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining > 0) kotlinx.coroutines.delay(remaining)
                player.pause()
                SleepTimer.cancel()
            }
        }

        // Replay's listening record (BitChord's reportProgress): ticks only while audio
        // is coming out; the recorder counts wall-clock time between ticks.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(ListeningRecorder.SAMPLE_MS)
                if (player.isPlaying) {
                    player.currentMediaItem?.toListeningSong()?.let { ListeningRecorder.onSample(it, player.duration) }
                }
            }
        }

        // Last.fm, set up and torn down as the settings change (BitChord's ScrobblingSnapshot).
        scope.launch {
            kotlinx.coroutines.flow.combine(
                listOf<kotlinx.coroutines.flow.Flow<Any>>(
                    Prefs.lastfmEnabled.state, Prefs.scrobbleOn.state, Prefs.nowPlayingOn.state,
                    Prefs.lastfmPrimaryArtistOnly.state, Prefs.lastfmUser, Prefs.scrobbleMinDuration.state,
                    Prefs.scrobbleDelayPercent.state, Prefs.scrobbleDelaySeconds.state,
                ),
            ) { it.toList() }.collectLatest {
                val sessionKey = LastFM.sessionKey
                val shouldEnable = Prefs.lastfmEnabled.value && Prefs.scrobbleOn.value &&
                    !sessionKey.isNullOrBlank() && Prefs.lastfmAvailable
                if (!shouldEnable) {
                    scrobbler?.destroy()
                    scrobbler = null
                    return@collectLatest
                }
                LastFM.configure(
                    endpoint = LastFM.DEFAULT_API_ENDPOINT,
                    apiKey = BuildConfig.LASTFM_API_KEY,
                    secret = BuildConfig.LASTFM_SECRET,
                    sessionKey = sessionKey,
                )
                val fresh = scrobbler == null
                val manager = scrobbler ?: ScrobbleManager(scope).also { scrobbler = it }
                manager.minSongDuration = Prefs.scrobbleMinDuration.value
                manager.scrobbleDelayPercent = Prefs.scrobbleDelayPercent.value
                manager.scrobbleDelaySeconds = Prefs.scrobbleDelaySeconds.value
                manager.useNowPlaying = Prefs.nowPlayingOn.value
                manager.usePrimaryArtistOnly = Prefs.lastfmPrimaryArtistOnly.value
                // Only a scrobbler that was just made is told what is playing; an existing
                // one is already timing this song, and restarting it would delay or repeat
                // the scrobble.
                if (fresh && player.isPlaying) {
                    manager.onPlayerStateChanged(true, player.currentMediaItem?.toScrobbleTrack(), player.duration.takeIf { it > 0 })
                }
            }
        }

        // Follow the "Skip silence" setting live.
        scope.launch { Prefs.skipSilence.collect { player.skipSilenceEnabled = it; spare.skipSilenceEnabled = it } }

        // Let the phone's equalizer (if it has one) attach to our audio.
        audioSessionId = player.audioSessionId
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val fader = CrossfadeController(scope, { player }, { spare }, ::adoptPlayer).also { it.start() }
        crossfade = fader
        session = MediaLibrarySession.Builder(this, SessionPlayer(player, fader), LibraryCallback())
            .setSessionActivity(openApp)
            .build()

        // A widget tap after the process died binds a fresh service: give it the last queue.
        scope.launch {
            QueueStore.restore(this@PlaybackService, player)
            publishWidgetState()
        }
        // Shuffle is a queue edit (BitChord's QueueShuffle), so the widget follows its state.
        scope.launch { QueueShuffle.enabled.collect { publishWidgetState(); QueueStore.save(this@PlaybackService, player) } }
        // A like from the app or the player moves the widget's star too.
        scope.launch { History.favorites.collect { publishWidgetState() } }
    }

    private fun buildPlayer(owns: Boolean): ExoPlayer = ExoPlayer.Builder(this)
        .setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ owns)
        .setHandleAudioBecomingNoisy(owns) // pause when headphones are unplugged
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()

    private fun setSessionOwner(target: ExoPlayer, owns: Boolean) {
        target.setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ owns)
        target.setHandleAudioBecomingNoisy(owns)
    }

    /**
     * BitChord's adoptPlayer: halfway through a crossfade the session moves to the
     * player the new track is playing on. That player started the track as its own
     * first item, so no transition reaches the listener; it is told by hand, or
     * history, scrobbling, Replay and the sleep timer would all miss the track.
     */
    private fun adoptPlayer(outgoing: ExoPlayer, incoming: ExoPlayer) {
        setSessionOwner(outgoing, owns = false)
        setSessionOwner(incoming, owns = true)
        outgoing.removeListener(playbackListener)
        player = incoming
        spare = outgoing
        incoming.addListener(playbackListener)
        crossfade?.let { session?.player = SessionPlayer(incoming, it) }
        playbackListener.onMediaItemTransition(incoming.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        playbackListener.onIsPlayingChanged(incoming.isPlaying)
    }

    /**
     * What controllers (the app, the notification, the widget) talk to: the
     * session's player, with every user skip or jump first ending any crossfade in
     * progress — a manual skip never crossfades (BitChord's SessionPlayer).
     */
    private class SessionPlayer(player: Player, private val crossfade: CrossfadeController) : ForwardingPlayer(player) {
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            crossfade.onSkipRequested()
            super.seekTo(mediaItemIndex, positionMs)
        }

        override fun seekToPreviousMediaItem() {
            crossfade.onSkipRequested()
            super.seekToPreviousMediaItem()
        }

        override fun seekToPrevious() {
            crossfade.onSkipRequested()
            super.seekToPrevious()
        }

        override fun seekToNextMediaItem() {
            crossfade.onSkipRequested()
            super.seekToNextMediaItem()
        }

        override fun seekToNext() {
            crossfade.onSkipRequested()
            super.seekToNext()
        }
    }

    /**
     * The widget's star and Shuffle (as BitChord's notification commands), and the
     * browse tree, search and play requests Android Auto sends (BitChord's
     * MediaLibraryCallback).
     */
    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            // Like and Shuffle are this app's own (its widget and UI); other apps that
            // connect for playback controls get the standard commands only.
            val own = controller.packageName == packageName
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().apply {
                if (own) {
                    add(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
                    add(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
                }
            }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = session.player
            when (customCommand.customAction) {
                ACTION_TOGGLE_FAVORITE -> player.currentMediaItem?.mediaId?.toLongOrNull()?.let { Playlists.toggleLike(it) }
                ACTION_TOGGLE_SHUFFLE -> QueueShuffle.toggle(player)
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val (root, rootParams) = autoLibrary.rootItem(params)
            return Futures.immediateFuture(LibraryResult.ofItem(root, rootParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val (items, returnParams) = autoLibrary.children(parentId, page, pageSize)
            LibraryResult.ofItemList(ImmutableList.copyOf(items), returnParams)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
            autoLibrary.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future(Dispatchers.IO) {
            val count = autoLibrary.search(query).size
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val all = autoLibrary.search(query)
            val from = (page.coerceAtLeast(0) * pageSize.coerceAtLeast(1)).coerceAtMost(all.size)
            val to = (from + pageSize.coerceAtLeast(1)).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            val (resolved, start) = autoLibrary.resolve(mediaItems, startIndex)
            // Played from a car or a voice command: a fresh list, so shuffle as a
            // queue edit starts off, and the player's own shuffle stays off.
            if (resolved !== mediaItems) QueueShuffle.setEnabled(false)
            MediaSession.MediaItemsWithStartPosition(
                ImmutableList.copyOf(resolved),
                start,
                if (resolved === mediaItems) startPositionMs.coerceAtLeast(0L) else 0L,
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future(Dispatchers.IO) {
            // The app's own adds already carry their file; only a browser's need resolving.
            if (mediaItems.all { it.localConfiguration != null }) mediaItems
            else mediaItems.flatMap { autoLibrary.resolve(listOf(it), 0).first }
        }
    }

    /** Writes what the widgets show and redraws them (BitChord's publishWidgetState). */
    private fun publishWidgetState(playing: Boolean? = null) {
        val player = session?.player ?: return
        val item = player.currentMediaItem
        if (item == null) {
            if (player.mediaItemCount == 0 && MediaWidgetSnapshot.load(this).hasTrack) return
            MediaWidgetSnapshot.save(this, MediaWidgetSnapshot.EMPTY)
            MediaWidget.refresh(this)
            return
        }
        val id = item.mediaId
        val albumId = item.mediaMetadata.artworkUri?.lastPathSegment
        val snapshot = MediaWidgetSnapshot(
            mediaId = id,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            artworkUrl = albumId?.let { "$id:$it" },
            // playWhenReady, not isPlaying -- see MediaWidgetSnapshot.isPlaying.
            isPlaying = playing ?: player.playWhenReady,
            hasPrevious = player.hasPreviousMediaItem(),
            hasNext = player.hasNextMediaItem(),
            isLiked = id.toLongOrNull()?.let { it in History.favorites.value } ?: false,
            canLike = id.toLongOrNull() != null,
            shuffleEnabled = QueueShuffle.enabled.value,
        )
        MediaWidgetSnapshot.save(this, snapshot)
        MediaWidget.refresh(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        player?.let { QueueStore.save(this, it) }
        // "Stop music on close from recents", or nothing is playing anyway.
        if (player == null || Prefs.stopOnClose.value || !player.playWhenReady || player.mediaItemCount == 0) {
            player?.pause()
            stopSelf()
        }
    }

    companion object {
        /** The player's audio session, for opening the system equalizer from Settings. */
        @Volatile var audioSessionId: Int = 0

        private val AUDIO_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }

    override fun onDestroy() {
        crossfade?.release()
        crossfade = null
        scrobbler?.destroy()
        scrobbler = null
        scope.cancel()
        ListeningRecorder.onStopped()
        session?.player?.let { QueueStore.save(this, it) }
        publishWidgetState(playing = false)
        // Tell the system equalizer this audio session is gone.
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName),
        )
        session?.run {
            player.release()
            release()
        }
        spare.release()
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
