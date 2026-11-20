package com.ghostmikz.ghostly.playback

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.ghostmikz.ghostly.data.BitPerfectMode
import com.ghostmikz.ghostly.data.OutputPcmMode
import com.ghostmikz.ghostly.playback.bitperfect.BitPerfectAudioSink
import com.ghostmikz.ghostly.playback.bitperfect.BitPerfectOutput
import com.ghostmikz.ghostly.playback.bitperfect.BitPerfectRoutingSink
import com.ghostmikz.ghostly.playback.bitperfect.DacVolume
import com.ghostmikz.ghostly.playback.bitperfect.usb.UsbDirectDevices
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt
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
@SuppressLint("UnsafeOptInUsageError")
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

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    /**
     * Whether the players' sinks were built for a float AudioTrack (BitChord).
     * Media3 fixes it when a player is built, so a change rebuilds both.
     */
    private var configuredFloatOutput = false
    private var outputReconfigureJob: Job? = null
    /** Each player's last opened AudioTrack, for the Output precision row. */
    private val trackConfigs = java.util.WeakHashMap<ExoPlayer, AudioSink.AudioTrackConfig>()
    private val outputDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = onOutputsChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = onOutputsChanged()

        private fun onOutputsChanged() {
            // Ghostly's own USB driver takes the DAC from Android while it plays, so
            // Android sees the DAC go and come back. That is not the listener
            // changing outputs: re-deciding float on it would rebuild the player
            // that just opened the DAC, over and over.
            if (BitPerfectOutput.holdsDirectUsb(this@PlaybackService)) return
            requestOutputReconfiguration()
        }
    }

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
        configuredFloatOutput = shouldEnableFloatOutput()
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
                // The crossfade holds the outgoing track at its end; that is not a pause.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                    crossfade?.isTransitioning() == true
                ) return
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
        observeOutputSettings()

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
        val fader = newCrossfade()
        session = MediaLibrarySession.Builder(this, SessionPlayer(player, fader), LibraryCallback())
            .setSessionActivity(openApp)
            .build()

        applyOutputRoute()

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

    private fun buildPlayer(owns: Boolean): ExoPlayer = ExoPlayer.Builder(this, renderers())
        .setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ owns)
        .setHandleAudioBecomingNoisy(owns) // pause when headphones are unplugged
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
        .also { built ->
            built.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                    trackConfigs[built] = audioTrackConfig
                    if (built === player) publishOutputStatus()
                }
            })
        }

    private fun newCrossfade(): CrossfadeController {
        val fader = CrossfadeController(scope, { player }, { spare }, ::adoptPlayer).also { it.start() }
        // A shuffle toggled as the next track becomes imminent keeps that track in place.
        QueueShuffle.isCrossfadeTransitioning = fader::isTransitioning
        crossfade = fader
        return fader
    }

    /**
     * BitChord's renderers: float output only where [shouldEnableFloatOutput]
     * allows it, a silence skipper that leaves pauses under a second alone, and
     * in front of it all the bit-perfect sink, which takes the tracks the
     * selected exclusive output can carry unchanged.
     */
    private fun renderers() = object : DefaultRenderersFactory(this) {
        init {
            // Float is what *preserves* a hi-res source rather than what
            // embellishes it: it is the only AudioTrack encoding Media3 will open
            // that carries a 24-bit sample intact.
            setEnableAudioFloatOutput(configuredFloatOutput)
            // c2.sec.flac.decoder produces one extra 232.2ms timestamp advance per
            // decoded buffer when Media3 requests float PCM, so FLAC goes through
            // the platform software codec on affected Samsung devices.
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val selector = if (mimeType == MimeTypes.AUDIO_FLAC) MediaCodecSelector.PREFER_SOFTWARE else MediaCodecSelector.DEFAULT
                val candidates = selector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                if (mimeType == MimeTypes.AUDIO_FLAC) {
                    candidates.filterNot { AudioOutputPolicy.isUnsafeFloatFlacDecoder(it.name) }
                } else {
                    candidates
                }
            }
        }

        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
            val normal = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                // Media3's float branch drops Sonic, so speed is done by the
                // AudioTrack itself there. Silence skipping is in that same dropped
                // chain and has no such escape hatch: on float it does nothing.
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams || enableFloatOutput)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        emptyArray<AudioProcessor>(),
                        SilenceSkippingAudioProcessor(
                            MIN_SILENCE_US,
                            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
                            SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
                            SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
                            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
                        ),
                        SonicAudioProcessor(),
                    ),
                )
                .build()
            return BitPerfectRoutingSink(normal, BitPerfectAudioSink(BitPerfectOutput.forContext(context)))
        }
    }

    /** The output the music goes to: a USB DAC when preferred and attached, else Android's choice. */
    private fun activeOutputDevice(): AudioDeviceInfo? {
        val manager = audioManager ?: return null
        return AudioRoute.usbOutput(manager).takeIf { Prefs.preferUsbDac.value } ?: AudioRoute.systemDefault(manager)
    }

    private fun shouldEnableFloatOutput(): Boolean {
        val device = activeOutputDevice()
        val kind = device?.let { AudioRoute.kindOf(it.type) } ?: AudioRoute.Kind.PHONE
        return AudioOutputPolicy.shouldUseFloatOutput(Prefs.outputPcmMode.value, kind, AudioRoute.takesFloat(device))
    }

    /**
     * The USB-DAC preference through Android's public routing API (BitChord). This
     * does not bypass the system USB driver: Android still opens only formats the
     * DAC advertises.
     */
    private fun applyOutputRoute() {
        val manager = audioManager ?: return
        val preferred = AudioRoute.usbOutput(manager).takeIf { Prefs.preferUsbDac.value }
        player.setPreferredAudioDevice(preferred)
        spare.setPreferredAudioDevice(preferred)
        publishOutputStatus()
    }

    private fun publishOutputStatus() {
        val device = activeOutputDevice()
        val config = trackConfigs[player]
        val requested = Prefs.outputPcmMode.value
        OutputStatus.update {
            OutputStatus.Snapshot(
                deviceName = AudioRoute.label(device),
                isUsb = device?.let { d -> AudioRoute.kindOf(d.type) } == AudioRoute.Kind.USB,
                sampleRateHz = config?.sampleRate,
                encoding = config?.encoding,
                requested = requested,
                floatFallback = requested == OutputPcmMode.FLOAT_32 && !configuredFloatOutput,
            )
        }
    }

    private fun requestOutputReconfiguration(rebuild: Boolean = false) {
        outputReconfigureJob?.cancel()
        outputReconfigureJob = scope.launch {
            // Replacing two renderers during a blend would cut one half of it.
            // Wait for the short transition to settle, then swap the engine.
            while (crossfade?.isTransitioning() == true) delay(50)
            val requestedFloat = shouldEnableFloatOutput()
            if (requestedFloat == configuredFloatOutput && !rebuild) {
                applyOutputRoute()
            } else {
                rebuildPlayersForOutput(requestedFloat)
            }
        }
    }

    /**
     * Rebuilds Media3's fixed AudioSink setup without restarting the service
     * (BitChord). Queue, item, position and play state move to the new player; the
     * old AudioTrack is released only after the session points at the new one.
     */
    private fun rebuildPlayersForOutput(enableFloat: Boolean) {
        val oldActive = player
        val oldSpare = spare
        val items = List(oldActive.mediaItemCount) { oldActive.getMediaItemAt(it) }
        val index = oldActive.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: 0
        val position = oldActive.currentPosition.coerceAtLeast(0L)
        val playWhenReady = oldActive.playWhenReady
        val repeatMode = oldActive.repeatMode
        val oldSessionId = oldActive.audioSessionId

        oldActive.playWhenReady = false
        crossfade?.release()
        oldActive.removeListener(playbackListener)

        configuredFloatOutput = enableFloat
        val newActive = buildPlayer(owns = true)
        val newSpare = buildPlayer(owns = false)
        newSpare.setAudioSessionId(newActive.audioSessionId)
        player = newActive
        spare = newSpare
        newActive.skipSilenceEnabled = Prefs.skipSilence.value
        newSpare.skipSilenceEnabled = Prefs.skipSilence.value
        newActive.repeatMode = repeatMode
        if (items.isNotEmpty()) newActive.setMediaItems(items, index.coerceIn(items.indices), position)
        newActive.addListener(playbackListener)

        val fader = newCrossfade()
        session?.player = SessionPlayer(newActive, fader)
        applyOutputRoute()
        if (items.isNotEmpty()) newActive.prepare()
        newActive.playWhenReady = playWhenReady

        oldActive.release()
        oldSpare.release()
        // The phone's equalizer follows the new audio session.
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, oldSessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName),
        )
        audioSessionId = newActive.audioSessionId
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, newActive.audioSessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
    }

    /** BitChord's observeSettings, for the output rows. */
    private fun observeOutputSettings() {
        audioManager?.registerAudioDeviceCallback(outputDeviceCallback, null)
        scope.launch { Prefs.preferUsbDac.state.drop(1).collect { requestOutputReconfiguration() } }
        scope.launch { Prefs.outputPcmMode.state.drop(1).collect { requestOutputReconfiguration() } }
        // Direct USB needs Android's permission to the DAC, asked for when the
        // mode is chosen and again whenever a DAC is attached; an answer, an
        // attach or a detach all send the playing track back to be re-routed.
        UsbDirectDevices.init(this)
        BitPerfectOutput.watch(this)
        // Unplugging the DAC direct USB plays to pauses, as unplugged headphones
        // do; Android can't say "becoming noisy" for a DAC it wasn't driving.
        BitPerfectOutput.onDirectDacLost = { scope.launch { session?.player?.pause() } }
        scope.launch {
            var available = false
            DacVolume.level.collect { level ->
                val now = level != null
                (session?.player as? SessionPlayer)?.onDacVolumeChanged(routeChanged = now != available)
                available = now
            }
        }
        UsbDirectDevices.onChanged = {
            BitPerfectOutput.refreshReady(this)
            scope.launch {
                if (Prefs.bitPerfectMode.value == BitPerfectMode.USB_DIRECT) {
                    UsbDirectDevices.requestPermission(this@PlaybackService)
                    requestOutputReconfiguration(rebuild = true)
                }
            }
        }
        scope.launch {
            // The sinks decide per format, so the playing track only moves on or
            // off the exclusive output when its sink is configured again.
            Prefs.bitPerfectMode.state.collect { mode ->
                BitPerfectOutput.refreshReady(this@PlaybackService)
                if (mode == BitPerfectMode.USB_DIRECT) UsbDirectDevices.requestPermission(this@PlaybackService)
            }
        }
        scope.launch { Prefs.bitPerfectMode.state.drop(1).collect { requestOutputReconfiguration(rebuild = true) } }
        scope.launch {
            Prefs.usbAllowFixedVolume.state.drop(1).collect {
                if (Prefs.bitPerfectMode.value == BitPerfectMode.USB_DIRECT) requestOutputReconfiguration(rebuild = true)
            }
        }
        scope.launch {
            Prefs.convertForLimitedDac.state.drop(1).collect {
                if (Prefs.bitPerfectMode.value != BitPerfectMode.OFF) requestOutputReconfiguration(rebuild = true)
            }
        }
    }

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
        private val listeners = java.util.concurrent.CopyOnWriteArraySet<Player.Listener>()

        override fun addListener(listener: Player.Listener) {
            listeners += listener
            super.addListener(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            listeners -= listener
            super.removeListener(listener)
        }

        // A DAC on direct USB output, whose volume is its own hardware attenuator:
        // remote, as far as Android's volume keys are concerned, since the music
        // stream no longer reaches it (BitChord).
        private val dac: Boolean get() = DacVolume.level.value != null

        private fun dacVolume(): Int = ((DacVolume.level.value ?: 0f) * 100).roundToInt()

        override fun getAvailableCommands(): Player.Commands =
            if (dac) super.getAvailableCommands().buildUpon().addAll(*DEVICE_VOLUME_COMMANDS).build()
            else super.getAvailableCommands()

        override fun isCommandAvailable(command: Int): Boolean =
            (dac && command in DEVICE_VOLUME_COMMANDS) || super.isCommandAvailable(command)

        /** The DAC volume came or went ([routeChanged]), or moved. Told by the service. */
        fun onDacVolumeChanged(routeChanged: Boolean) {
            if (routeChanged) {
                val commands = availableCommands
                listeners.forEach { it.onAvailableCommandsChanged(commands) }
                announce(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
                val info = deviceInfo
                listeners.forEach { it.onDeviceInfoChanged(info) }
                announce(Player.EVENT_DEVICE_INFO_CHANGED)
            }
            val volume = deviceVolume
            val muted = isDeviceMuted
            listeners.forEach { it.onDeviceVolumeChanged(volume, muted) }
            announce(Player.EVENT_DEVICE_VOLUME_CHANGED)
        }

        private fun announce(event: Int) {
            val events = Player.Events(FlagSet.Builder().add(event).build())
            listeners.forEach { it.onEvents(this, events) }
        }

        override fun getDeviceInfo(): DeviceInfo = if (dac) DAC_DEVICE else super.getDeviceInfo()

        override fun getDeviceVolume(): Int = if (dac) dacVolume() else super.getDeviceVolume()

        override fun isDeviceMuted(): Boolean = if (dac) false else super.isDeviceMuted()

        override fun setDeviceVolume(volume: Int, flags: Int) {
            if (dac) DacVolume.set(volume / 100f) else super.setDeviceVolume(volume, flags)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun setDeviceVolume(volume: Int) {
            if (dac) DacVolume.set(volume / 100f) else super.setDeviceVolume(volume)
        }

        override fun increaseDeviceVolume(flags: Int) {
            if (dac) DacVolume.step(+1) else super.increaseDeviceVolume(flags)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun increaseDeviceVolume() {
            if (dac) DacVolume.step(+1) else super.increaseDeviceVolume()
        }

        override fun decreaseDeviceVolume(flags: Int) {
            if (dac) DacVolume.step(-1) else super.decreaseDeviceVolume(flags)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun decreaseDeviceVolume() {
            if (dac) DacVolume.step(-1) else super.decreaseDeviceVolume()
        }

        override fun setDeviceMuted(muted: Boolean, flags: Int) {
            if (!dac) super.setDeviceMuted(muted, flags)
        }

        // Shuffle is a queue edit (BitChord's QueueShuffle), never the player's own
        // hidden order: a car, Bluetooth or the notification asking for shuffle
        // gets the same toggle as the app's button.
        override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
            if (shuffleModeEnabled != QueueShuffle.enabled.value) QueueShuffle.toggle(this)
        }

        override fun getShuffleModeEnabled(): Boolean = QueueShuffle.enabled.value

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
            // Item by item: the app's own adds (which carry their file and queue tag)
            // stay as they are; a browser's row adds just its song.
            mediaItems.mapNotNull { autoLibrary.resolveOne(it) }
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

        /**
         * Media3's stock threshold is 100ms, which eats the breaths and rests
         * inside a song; a second-long floor only trims dead air (BitChord).
         */
        private const val MIN_SILENCE_US = 1_000_000L

        private val DAC_DEVICE: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0)
            .setMaxVolume(100)
            .build()

        private val DEVICE_VOLUME_COMMANDS = intArrayOf(
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )

        private val AUDIO_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }

    override fun onDestroy() {
        outputReconfigureJob?.cancel()
        audioManager?.unregisterAudioDeviceCallback(outputDeviceCallback)
        UsbDirectDevices.onChanged = null
        BitPerfectOutput.onDirectDacLost = null
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
