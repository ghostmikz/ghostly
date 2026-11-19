/*
 * Ported from BitChord's LibraryReplayEntry (LibraryPhoneParts.kt) and
 * ReplayCardRowSkeleton/ShimmerBox (Skeletons.kt)
 * (https://github.com/kushagrasinghx/BitChord, GPLv3).
 */
package com.ghostmikz.ghostly.ui.replay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp

/**
 * The Library's way in to Replay: its row of headline cards, placeholders for
 * them while the history is read, and nothing at all while there is not yet
 * enough listening to deal them.
 */
@Composable
fun LibraryReplayEntry(
    cards: List<ReplayHeroCard>,
    loading: Boolean,
    holder: String,
    memberSince: String?,
    onOpenReplay: (ReplayStoryPage) -> Unit,
) {
    if (loading) {
        ReplayCardRowSkeleton()
    } else if (cards.isNotEmpty()) {
        ReplayCardRow(
            cards = cards,
            holder = holder,
            memberSince = memberSince,
            onCardClick = onOpenReplay,
            modifier = Modifier.padding(vertical = 6.dp),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        )
    }
}

/**
 * The row of Replay cards while the listening history is still being read — two
 * cards at the real ones' 300dp width, credit-card ratio and corner, so the row
 * lands in place rather than pushing the rest down.
 */
@Composable
private fun ReplayCardRowSkeleton(modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.padding(vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false,
    ) {
        items(2) {
            ShimmerBox(
                modifier = Modifier.width(300.dp).aspectRatio(1.586f),
                shape = RoundedCornerShape(20.dp),
            )
        }
    }
}

@Composable
private fun ShimmerBox(modifier: Modifier, shape: Shape) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f).compositeOver(base)
    val sweep = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_PERIOD_MS, easing = LinearEasing)),
        label = "sweep",
    )
    Box(
        modifier
            .clip(shape)
            .drawWithCache {
                // The band travels from fully off one edge to fully off the
                // other, which leaves a beat of flat grey between passes.
                val band = size.width * 0.5f
                val startX = -band + sweep.value * (size.width + band * 2)
                val brush = Brush.horizontalGradient(
                    colors = listOf(base, highlight, base),
                    startX = startX,
                    endX = startX + band,
                )
                onDrawBehind { drawRect(brush) }
            },
    )
}

private const val SHIMMER_PERIOD_MS = 1400
