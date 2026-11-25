package com.ghostmikz.ghostly

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawBehind
import android.Manifest
import com.ghostmikz.ghostly.data.Song
import androidx.compose.ui.layout.boundsInRoot
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.collectAsState
import com.ghostmikz.ghostly.ui.backdrop.backdrops.layerBackdrop
import com.ghostmikz.ghostly.ui.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import android.app.Activity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostmikz.ghostly.data.History
import com.ghostmikz.ghostly.data.Prefs
import com.ghostmikz.ghostly.ui.*
import com.ghostmikz.ghostly.ui.icons.BitChordIcons
import com.ghostmikz.ghostly.data.stats.ListeningStats
import com.ghostmikz.ghostly.ui.replay.LibraryReplayEntry
import com.ghostmikz.ghostly.ui.replay.ReplayScreen
import com.ghostmikz.ghostly.ui.replay.ReplayShareSheet
import com.ghostmikz.ghostly.ui.replay.ReplayStories
import com.ghostmikz.ghostly.ui.replay.ReplayStoryPage
import com.ghostmikz.ghostly.ui.replay.cards
import com.ghostmikz.ghostly.ui.replay.rememberReplayState
import com.ghostmikz.ghostly.ui.replay.replayHolder

/**
 * The page stack as plain strings, so it survives rotation, a theme or font
 * change, and the system recreating the activity: "album:12", "artist:Name"...
 */
private val StackSaver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<Screen>, String>(
    save = { stack -> stack.map { it.encode() } },
    restore = { saved -> mutableStateListOf<Screen>().apply { addAll(saved.mapNotNull(::decodeScreen)) } },
)

private fun Screen.encode(): String = when (this) {
    Screen.Settings -> "settings"
    Screen.Recent -> "recent"
    is Screen.AlbumScreen -> "album:$id"
    is Screen.ArtistScreen -> "artist:$name"
    is Screen.FolderScreen -> "folder:$path"
    is Screen.PlaylistScreen -> "playlist:$id"
    is Screen.Replay -> "replay:${landing.name}"
}

private fun decodeScreen(s: String): Screen? {
    val kind = s.substringBefore(':')
    val arg = s.substringAfter(':', "")
    return when (kind) {
        "settings" -> Screen.Settings
        "recent" -> Screen.Recent
        "album" -> arg.toLongOrNull()?.let { Screen.AlbumScreen(it) }
        "artist" -> Screen.ArtistScreen(arg)
        "folder" -> Screen.FolderScreen(arg)
        "playlist" -> Screen.PlaylistScreen(arg)
        "replay" -> runCatching { Screen.Replay(ReplayStoryPage.valueOf(arg)) }.getOrNull()
        else -> null
    }
}

private sealed interface Screen {
    data object Settings : Screen
    data object Recent : Screen
    data class AlbumScreen(val id: Long) : Screen
    data class ArtistScreen(val name: String) : Screen
    data class FolderScreen(val path: String) : Screen
    data class PlaylistScreen(val id: String) : Screen
    data class Replay(val landing: ReplayStoryPage) : Screen
}

private val audioPermission =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

/** How far a tab scrolls before the top fade is fully in. */
private val TOP_FADE_RAMP = 120.dp

class MainActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()

    // Volume keys move the DAC's own volume while direct USB output owns it (BitChord).
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        handleDacVolumeKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        handleDacVolumeKey(event) || super.onKeyUp(keyCode, event)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        History.init(this)
        com.ghostmikz.ghostly.data.Playlists.init(this)
        com.ghostmikz.ghostly.data.SongKeys.init(this)
        ListeningStats.init(this)
        com.ghostmikz.ghostly.lastfm.ScrobbleQueue.init(this)
        ArtCache.sizeFor(this)
        if (savedInstanceState == null) {
            takeOpenPlayer(intent)
            takeVoiceSearch(intent)
        }
        setContent { GhostlyTheme { App(vm) } }
    }

    override fun onStart() {
        super.onStart()
        vm.setVisible(true)
    }

    override fun onStop() {
        vm.setVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOpenPlayer(intent)
        takeVoiceSearch(intent)
    }

    /** The Assistant or Android Auto asking to play something (MEDIA_PLAY_FROM_SEARCH). */
    private fun takeVoiceSearch(intent: Intent?) {
        if (intent?.action != android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        vm.playFromSearch(intent.getStringExtra(android.app.SearchManager.QUERY))
        intent.action = null
    }

    /** A tap on a home-screen widget asks for the full player (BitChord's PlayerDeepLink). */
    private fun takeOpenPlayer(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) != true) return
        // Reopened from Recents, Android replays the original widget intent; that
        // is not a new tap and shouldn't open the player again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        intent.removeExtra(EXTRA_OPEN_PLAYER)
        openPlayerPending.value = true
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "com.ghostmikz.ghostly.OPEN_PLAYER"
        internal val openPlayerPending = kotlinx.coroutines.flow.MutableStateFlow(false)
    }
}

@Composable
private fun App(vm: PlayerViewModel) {
    val context = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(granted()) }
    val activity = context as? android.app.Activity
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasPermission = granted() }
    // Granted in system Settings while away: notice it on the way back.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) hasPermission = granted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun ask() {
        // Denied for good ("Don't ask again", or twice on Android 11+): the dialog
        // no longer appears, so the button opens Ghostly's page in system Settings.
        val blocked = askedOnce && activity != null &&
            !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, audioPermission)
        askedOnce = true
        if (blocked) {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.fromParts("package", context.packageName, null)),
                )
            }
            return
        }
        val perms = buildList {
            add(audioPermission)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        launcher.launch(perms.toTypedArray())
    }
    LaunchedEffect(Unit) { if (!hasPermission) ask() }
    LaunchedEffect(hasPermission) { if (hasPermission) vm.loadLibrary() }

    Box(Modifier.fillMaxSize().background(GhostlyColors.Background)) {
        if (hasPermission) Main(vm) else PermissionScreen(::ask)
    }
}

@Composable
private fun PermissionScreen(onAllow: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(28.dp), verticalArrangement = Arrangement.Bottom) {
        Text("Your music,\nright here.", color = GhostlyColors.Text, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1).sp, lineHeight = 44.sp)
        Spacer(Modifier.height(12.dp))
        Text("Ghostly plays the audio files on this phone. Allow access to your music to get started.",
            color = GhostlyColors.TextSecondary, fontSize = 17.sp)
        Spacer(Modifier.height(32.dp))
        Box(
            Modifier.fillMaxWidth().height(54.dp).clip(CircleShape).background(GhostlyColors.Text).clickable(onClick = onAllow),
            contentAlignment = Alignment.Center,
        ) { Text("Allow access", color = GhostlyColors.Background, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(16.dp))
    }
}

/** What the content area shows: a tab, or a page pushed on top of it. */
/** Stand-in for "no song": the picker was opened to make a new playlist. */
private val NEW_PLAYLIST = com.ghostmikz.ghostly.data.Song(-1, "", "", "", -1, 0, 0, 0, 0, "")

private data class Page(val tab: Int, val depth: Int, val screen: Screen?)

private val TABS = listOf(
    TabItem("Home", BitChordIcons.Home),
    TabItem("Library", BitChordIcons.TabLibrary),
    TabItem("Search", BitChordIcons.TabSearch),
)

@OptIn(ExperimentalSharedTransitionApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Main(vm: PlayerViewModel) {
    val library by vm.library.collectAsStateWithLifecycle()
    val libraryLoading by vm.loading.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val stats by History.stats.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val stack = rememberSaveable(saver = StackSaver) { mutableStateListOf<Screen>() }
    val pageStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val motion = motionOn()
    val scope = rememberCoroutineScope()

    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    // BitChord's "Add to playlist" sheet, for a song or (NEW_PLAYLIST) to make a playlist.
    // The picker's song is kept by id across recreation, and found again in the library.
    var pickerForId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val actions = remember(vm) {
        LibraryActions(
            play = { songs, i, shuffle, from -> vm.play(songs, i, shuffle, from) },
            playNext = { vm.playNext(it) },
            openAlbum = { stack += Screen.AlbumScreen(it) },
            openArtist = { stack += Screen.ArtistScreen(it) },
            openFolder = { stack += Screen.FolderScreen(it) },
            openFavorites = { stack += Screen.PlaylistScreen(com.ghostmikz.ghostly.data.Playlists.LIKED_ID) },
            openRecent = { stack += Screen.Recent },
            openSettings = { stack += Screen.Settings },
            back = back,
            openPlaylist = { stack += Screen.PlaylistScreen(it) },
            addToPlaylist = { pickerForId = (it ?: NEW_PLAYLIST).id; pickerOpen = true },
        )
    }

    // ---- player sheet: 0 = fully open, 1 = closed. It follows the finger while dragged. ----
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val sheet = remember { Animatable(if (playerOpen) 0f else 1f) }
    // Where the mini player's cover is, for the player's cover to fly to and from (BitChord's PlayerDock).
    var miniArtCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    fun settle(open: Boolean, velocity: Float = 0f) {
        playerOpen = open
        scope.launch { sheet.animateTo(if (open) 0f else 1f, ghostSpring(motion, 1f, 195f), initialVelocity = velocity) }
    }

    // ---- Replay (BitChord): one set of numbers for the Library cards, the page and the stories ----
    var replayStory by rememberSaveable { mutableStateOf<ReplayStoryPage?>(null) }
    var replayShare by rememberSaveable { mutableStateOf<ReplayStoryPage?>(null) }
    var showReplayShare by rememberSaveable { mutableStateOf(false) }
    val replayOpen = stack.lastOrNull() is Screen.Replay || replayStory != null || showReplayShare ||
        (tab == 1 && stack.isEmpty())
    val (replay, setReplayPeriod) = rememberReplayState(replayOpen)
    val replayCards = remember(replay.summary) { replay.summary?.takeUnless { it.isEmpty }?.cards().orEmpty() }
    val lastfmUser by Prefs.lastfmUser.collectAsStateWithLifecycle()
    val replayHolderName = replayHolder(lastfmUser)

    // Widget tap: open the player once there is a song to show.
    val openPending by MainActivity.openPlayerPending.collectAsStateWithLifecycle()
    LaunchedEffect(openPending, state.current != null) {
        if (openPending && state.current != null) {
            MainActivity.openPlayerPending.value = false
            if (!playerOpen) settle(true)
        }
    }

    BackHandler(enabled = replayStory != null && !showReplayShare) { replayStory = null }
    BackHandler(enabled = playerOpen) { settle(false) }
    // The handler composed last wins, so these stand down while a story is up:
    // back closes the story first, not the Replay page under it.
    BackHandler(enabled = !playerOpen && replayStory == null && stack.isNotEmpty()) { back() }
    BackHandler(enabled = !playerOpen && replayStory == null && stack.isEmpty() && tab != 0) { tab = 0 }
    LaunchedEffect(state.current == null) { if (state.current == null && playerOpen) settle(false) }

    // Read through derivedStateOf: these only change at a threshold, so the screen
    // recomposes then, not on every frame of the sheet's drag or spring.
    val sheetMostlyClosed by remember { derivedStateOf { sheet.value > 0.5f } }
    val sheetShowing by remember { derivedStateOf { sheet.value < 1f } }
    val lightBars = !LocalPalette.current.isDark && sheetMostlyClosed
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }

    // ---- the tab bar shrinks to a bubble while scrolling down, and comes back on the way up ----
    var collapsed by remember { mutableStateOf(false) }
    // How far the page on screen has been scrolled down, for the top fade.
    // Kept per page, as the pages keep their own scroll (pageStates): coming back to a
    // list that is already scrolled down keeps its fade.
    val pageScrolls = remember { androidx.compose.runtime.mutableStateMapOf<String, Float>() }
    var pageKey by remember { mutableStateOf("") }
    val scrollWatch = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                pageScrolls[pageKey] = ((pageScrolls[pageKey] ?: 0f) - consumed.y).coerceAtLeast(0f)
                return Offset.Zero
            }
            var run = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy * run < 0) run = 0f
                run += dy
                if (run < -60f) collapsed = true
                if (run > 40f) collapsed = false
                return Offset.Zero
            }
        }
    }
    val page = Page(tab, stack.size, stack.lastOrNull())
    LaunchedEffect(page) { collapsed = false }
    val currentPageKey = "${page.tab}/${page.screen}"
    androidx.compose.runtime.SideEffect { pageKey = currentPageKey }
    // Liquid Glass (BitChord): only the glass bar folds while scrolling; the default bar stays put.
    val liquid = Prefs.liquidGlass.state.collectAsState().value && isGlassSupported()
    val reduceBlur = Prefs.reduceBlur.state.collectAsState().value
    val showCollapsed = liquid && collapsed && state.current != null
    /** How tall the floating bars stand, queue notice included, for the locate button. */
    var barsHeightPx by remember { mutableIntStateOf(0) }
    // The page is recorded into a layer that the frosted/glass bars sample. Not under
    // "Reduce dynamic blur": then nothing samples it and the surfaces fill solid.
    val record = isGlassSupported() && !reduceBlur
    val pageColor = GhostlyColors.Background
    val appBackdrop = rememberLayerBackdrop(onDraw = remember(pageColor) { { drawRect(pageColor); drawContent() } })

    val navBar = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    // Space under the lists so the floating bars never cover the last row.
    val bottomPad = navBar + 8.dp + 64.dp + (if (state.current != null) 64.dp + 10.dp else 0.dp) + 24.dp
    val currentId = state.current?.id

    // BitChord's iPhone-style rubber-band overscroll on every list.
    val iosOverscroll = rememberIosOverscrollFactory()
    CompositionLocalProvider(
        LocalOverscrollFactory provides iosOverscroll,
        LocalIsPlaying provides state.isPlaying,
        LocalAppBackdrop provides if (record) appBackdrop else null,
        LocalLiquidGlassEnabled provides liquid,
    ) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenH = with(LocalDensity.current) { maxHeight.toPx() }
        val drag = Modifier.draggable(
            rememberDraggableState { d -> scope.launch { sheet.snapTo((sheet.value + d / screenH).coerceIn(0f, 1f)) } },
            Orientation.Vertical,
            onDragStopped = { v ->
                val speed = v / screenH
                // A quick flick decides by direction; otherwise moving a fifth of the way is enough.
                val open = when {
                    kotlin.math.abs(speed) > 1.2f -> speed < 0
                    playerOpen -> sheet.value < 0.2f
                    else -> sheet.value < 0.8f
                }
                settle(open, velocity = speed)
            },
        )

        // ---- pages (BitChord): a tab switch is instant; opening or closing a page is a short fade ----
        AnimatedContent(
            targetState = page,
            modifier = Modifier.fillMaxSize()
                .then(if (record) Modifier.layerBackdrop(appBackdrop) else Modifier)
                // Not padded below the status bar: pages run up behind it, under the top
                // fade, as in BitChord, and make their own room at the top (TopBarSpace).
                .nestedScroll(scrollWatch),
            transitionSpec = {
                val tabSwap = targetState.depth == 0 && initialState.depth == 0
                if (!motion || tabSwap) EnterTransition.None togetherWith ExitTransition.None
                else fadeIn(tween(180)) togetherWith fadeOut(tween(180))
            },
            label = "pages",
        ) { p ->
            // Each page keeps its saved state (search text, list scroll) while it is
            // off screen, so coming back from an album finds it as it was left.
            pageStates.SaveableStateProvider("${p.tab}/${p.screen}") {
            when (val top = p.screen) {
                null -> when (p.tab) {
                    0 -> HomeScreen(library, actions, bottomPad, libraryLoading)
                    1 -> LibraryScreen(library, currentId, actions, bottomPad) {
                        LibraryReplayEntry(
                            cards = replayCards,
                            loading = replay.loading,
                            holder = replayHolderName,
                            memberSince = replay.memberSince,
                            onOpenReplay = { stack += Screen.Replay(it) },
                        )
                    }
                    else -> SearchScreen(library, currentId, actions, bottomPad)
                }
                Screen.Settings -> SettingsScreen(library.songs.size, vm::loadLibrary, bottomPad, back,
                    covered = playerOpen || replayStory != null, scanning = libraryLoading,
                    songs = library.songs, onPlay = { vm.play(listOf(it), from = "Duplicates") })
                Screen.Recent -> {
                    val songs = remember(stats, library) {
                        val byId = library.songs.associateBy { it.id }
                        stats.entries.sortedByDescending { it.value.lastPlayed }.mapNotNull { byId[it.key] }
                    }
                    CollectionScreen("Recently Played", "Your listening history", { m, c -> SymbolCover(BitChordIcons.Clock, m, c) },
                        songs, currentId, actions, bottomPad, emptyText = "Songs you play show up here.")
                }
                is Screen.AlbumScreen -> library.albums.find { it.id == top.id }
                    ?.let { AlbumPage(it, currentId, actions, bottomPad) } ?: LaunchedEffect(top) { back() }
                is Screen.ArtistScreen -> library.artists.find { it.name == top.name }
                    ?.let { ArtistPage(it, currentId, actions, bottomPad) } ?: LaunchedEffect(top) { back() }
                is Screen.PlaylistScreen -> {
                    val all by com.ghostmikz.ghostly.data.Playlists.playlists.collectAsState()
                    all.firstOrNull { it.id == top.id }?.let { PlaylistPage(it, library, currentId, actions, bottomPad) }
                        ?: LaunchedEffect(top) { back() }
                }
                is Screen.Replay -> ReplayScreen(
                    state = replay,
                    holder = replayHolderName,
                    onPeriodChange = setReplayPeriod,
                    onOpenStory = { replayStory = it },
                    onPlaySong = { i ->
                        val byId = library.songs.associateBy { it.id }
                        val chart = replay.summary?.songs.orEmpty().mapNotNull { byId[it.song.id] }
                        val start = replay.summary?.songs?.getOrNull(i)?.song?.id
                        actions.play(chart, chart.indexOfFirst { it.id == start }.coerceAtLeast(0), false, "Replay")
                    },
                    onOpenArtist = { name -> if (library.artists.any { it.name == name }) stack += Screen.ArtistScreen(name) },
                    onOpenAlbum = { id -> id?.toLongOrNull()?.takeIf { a -> library.albums.any { it.id == a } }?.let { stack += Screen.AlbumScreen(it) } },
                    onShare = { replayShare = null; showReplayShare = true },
                    // Its heading makes its own room for the status bar (TopBarSpace).
                    contentPadding = PaddingValues(top = 68.dp, bottom = bottomPad),
                    landingPage = top.landing,
                )
                is Screen.FolderScreen -> {
                    val songs = remember(library, top.path) { library.songs.filter { it.folder == top.path } }
                    FolderPage(top.path, songs, currentId, actions, bottomPad)
                }
            }
            }
        }

        // BitChord's top fade: the page's own colour behind the status bar and the round
        // buttons, so what scrolls under them fades out instead of running into the clock.
        // A pushed page has it always; a tab's big heading would be washed out at rest,
        // so there it comes in as the page scrolls.
        val statusTop = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
        val fadeRampPx = with(LocalDensity.current) { TOP_FADE_RAMP.toPx() }
        val topFadeAlpha by remember(stack.isEmpty()) {
            derivedStateOf { if (stack.isNotEmpty()) 1f else ((pageScrolls[pageKey] ?: 0f) / fadeRampPx).coerceIn(0f, 1f) }
        }
        BottomFadeScrim(
            Modifier.align(Alignment.TopCenter).rotate(180f).graphicsLayer { alpha = topFadeAlpha },
            withMiniPlayer = false, inset = statusTop,
        )

        // Round buttons pinned at the top while the page scrolls under them (BitChord).
        // Back goes through the system back stack, so it also leaves Settings sub-pages one at a time.
        val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (stack.isNotEmpty()) TopCircle(Icons.Rounded.ChevronLeft, "Back") { backDispatcher?.onBackPressed() }
            Spacer(Modifier.weight(1f))
            // The page's own buttons (search this list, sort), beside Settings (BitChord).
            if (stack.lastOrNull() != Screen.Settings) {
                PageActions.current.forEach { action ->
                    TopCircle(action.icon, action.description, action.onClick)
                    Spacer(Modifier.width(10.dp))
                }
            }
            if (stack.lastOrNull() != Screen.Settings) TopCircle(Icons.Rounded.Settings, "Settings") { stack += Screen.Settings }
        }

        // Fade the content out under the floating bars (BitChord's BottomFadeScrim).
        BottomFadeScrim(Modifier.align(Alignment.BottomCenter), withMiniPlayer = state.current != null, inset = navBar)

        // Over the page rather than in it, so it can be glass (see LocateTargetFor).
        // Its own spot is just above the full bars; while a queue notice makes the
        // bars taller than that, it rides above the notice instead. Fades with the
        // bars as the player opens, and is gone once it is open.
        val locateShown by remember { derivedStateOf { sheet.value > 0f } }
        if (locateShown) {
            val barsTop = navBar + (if (liquid) 8.dp else 6.dp) + with(LocalDensity.current) { barsHeightPx.toDp() }
            LocatePlayingButton(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = maxOf(bottomPad - 24.dp + 12.dp, barsTop + 12.dp))
                    .graphicsLayer { alpha = sheet.value },
            )
        }

        // The DAC's own volume beside the volume keys while direct USB owns it
        // (BitChord); not over the full player, whose own bar already shows it.
        LaunchedEffect(Unit) { snapshotFlow { sheet.value < 1f }.collect { VolumeHud.suppressed = it } }
        DacVolumeHud()

        // ---- floating bars: the full tab bar with the mini player above it, or (while scrolling down)
        // a small tab bubble with the mini player beside it. The two layouts morph into each other. ----
        if (!liquid) {
            // BitChord's default: the mini player stacked over the frosted tab bar.
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 6.dp)
                    .graphicsLayer { alpha = sheet.value }.onSizeChanged { barsHeightPx = it.height },
            ) {
                // "Song will play next" and the like, right on the mini player (BitChord).
                QueueNoticeHost()
                MiniPlayer(state, vm, onOpen = { settle(true) }, modifier = Modifier.padding(horizontal = 19.dp),
                    sidePadding = 0.dp, dragModifier = drag,
                    onArtPlaced = { miniArtCoords = it }, hideArt = { sheet.value < 1f })
                if (state.current != null) Spacer(Modifier.height(8.dp))
                FloatingTabBar(TABS, selected = tab, onSelect = { i -> stack.clear(); tab = i },
                    modifier = Modifier.padding(horizontal = 19.dp))
            }
        } else {
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp)
                    .graphicsLayer { alpha = sheet.value }.onSizeChanged { barsHeightPx = it.height },
            ) {
            // On the mini player, so it folds down with the bars (BitChord).
            QueueNoticeHost()
            SharedTransitionLayout(Modifier.fillMaxWidth()) {
                AnimatedContent(
                    targetState = showCollapsed,
                    transitionSpec = {
                        if (motion) (fadeIn(tween(220)) togetherWith fadeOut(tween(160))).using(SizeTransform(clip = false))
                        else EnterTransition.None togetherWith ExitTransition.None
                    },
                    label = "bars",
                ) { small ->
                    val mini = Modifier.sharedBounds(rememberSharedContentState("mini"), this@AnimatedContent)
                    val bar = Modifier.sharedBounds(rememberSharedContentState("bar"), this@AnimatedContent)
                    if (!small) {
                        Column(Modifier.fillMaxWidth()) {
                            MiniPlayer(state, vm, onOpen = { settle(true) }, modifier = Modifier.padding(horizontal = 18.dp).then(mini),
                                sidePadding = 0.dp, dragModifier = drag,
                    onArtPlaced = { miniArtCoords = it }, hideArt = { sheet.value < 1f })
                            if (state.current != null) Spacer(Modifier.height(10.dp))
                            GlassTabBar(TABS, selected = tab, onSelect = { i -> stack.clear(); tab = i },
                                modifier = Modifier.padding(horizontal = 18.dp).then(bar))
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            TabBubble(TABS[tab], onClick = { collapsed = false }, modifier = bar)
                            Spacer(Modifier.width(10.dp))
                            MiniPlayer(state, vm, onOpen = { settle(true) }, modifier = Modifier.weight(1f).then(mini),
                                sidePadding = 0.dp, dragModifier = drag,
                    onArtPlaced = { miniArtCoords = it }, hideArt = { sheet.value < 1f })
                        }
                    }
                }
            }
            }
        }

        if (pickerOpen) {
            val pickerSong = pickerForId?.takeIf { it != NEW_PLAYLIST.id }?.let { id -> library.songs.firstOrNull { it.id == id } }
            PlaylistPickerSheet(song = pickerSong, library = library) { pickerOpen = false }
        }

        // ---- the player, sliding up from the bottom and following the finger ----
        if (sheetShowing) {
            // The dim is drawn, not composed, from the sheet's position.
            Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = (1f - sheet.value) * 0.5f)) })
            Box(Modifier.fillMaxSize().offset { IntOffset(0, (sheet.value * screenH).roundToInt()) }) {
                GhostlyTheme(dark = true) {
                    NowPlayingScreen(
                        state, vm, actions, onClose = { settle(false) }, dragModifier = drag,
                        openFraction = { 1f - sheet.value },
                        miniArt = { if (motion) miniArtCoords?.takeIf { it.isAttached }?.boundsInRoot() else null },
                        sheetOffsetPx = { sheet.value * screenH },
                        travelPx = { screenH },
                    )
                }
            }
        }

        // ---- Replay stories, over everything (BitChord mounts them outside the pane) ----
        replayStory?.let { start ->
            replay.summary?.takeUnless { it.isEmpty }?.let { summary ->
                ReplayStories(
                    summary = summary,
                    start = start,
                    onClose = { replayStory = null },
                    onShare = { card -> replayShare = card; showReplayShare = true },
                    paused = showReplayShare,
                )
            }
        }

        // ---- Share the Replay ----
        if (showReplayShare) {
            replay.summary?.takeUnless { it.isEmpty }?.let { summary ->
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { showReplayShare = false },
                    sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    ReplayShareSheet(
                        summary = summary,
                        holder = replayHolderName,
                        memberSince = replay.memberSince,
                        page = replayShare,
                        onDismiss = { showReplayShare = false },
                    )
                }
            }
        }
    }
    }
}

/** A pinned round top button: real glass with Liquid Glass on, frosted otherwise. */
@Composable
private fun TopCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val glass = LocalLiquidGlassEnabled.current
    Box(
        Modifier.size(52.dp).bounceClick(scaleDown = 0.9f, onClick = onClick).clip(CircleShape)
            .then(if (glass) Modifier.liquidGlass(CircleShape) else Modifier.frosted(CircleShape, GhostlyColors.Surface))
            .border(1.dp, GhostlyColors.Card, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (glass) glassContentColor() else GhostlyColors.Text, modifier = Modifier.size(26.dp)) }
}
