package com.ghostmikz.ghostly

import android.Manifest
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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

private sealed interface Screen {
    data object Settings : Screen
    data object Favorites : Screen
    data object Recent : Screen
    data class AlbumScreen(val id: Long) : Screen
    data class ArtistScreen(val name: String) : Screen
    data class FolderScreen(val path: String) : Screen
}

private val audioPermission =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

class MainActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        History.init(this)
        setContent { GhostlyTheme { App(vm) } }
    }
}

@Composable
private fun App(vm: PlayerViewModel) {
    val context = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasPermission = granted() }
    fun ask() {
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
        Text("Your music,\nright here.", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1).sp, lineHeight = 44.sp)
        Spacer(Modifier.height(12.dp))
        Text("Ghostly plays the audio files on this phone. Allow access to your music to get started.",
            color = GhostlyColors.TextSecondary, fontSize = 17.sp)
        Spacer(Modifier.height(32.dp))
        Box(
            Modifier.fillMaxWidth().height(54.dp).clip(CircleShape).background(Color.White).clickable(onClick = onAllow),
            contentAlignment = Alignment.Center,
        ) { Text("Allow access", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Main(vm: PlayerViewModel) {
    val library by vm.library.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val stats by History.stats.collectAsStateWithLifecycle()
    val favIds by History.favorites.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val stack = remember { mutableStateListOf<Screen>() }
    var playerOpen by rememberSaveable { mutableStateOf(false) }

    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    val actions = remember(vm) {
        LibraryActions(
            play = { songs, i, shuffle, from -> vm.play(songs, i, shuffle, from) },
            playNext = { vm.playNext(it) },
            openAlbum = { stack += Screen.AlbumScreen(it) },
            openArtist = { stack += Screen.ArtistScreen(it) },
            openFolder = { stack += Screen.FolderScreen(it) },
            openFavorites = { stack += Screen.Favorites },
            openRecent = { stack += Screen.Recent },
            openSettings = { stack += Screen.Settings },
            back = back,
        )
    }

    BackHandler(enabled = playerOpen) { playerOpen = false }
    BackHandler(enabled = !playerOpen && stack.isNotEmpty()) { back() }
    BackHandler(enabled = !playerOpen && stack.isEmpty() && tab != 0) { tab = 0 }
    if (state.current == null && playerOpen) playerOpen = false

    val navBar = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    // Space under the lists so the floating bars never cover the last row.
    val bottomPad = navBar + 8.dp + 64.dp + (if (state.current != null) 64.dp + 10.dp else 0.dp) + 24.dp
    val currentId = state.current?.id

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            when (val top = stack.lastOrNull()) {
                null -> when (tab) {
                    0 -> HomeScreen(library, actions, bottomPad)
                    1 -> LibraryScreen(library, currentId, actions, bottomPad)
                    else -> SearchScreen(library, currentId, actions, bottomPad)
                }
                Screen.Settings -> SettingsScreen(library.songs.size, vm::loadLibrary, bottomPad, back)
                Screen.Favorites -> {
                    val songs = remember(favIds, library) { library.songs.filter { it.id in favIds } }
                    CollectionScreen("Favorites", "Songs you starred", { SymbolCover(BitChordIcons.StarFilled, it) },
                        songs, currentId, actions, bottomPad, emptyText = "Tap the star on the player, or \"Add to favorites\" in a song's menu.")
                }
                Screen.Recent -> {
                    val songs = remember(stats, library) {
                        val byId = library.songs.associateBy { it.id }
                        stats.entries.sortedByDescending { it.value.lastPlayed }.mapNotNull { byId[it.key] }
                    }
                    CollectionScreen("Recently Played", "Your listening history", { SymbolCover(BitChordIcons.Clock, it) },
                        songs, currentId, actions, bottomPad, emptyText = "Songs you play show up here.")
                }
                is Screen.AlbumScreen -> library.albums.find { it.id == top.id }
                    ?.let { AlbumPage(it, currentId, actions, bottomPad) } ?: LaunchedEffect(top) { back() }
                is Screen.ArtistScreen -> library.artists.find { it.name == top.name }
                    ?.let { ArtistPage(it, currentId, actions, bottomPad) } ?: LaunchedEffect(top) { back() }
                is Screen.FolderScreen -> {
                    val songs = remember(library, top.path) { library.songs.filter { it.folder == top.path } }
                    FolderPage(top.path, songs, currentId, actions, bottomPad)
                }
            }
        }

        // Fade the content out under the floating bars.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomPad)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))),
        )

        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp)) {
            MiniPlayer(state, vm, onOpen = { playerOpen = true })
            if (state.current != null) Spacer(Modifier.height(10.dp))
            FloatingTabBar(
                listOf(
                    TabItem("Home", BitChordIcons.Home),
                    TabItem("Library", BitChordIcons.TabLibrary),
                    TabItem("Search", BitChordIcons.TabSearch),
                ),
                selected = tab,
                onSelect = { i -> stack.clear(); tab = i },
            )
        }

        AnimatedVisibility(
            visible = playerOpen,
            enter = slideInVertically(tween(320)) { it },
            exit = slideOutVertically(tween(260)) { it },
        ) {
            NowPlayingScreen(state, vm, actions, onClose = { playerOpen = false })
        }
    }
}
