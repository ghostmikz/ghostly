package com.ghostmikz.ghostly.ui.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostmikz.ghostly.lyrics.LocaleTags
import com.ghostmikz.ghostly.lyrics.LyricLine
import com.ghostmikz.ghostly.lyrics.LyricsSource
import com.ghostmikz.ghostly.lyrics.translationLanguageName
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch


/**
 * The translate control, sized and lit like every other disc in the player —
 * see [CircleGlyph]. Its own composable rather than a [CircleGlyph] call
 * because it has a fourth state the others do not: a request in flight, which
 * takes the icon's place rather than sitting beside it.
 */
@Composable
internal fun TranslationToggleButton(
    state: LyricsTranslationUiState,
    showingTranslation: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val active = showingTranslation || state is LyricsTranslationUiState.Loading
    val chrome = LocalPlayerChrome.current
    val tint = when {
        !enabled || state is LyricsTranslationUiState.SameLanguage -> chromeWhite(0.42f)
        active -> Color.White.copy(alpha = if (chrome) CHROME_FULL_ALPHA else 1f)
        else -> chromeWhite(0.78f)
    }
    val discAlpha by animateFloatAsState(
        targetValue = if (active) 0.34f else 0.18f,
        label = "translateDisc",
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .additiveChrome(chrome)
            .background(Color.White.copy(alpha = discAlpha))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (state is LyricsTranslationUiState.Loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = tint,
                strokeWidth = 1.7.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Translate,
                contentDescription = if (showingTranslation) "Show original lyrics" else "Translate lyrics",
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/** The left-hand companion to [TranslationToggleButton], producing Latin script. */
@Composable
internal fun RomanizationToggleButton(
    state: LyricsTranslationUiState,
    showingRomanization: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val active = showingRomanization || state is LyricsTranslationUiState.Loading
    val chrome = LocalPlayerChrome.current
    val tint = when {
        !enabled || state is LyricsTranslationUiState.SameLanguage -> chromeWhite(0.42f)
        active -> Color.White.copy(alpha = if (chrome) CHROME_FULL_ALPHA else 1f)
        else -> chromeWhite(0.78f)
    }
    val discAlpha by animateFloatAsState(
        targetValue = if (active) 0.34f else 0.18f,
        label = "romanizeDisc",
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .additiveChrome(chrome)
            .background(Color.White.copy(alpha = discAlpha))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (state is LyricsTranslationUiState.Loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = tint,
                strokeWidth = 1.7.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = if (showingRomanization) "Show original lyrics" else "Romanize lyrics",
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/**
 * A short text-material transition: the list and its playback clock stay in
 * place while a field of tiny glyph-like particles resolves into the new text.
 * Only the dedicated Canvas drawing moves, so changing language never causes a
 * second scroll, a blank frame, or a new lyrics timeline. The app's Reduce
 * animation preference collapses the whole response to an immediate swap.
 */
@Composable
internal fun LyricsTranslationMotion(
    trigger: Int,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (State<Float>?) -> Unit,
) {
    val progress = remember { Animatable(1f) }
    val foreground = rememberIsForeground()
    // Reopening the panel or returning from the background must not replay a
    // previous toggle. A new toggle cancels the previous effect automatically.
    var consumedTrigger by remember { mutableIntStateOf(trigger) }
    LaunchedEffect(trigger, reduceMotion, foreground) {
        val changed = trigger != consumedTrigger
        consumedTrigger = trigger
        if (!changed || trigger <= 0 || reduceMotion || !foreground) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = TRANSLATION_MOTION_MS, easing = LinearEasing),
            )
        }
    }

    Box(modifier = modifier) {
        // Keep the lyrics subtree completely outside the animation clock. In
        // particular, do not read progress in composition or apply a clipping
        // layer here: the panel's active line deliberately scales beyond its
        // measured bounds and its glow uses unbounded blur.
        content(progress.asState().takeIf { !reduceMotion && foreground && trigger > 0 })
    }
}

/** Glyph positions are cached at layout time; the shared clock is draw-only. */
internal fun Modifier.lyricParticles(
    layout: TextLayoutResult?,
    progress: State<Float>?,
    room: Dp,
): Modifier {
    if (layout == null || progress == null) return this
    return drawWithCache {
        val text = layout.layoutInput.text.text
        val candidates = text.indices.filter { text[it].isLetterOrDigit() }
        val random = Random(text.hashCode())
        val inset = room.toPx()
        val particles = candidates.shuffled(random).take(PARTICLES_PER_VOICE).map { index ->
            val glyph = layout.getBoundingBox(index)
            TranslationParticle(
                anchor = glyph.center + Offset(inset, inset),
                drift = Offset((random.nextFloat() - 0.5f) * 12.dp.toPx(),
                    -(5f + random.nextFloat() * 11f).dp.toPx()),
                radius = (0.65f + random.nextFloat() * 0.65f).dp.toPx(),
                delay = 0.16f * index / text.length.coerceAtLeast(1),
            )
        }
        onDrawWithContent {
            drawContent()
            val value = progress.value
            if (value > 0f && value < 1f) {
                particles.forEach { particle ->
                    val t = ((value - particle.delay) / 0.84f).coerceIn(0f, 1f)
                    val envelope = sin(PI * t).toFloat()
                    val ease = 1f - (1f - t) * (1f - t)
                    val center = particle.anchor + Offset(
                        particle.drift.x * ease,
                        particle.drift.y * ease + 3.dp.toPx() * t * t,
                    )
                    // Two inexpensive circles give a soft halo without another
                    // blur layer; opacity rises and falls without a flash.
                    drawCircle(Color.White, particle.radius * 2.7f, center, alpha = envelope * 0.07f)
                    drawCircle(Color.White, particle.radius, center, alpha = envelope * 0.58f)
                }
            }
        }
    }
}


/**
 * Everything the player shows about a lyric translation or romanization, and
 * the two toggles that ask for one — see [rememberLyricsTranslation].
 */
internal class LyricsTranslationUi(
    /** The lines the one-line strip reads: the originals, or their translation. */
    val displayedLyrics: List<LyricLine>,
    /** Drawn small under each original line in the panel; null for none. */
    val subLines: List<LyricLine>?,
    val translationState: LyricsTranslationUiState,
    val romanizationState: LyricsTranslationUiState,
    val showingTranslation: Boolean,
    val showingRomanization: Boolean,
    /** Bumped on every switch between versions — the particle motion's trigger. */
    val transition: Int,
    val reduceMotion: Boolean,
    /** The line naming the lyrics' source, or what the translation is doing. */
    val status: String,
    val toggleTranslation: () -> Unit,
    val toggleRomanization: () -> Unit,
)

/**
 * The translation and romanization state machine behind the lyric panel:
 * which language, what has been asked for, what came back, and which version
 * is on screen. Reset per track, per target language and per lyric sheet.
 */
@Composable
internal fun rememberLyricsTranslation(
    trackId: String,
    lyrics: List<LyricLine>?,
    lyricsSource: LyricsSource?,
    lyricsUnavailable: Boolean,
    /** What the status line says while the lyrics are still being looked up. */
    loadingText: String,
    haptics: Haptics,
): LyricsTranslationUi {
    val reduceTranslationMotion by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val configuredLocale = appLanguageTag()
    val preferredTranslation by PlayerSettings.translationLanguage.collectAsStateWithLifecycle()
    // Settings wins where it has been set; blank means follow the app. Only the
    // app-language path is reduced to a base language — a code chosen in
    // Settings is already exactly what the endpoint wants and narrowing it
    // would throw away the script half of zh-TW. Chinese is the exception on the
    // app path too: bare "zh" comes back Simplified, so the script maps to zh-TW / zh-CN.
    val translationLanguage = remember(configuredLocale, preferredTranslation) {
        preferredTranslation.ifBlank {
            if (LocaleTags.isChineseTag(configuredLocale)) {
                LocaleTags.translationWireTarget(configuredLocale)
            } else {
                Locale.forLanguageTag(configuredLocale).language.ifBlank { "en" }
            }
        }
    }
    val translationLanguageName = remember(configuredLocale, translationLanguage) {
        translationLanguageName(translationLanguage, Locale.forLanguageTag(configuredLocale))
    }
    // Resolved here, in composition, for the callbacks below to show.
    val alreadyInLanguageMessage = "These lyrics are already in %1\$s".format(translationLanguageName)
    val translationUnavailableMessage = "Translation is unavailable right now"
    val alreadyRomanizedMessage = "These lyrics already use Latin characters"
    val romanizationUnavailableMessage = "Romanization is unavailable for these lyrics"
    var translationState by remember(trackId, translationLanguage, lyrics) {
        mutableStateOf<LyricsTranslationUiState>(LyricsTranslationUiState.Idle)
    }
    var romanizationState by remember(trackId, translationLanguage, lyrics) {
        mutableStateOf<LyricsTranslationUiState>(LyricsTranslationUiState.Idle)
    }
    var lyricsDisplayMode by remember(trackId, translationLanguage, lyrics) {
        mutableStateOf(LyricsDisplayMode.Original)
    }
    val showingTranslation = lyricsDisplayMode == LyricsDisplayMode.Translated
    val showingRomanization = lyricsDisplayMode == LyricsDisplayMode.Romanized
    var translationTransition by remember(trackId) { mutableIntStateOf(0) }
    var translationJob by remember(trackId, translationLanguage, lyrics) {
        mutableStateOf<Job?>(null)
    }
    var romanizationJob by remember(trackId, translationLanguage, lyrics) {
        mutableStateOf<Job?>(null)
    }
    DisposableEffect(trackId, translationLanguage, lyrics) {
        onDispose {
            translationJob?.cancel()
            romanizationJob?.cancel()
        }
    }
    val displayedLyrics = when (lyricsDisplayMode) {
        LyricsDisplayMode.Translated ->
            (translationState as? LyricsTranslationUiState.Ready)?.lines ?: lyrics.orEmpty()
        LyricsDisplayMode.Romanized ->
            (romanizationState as? LyricsTranslationUiState.Ready)?.lines ?: lyrics.orEmpty()
        LyricsDisplayMode.Original -> lyrics.orEmpty()
    }
    // What the panel draws in small type under each original line, Apple
    // Music style. The panel itself always keeps the original words; only the
    // one-line strip over the scrubber swaps to [displayedLyrics]. One mode at
    // a time by construction — [lyricsDisplayMode] holds a single value.
    val lyricsSubLines = when (lyricsDisplayMode) {
        LyricsDisplayMode.Translated -> (translationState as? LyricsTranslationUiState.Ready)?.lines
        LyricsDisplayMode.Romanized -> (romanizationState as? LyricsTranslationUiState.Ready)?.lines
        LyricsDisplayMode.Original -> null
    }
    val translationScope = rememberCoroutineScope()
    val toggleTranslation: () -> Unit = toggleTranslation@{
        when (val state = translationState) {
            is LyricsTranslationUiState.Ready -> {
                lyricsDisplayMode = if (showingTranslation) {
                    LyricsDisplayMode.Original
                } else {
                    LyricsDisplayMode.Translated
                }
                translationTransition++
                haptics.play(Haptic.Select)
            }
            LyricsTranslationUiState.Loading -> Unit
            LyricsTranslationUiState.SameLanguage -> {
                haptics.play(Haptic.Tap)
                PlayerPlatform.host.showMessage(alreadyInLanguageMessage)
            }
            LyricsTranslationUiState.Idle -> {
                val source = lyrics.orEmpty()
                if (source.isEmpty()) return@toggleTranslation
                haptics.play(Haptic.Tap)
                translationState = LyricsTranslationUiState.Loading
                romanizationJob?.cancel()
                if (romanizationState is LyricsTranslationUiState.Loading) {
                    romanizationState = LyricsTranslationUiState.Idle
                }
                translationJob?.cancel()
                translationJob = translationScope.launch {
                    when (
                        val result = PlayerPlatform.host.translateLyrics(
                            trackId = trackId,
                            lines = source,
                            targetLanguageTag = translationLanguage,
                        )
                    ) {
                        is LyricsTranslationResult.Translated -> {
                            translationState = LyricsTranslationUiState.Ready(result.lines)
                            lyricsDisplayMode = LyricsDisplayMode.Translated
                            translationTransition++
                            haptics.play(Haptic.ToggleOn)
                        }
                        is LyricsTranslationResult.SameLanguage -> {
                            translationState = LyricsTranslationUiState.SameLanguage
                            PlayerPlatform.host.showMessage(alreadyInLanguageMessage)
                        }
                        LyricsTranslationResult.Unavailable -> {
                            translationState = LyricsTranslationUiState.Idle
                            PlayerPlatform.host.showMessage(translationUnavailableMessage)
                        }
                    }
                }
            }
        }
    }
    val toggleRomanization: () -> Unit = toggleRomanization@{
        when (val state = romanizationState) {
            is LyricsTranslationUiState.Ready -> {
                lyricsDisplayMode = if (showingRomanization) {
                    LyricsDisplayMode.Original
                } else {
                    LyricsDisplayMode.Romanized
                }
                translationTransition++
                haptics.play(Haptic.Select)
            }
            LyricsTranslationUiState.Loading -> Unit
            LyricsTranslationUiState.SameLanguage -> {
                haptics.play(Haptic.Tap)
                PlayerPlatform.host.showMessage(alreadyRomanizedMessage)
            }
            LyricsTranslationUiState.Idle -> {
                val source = lyrics.orEmpty()
                if (source.isEmpty()) return@toggleRomanization
                haptics.play(Haptic.Tap)
                romanizationState = LyricsTranslationUiState.Loading
                translationJob?.cancel()
                if (translationState is LyricsTranslationUiState.Loading) {
                    translationState = LyricsTranslationUiState.Idle
                }
                romanizationJob?.cancel()
                romanizationJob = translationScope.launch {
                    when (
                        val result = PlayerPlatform.host.romanizeLyrics(
                            trackId = trackId,
                            lines = source,
                            targetLanguageTag = translationLanguage,
                        )
                    ) {
                        is LyricsRomanizationResult.Romanized -> {
                            romanizationState = LyricsTranslationUiState.Ready(result.lines)
                            lyricsDisplayMode = LyricsDisplayMode.Romanized
                            translationTransition++
                            haptics.play(Haptic.ToggleOn)
                        }
                        LyricsRomanizationResult.AlreadyRomanized -> {
                            romanizationState = LyricsTranslationUiState.SameLanguage
                            PlayerPlatform.host.showMessage(alreadyRomanizedMessage)
                        }
                        LyricsRomanizationResult.Unavailable -> {
                            romanizationState = LyricsTranslationUiState.Idle
                            PlayerPlatform.host.showMessage(romanizationUnavailableMessage)
                        }
                    }
                }
            }
        }
    }
    // The one line that sits above the scrubber while the lyrics are open: which
    // of the providers the timings came from, or what the translation is doing.
    // Worked out once for both layouts — the portrait player puts it over its
    // half-player, the landscape one under its lyric column — so the two can
    // never disagree about what it says.
    val status = when {
        translationState is LyricsTranslationUiState.Loading ->
            "Translating to %1\$s…".format(translationLanguageName)
        romanizationState is LyricsTranslationUiState.Loading ->
            "Romanizing lyrics…"
        showingTranslation ->
            "Translated to %1\$s".format(translationLanguageName)
        showingRomanization ->
            "Romanized pronunciation"
        translationState is LyricsTranslationUiState.SameLanguage ->
            "These lyrics are already in %1\$s".format(translationLanguageName)
        romanizationState is LyricsTranslationUiState.SameLanguage ->
            "These lyrics already use Latin characters"
        lyricsSource != null -> "Lyrics by %1\$s".format(lyricsSource.label)
        lyricsUnavailable -> "No lyrics found"
        lyrics.isNullOrEmpty() -> loadingText
        else -> "Lyrics saved with this download"
    }

    return LyricsTranslationUi(
        displayedLyrics = displayedLyrics,
        subLines = lyricsSubLines,
        translationState = translationState,
        romanizationState = romanizationState,
        showingTranslation = showingTranslation,
        showingRomanization = showingRomanization,
        transition = translationTransition,
        reduceMotion = reduceTranslationMotion,
        status = status,
        toggleTranslation = toggleTranslation,
        toggleRomanization = toggleRomanization,
    )
}
