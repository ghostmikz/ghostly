// Ported from BitChord's CrossfadeController (https://github.com/kushagrasinghx/BitChord, GPLv3):
// its plain crossfade only. BitChord's Automix (beat analysis, filters, loop mixes,
// tempo ease) is left out, so every branch that depended on it is gone too.
package com.ghostmikz.ghostly.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ghostmikz.ghostly.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Blends the end of one track into the start of the next, on two players.
 *
 * The incoming track always starts on the standby player, loaded with the whole
 * queue from the next item on, while the outgoing track plays out on the
 * session's player. The two are faded against each other with equal-power
 * curves (sin / cos, so their summed power stays level), and halfway through
 * the session moves to the incoming player ([onHandoff]): controllers only ever
 * see one player, which flips from the old track to the new one already
 * playing.
 *
 * Progress is read off the incoming player's own position rather than a timer,
 * so a pause freezes the blend where it is. A manual skip or seek never
 * crossfades: it ends a transition in progress ([bail]).
 */
class CrossfadeController(
    private val scope: CoroutineScope,
    private val active: () -> ExoPlayer,
    private val standby: () -> ExoPlayer,
    private val onHandoff: (outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit,
) {

    private enum class Phase { IDLE, ARMING, FADING, SLEEP_FADE, BAILING }

    /**
     * A player's position between ExoPlayer's own reports, which arrive in steps:
     * the last report plus the wall-clock time since, at the playback speed,
     * capped so a stalled player isn't run on indefinitely.
     */
    private class PositionClock {
        private var player: ExoPlayer? = null
        private var reported = 0L
        private var seenAt = 0L

        fun reset() {
            player = null
        }

        fun read(p: ExoPlayer): Double {
            val now = System.nanoTime()
            val position = p.currentPosition
            if (p !== player || position != reported) {
                seenAt = if (p === player) now - HALF_TICK_NANOS else now
                player = p
                reported = position
            }
            if (!p.isPlaying) return position.toDouble()
            val since = (now - seenAt).coerceIn(0L, MAX_EXTRAPOLATION_NANOS)
            return position + since / 1e6 * p.playbackParameters.speed
        }
    }

    private var phase = Phase.IDLE
    private var outgoing: ExoPlayer? = null
    private var incoming: ExoPlayer? = null
    private var handedOff = false
    private var queuedItemCount = 0
    private var outgoingItemIndex = C.INDEX_UNSET
    private var fadeMs = 0L
    private var fadeEndMs = 0L
    private var armDeadline = 0L
    private var bailStartedAt = 0L
    private var bailFromGain = 0f
    private var sleepFadeMs = 1L
    private var sleepFadeStartPositionMs = 0L
    private var sleepFadeStartGain = 1f
    private val inClock = PositionClock()
    private var tickerJob: Job? = null
    private var listeningTo: ExoPlayer? = null

    /** The player currently held at its track end; see [holdAtEnd]. */
    private var heldAtEnd: ExoPlayer? = null
    private var holdWanted = false

    fun isTransitioning(): Boolean = phase != Phase.IDLE

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) bail()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> bail()
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> bail()
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> ranOutBeforeHandoff()
            }
        }

        override fun onPlayerError(error: PlaybackException) = bail()

        // The held player reached its track end before the handoff: hand off now,
        // or, with nothing to hand to, let it carry on into the next track.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
            val held = heldAtEnd ?: return
            if (held !== listeningTo) return
            val into = incoming
            if (phase == Phase.FADING && !handedOff && into != null) {
                handOff(held, into)
                return
            }
            releaseHold()
            if (phase == Phase.ARMING) bail()
            held.play()
        }
    }

    private fun ranOutBeforeHandoff() {
        if (handedOff) return
        when (phase) {
            Phase.ARMING -> bail()
            Phase.FADING -> {
                val out = outgoing ?: return bail()
                val into = incoming ?: return bail()
                Log.d(TAG, "outgoing ran on into the next track before the handoff")
                out.volume = 0f
                handOff(out, into)
            }
            else -> Unit
        }
    }

    /** Only the session's player is listened to: loading the standby must not read as a change. */
    private fun listenTo(target: ExoPlayer) {
        if (listeningTo === target) return
        listeningTo?.removeListener(listener)
        target.addListener(listener)
        listeningTo = target
    }

    fun start() {
        listenTo(active())
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                tick()
                delay(
                    when (phase) {
                        Phase.IDLE -> IDLE_STEP_MS
                        Phase.ARMING -> ARM_STEP_MS
                        Phase.FADING, Phase.SLEEP_FADE -> FADE_STEP_MS
                        Phase.BAILING -> BAIL_STEP_MS
                    },
                )
            }
        }
    }

    fun release() {
        tickerJob?.cancel()
        tickerJob = null
        releaseHold()
        listeningTo?.removeListener(listener)
        listeningTo = null
        active().volume = 1f
    }

    /** A user skip or seek: never crossfaded, and it ends a transition in progress. */
    fun onSkipRequested() {
        if (phase != Phase.IDLE) bail()
    }

    private fun tick() {
        // One rule covers focus loss, the sleep timer and the pause button: the
        // other player follows the one the session is on.
        if (phase == Phase.FADING || phase == Phase.BAILING) {
            if (handedOff) {
                outgoing?.playWhenReady = incoming?.playWhenReady ?: true
            } else {
                outgoing?.playWhenReady?.let { incoming?.playWhenReady = it }
            }
        }
        when (phase) {
            Phase.IDLE -> considerAutoTransition()
            Phase.ARMING -> driveArming()
            Phase.FADING -> driveFade()
            Phase.SLEEP_FADE -> driveSleepFade()
            Phase.BAILING -> driveBail()
        }
    }

    private fun considerAutoTransition() {
        val player = active()
        if (!player.isPlaying) return
        holdWanted = false
        planAutoTransition(player)
        if (!holdWanted && phase == Phase.IDLE) releaseHold()
    }

    private fun planAutoTransition(player: ExoPlayer) {
        if (!player.hasNextMediaItem()) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return
        // Never fade a track into itself.
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return
        if (configuredFadeMs() <= 0L) return
        val fade = fadeFor(duration)
        if (fade <= 0L) return
        val remaining = duration - player.currentPosition
        // "After this song": the track fades out and stops instead of handing on.
        if (SleepTimer.afterTrack.value) {
            if (remaining > fade) return
            beginSleepFadeOut(fade)
            return
        }
        holdAhead(player, duration)
        if (remaining > fade + ARM_LEAD_MS) return
        begin(fade, endMs = duration)
    }

    private fun begin(fade: Long, endMs: Long): Boolean {
        val out = active()
        val into = standby()
        if (out === into) return false
        val nextIndex = out.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false
        fadeMs = fade
        fadeEndMs = endMs
        armDeadline = SystemClock.elapsedRealtime() + ARM_TIMEOUT_MS
        handedOff = false
        outgoing = out
        incoming = into
        outgoingItemIndex = out.currentMediaItemIndex
        val items = (0 until out.mediaItemCount).map { out.getMediaItemAt(it) }
        queuedItemCount = items.size
        Log.d(TAG, "arm fade=${fade}ms end=${endMs}ms at=${out.currentPosition}ms")
        holdAtEnd(out)
        into.skipSilenceEnabled = out.skipSilenceEnabled
        into.repeatMode = out.repeatMode
        into.shuffleModeEnabled = out.shuffleModeEnabled
        into.setPlaybackSpeed(out.playbackParameters.speed)
        into.volume = 0f
        into.setMediaItems(items, nextIndex, 0L)
        into.playWhenReady = false
        into.prepare()
        phase = Phase.ARMING
        return true
    }

    /**
     * Stops the outgoing player at its track end. Without it ExoPlayer reads ahead
     * into the next item, and removing that item at the handoff makes Media3 flush
     * the sink, an audible dropout. Only set while the end is further away than
     * Media3 can already have read ahead.
     */
    private fun holdAtEnd(out: ExoPlayer) {
        if (heldAtEnd === out) return
        val duration = out.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return
        val left = duration - out.currentPosition
        if (left <= maxReadAheadMs(out)) {
            Log.d(TAG, "end not held: ${left}ms left, may already be reading on (${maxReadAheadMs(out)}ms)")
            return
        }
        releaseHold()
        out.pauseAtEndOfMediaItems = true
        heldAtEnd = out
    }

    private fun holdAhead(player: ExoPlayer, duration: Long) {
        val left = duration - player.currentPosition
        val limit = maxReadAheadMs(player)
        if (left > limit + HOLD_AHEAD_MS) return
        holdWanted = true
        if (left > limit) holdAtEnd(player)
    }

    private fun releaseHold() {
        heldAtEnd?.pauseAtEndOfMediaItems = false
        heldAtEnd = null
    }

    private fun maxReadAheadMs(player: ExoPlayer): Long {
        val speed = player.playbackParameters.speed.coerceAtLeast(1f)
        return (MEDIA3_READ_AHEAD_LIMIT_MS * speed).roundToLong() + HOLD_SAFETY_MS
    }

    /** The unheld outgoing player has moved on to the next item after the handoff: keep it silent. */
    private fun ranOn(out: ExoPlayer): Boolean =
        handedOff && outgoingItemIndex != C.INDEX_UNSET && out.currentMediaItemIndex != outgoingItemIndex

    private fun driveArming() {
        val out = outgoing ?: return bail()
        val into = incoming ?: return bail()
        if (!stillWorthFading()) return bail()
        if (!out.playWhenReady) return bail()
        val expired = SystemClock.elapsedRealtime() > armDeadline
        val ready = into.playbackState == Player.STATE_READY
        if (expired && !ready) return bail()
        val atFadePoint = fadeEndMs <= 0L || fadeEndMs - out.currentPosition <= fadeMs
        if (!atFadePoint) return
        if (ready) startFade()
    }

    private fun startFade() {
        val into = incoming ?: return bail()
        into.volume = 0f
        into.playWhenReady = true
        inClock.reset()
        Log.d(TAG, "blend start at in=${into.currentPosition}ms out=${outgoing?.currentPosition}ms")
        phase = Phase.FADING
    }

    private fun handOff(out: ExoPlayer, into: ExoPlayer) {
        if (handedOff) return
        reconcileQueue(out, into)
        Log.d(TAG, "handoff at in=${into.currentPosition}ms out=${out.currentPosition}ms")
        listenTo(into)
        handedOff = true
        onHandoff(out, into)
        if (heldAtEnd === out && out.mediaItemCount > out.currentMediaItemIndex + 1) {
            out.removeMediaItems(out.currentMediaItemIndex + 1, out.mediaItemCount)
        }
    }

    /** Anything added to the queue while the incoming player was armed goes onto it too. */
    private fun reconcileQueue(out: ExoPlayer, into: ExoPlayer) {
        val appended = (queuedItemCount until out.mediaItemCount).map { out.getMediaItemAt(it) }
        if (appended.isEmpty()) return
        into.addMediaItems(appended)
        queuedItemCount = out.mediaItemCount
    }

    private fun driveFade() {
        val out = outgoing ?: return bail()
        val player = incoming ?: return bail()
        // Capped by the incoming track too: its length is often unknown when the fade starts.
        val remainingIncoming = player.duration
            .takeIf { it != C.TIME_UNSET && it > 0L }
            ?.coerceAtLeast(0L)
        val incomingCap = remainingIncoming?.div(3) ?: Long.MAX_VALUE
        val span = minOf(fadeMs, incomingCap).coerceAtLeast(1L)
        val elapsed = inClock.read(player).coerceAtLeast(0.0).toFloat()
        val incomingProgress = (elapsed / span).coerceIn(0f, 1f)
        // The outgoing side must reach zero before its last sample, whatever the incoming says.
        val outRemaining = out.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            ?.minus(out.currentPosition)
        val endDrivenProgress = outRemaining?.let {
            ((span - it).toFloat() / span).coerceIn(0f, 1f)
        } ?: 0f
        val progress = maxOf(incomingProgress, endDrivenProgress)

        // Before the handoff a failure on the incoming player would otherwise go unheard.
        if (!handedOff && player.playbackState == Player.STATE_IDLE) return bail()

        val rise = riseGain(progress)
        val fall = fallGain(progress)
        // BitChord trims both sides in its DSP stage when they sum past full scale;
        // here that headroom is folded into the faders.
        val trim = headroomFor(rise, fall)
        player.volume = rise * trim
        out.volume = if (ranOn(out)) 0f else fall * trim

        if (!handedOff && (progress >= HANDOFF_AT || (outRemaining != null && outRemaining <= HANDOFF_GUARD_MS))) {
            handOff(out, player)
        }
        val settingSwitchedOff = configuredFadeMs() <= 0L
        if (progress >= 1f || settingSwitchedOff) {
            handOff(out, player)
            finish()
        }
    }

    private fun beginSleepFadeOut(fade: Long) {
        val out = active()
        sleepFadeMs = fade.coerceAtLeast(1L)
        sleepFadeStartPositionMs = out.currentPosition
        sleepFadeStartGain = out.volume
        outgoing = out
        incoming = null
        handedOff = false
        Log.d(TAG, "sleep timer due: fading out over ${fade}ms instead of transitioning")
        phase = Phase.SLEEP_FADE
    }

    private fun driveSleepFade() {
        val out = outgoing ?: return finishSleepFade()
        if (!SleepTimer.afterTrack.value) {
            out.volume = 1f
            outgoing = null
            phase = Phase.IDLE
            return
        }
        val elapsed = (out.currentPosition - sleepFadeStartPositionMs).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / sleepFadeMs).coerceIn(0f, 1f)
        out.volume = sleepFadeStartGain * fallGain(progress)
        val done = progress >= 1f ||
            out.playbackState == Player.STATE_ENDED ||
            out.playbackState == Player.STATE_IDLE
        if (done) finishSleepFade()
    }

    private fun finishSleepFade() {
        outgoing?.pause()
        outgoing?.volume = 1f
        SleepTimer.cancel()
        outgoing = null
        phase = Phase.IDLE
    }

    private fun bail() {
        if (phase == Phase.IDLE || phase == Phase.BAILING) return
        Log.d(TAG, "bail from $phase")
        if (phase == Phase.SLEEP_FADE) {
            outgoing?.volume = 1f
            outgoing = null
            phase = Phase.IDLE
            return
        }
        // Nothing audible from the standby yet: just put it away.
        if (!handedOff && phase != Phase.FADING) {
            finish()
            return
        }
        if (handedOff) incoming?.volume = 1f else outgoing?.volume = 1f
        bailFromGain = leavingOnBail()?.volume ?: 0f
        bailStartedAt = SystemClock.elapsedRealtime()
        phase = Phase.BAILING
    }

    private fun leavingOnBail(): ExoPlayer? = if (handedOff) outgoing else incoming

    /** The side being dropped ramps down over [BAIL_MS] rather than cutting off with a click. */
    private fun driveBail() {
        val leaving = leavingOnBail()
        if (leaving == null) {
            finish()
            return
        }
        val progress = (SystemClock.elapsedRealtime() - bailStartedAt).toFloat() / BAIL_MS
        if (progress < 1f) {
            val fall = if (leaving === outgoing && ranOn(leaving)) 0f else bailFromGain * fallGain(progress)
            leaving.volume = fall
            return
        }
        finish()
    }

    private fun finish() {
        if (phase != Phase.IDLE) Log.d(TAG, "finish from $phase")
        releaseHold()
        if (handedOff) {
            incoming?.volume = 1f
            outgoing?.let(::retire)
        } else {
            outgoing?.volume = 1f
            incoming?.let(::retire)
        }
        outgoing = null
        incoming = null
        handedOff = false
        outgoingItemIndex = C.INDEX_UNSET
        queuedItemCount = 0
        phase = Phase.IDLE
    }

    private fun stillWorthFading(): Boolean {
        val stillOn = configuredFadeMs() > 0L
        return stillOn && (outgoing ?: active()).hasNextMediaItem()
    }

    /** A player off duty: empty, unheld and at full volume, ready to be the next standby. */
    private fun retire(player: ExoPlayer) {
        player.stop()
        player.clearMediaItems()
        player.pauseAtEndOfMediaItems = false
        player.volume = 1f
    }

    private fun configuredFadeMs(): Long = Prefs.crossfadeSeconds.value * 1000L

    /** Never more than a third of the track. */
    private fun fadeFor(duration: Long): Long {
        val configured = configuredFadeMs()
        if (duration == C.TIME_UNSET || duration <= 0L) return configured
        return minOf(configured, duration / 3).coerceAtLeast(0L)
    }

    private fun riseGain(progress: Float): Float =
        sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    private fun fallGain(progress: Float): Float =
        cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    /** At most 1.5 dB at the midpoint: a gain, not a limiter, so it never pumps. */
    private fun headroomFor(incomingGain: Float, outgoingGain: Float): Float {
        val sum = incomingGain + outgoingGain
        return if (sum <= 1f) 1f else 1f / sqrt(sum)
    }

    private companion object {
        const val TAG = "GhostlyCrossfade"
        const val BAIL_MS = 120L
        const val ARM_LEAD_MS = 4_000L
        /** How far Media3 1.11 reads ahead; re-check on a Media3 upgrade. */
        const val MEDIA3_READ_AHEAD_LIMIT_MS = 10_000L
        const val HOLD_SAFETY_MS = 1_000L
        const val HOLD_AHEAD_MS = 3_000L
        const val ARM_TIMEOUT_MS = 12_000L
        const val HANDOFF_AT = 0.5f
        const val HANDOFF_GUARD_MS = 400L
        const val IDLE_STEP_MS = 250L
        const val ARM_STEP_MS = 40L
        const val FADE_STEP_MS = 30L
        const val HALF_TICK_NANOS = FADE_STEP_MS * 500_000L
        const val MAX_EXTRAPOLATION_NANOS = 600_000_000L
        const val BAIL_STEP_MS = 15L
    }
}
