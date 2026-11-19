package com.ghostmikz.tune

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostmikz.tune.data.Prefs
import com.ghostmikz.tune.ui.*

private sealed interface Screen {
    data object Settings : Screen
    data class AlbumPage(val id: Long) : Screen
    data class ArtistPage(val name: String) : Screen
    data class FolderPage(val path: String) : Screen
}

private val audioPermission =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

class MainActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContent { TuneTheme { App(vm) } }
    }
}

@Composable
private fun TuneTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFF2B8D6), secondary = Color(0xFFCBC2DB))
        else -> lightColorScheme(primary = Color(0xFF8E4A6E), secondary = Color(0xFF625B71))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun App(vm: PlayerViewModel) {
    val context = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = granted()
    }
    fun ask() {
        val perms = buildList {
            add(audioPermission)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        launcher.launch(perms.toTypedArray())
    }
    LaunchedEffect(Unit) { if (!hasPermission) ask() }
    LaunchedEffect(hasPermission) { if (hasPermission) vm.loadLibrary() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!hasPermission) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Tune needs access to your music", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("It only reads audio files on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Button(onClick = ::ask) { Text("Allow access") }
            }
        } else {
            Main(vm)
        }
    }
}

@Composable
private fun Main(vm: PlayerViewModel) {
    val library by vm.library.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val stack = remember { mutableStateListOf<Screen>() }
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }

    val actions = remember(vm) {
        LibraryActions(
            play = { songs, i, shuffle -> vm.play(songs, i, shuffle) },
            playNext = { vm.playNext(it) },
            openAlbum = { stack += Screen.AlbumPage(it) },
            openArtist = { stack += Screen.ArtistPage(it) },
            openFolder = { stack += Screen.FolderPage(it) },
        )
    }

    BackHandler(enabled = stack.isNotEmpty() && !nowPlayingOpen) { stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = nowPlayingOpen) { nowPlayingOpen = false }
    if (state.current == null && nowPlayingOpen) nowPlayingOpen = false

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Room at the bottom of lists so the mini player never covers the last row.
            val bottomPad = PaddingValues(bottom = if (state.current != null) 96.dp else 16.dp)
            Box(Modifier.weight(1f)) {
                val back = { stack.removeAt(stack.lastIndex); Unit }
                when (val top = stack.lastOrNull()) {
                    null -> HomeScreen(library, loading, state.current?.id, actions, { stack += Screen.Settings }, bottomPad)
                    Screen.Settings -> SettingsScreen(library.songs.size, vm::loadLibrary, bottomPad, back)
                    is Screen.AlbumPage -> library.albums.find { it.id == top.id }
                        ?.let { AlbumScreen(it, state.current?.id, actions, bottomPad, back) } ?: LaunchedEffect(top) { back() }
                    is Screen.ArtistPage -> library.artists.find { it.name == top.name }
                        ?.let { ArtistScreen(it, state.current?.id, actions, bottomPad, back) } ?: LaunchedEffect(top) { back() }
                    is Screen.FolderPage -> {
                        val songs = remember(library, top.path) { library.songs.filter { it.folder == top.path } }
                        FolderScreen(top.path, songs, state.current?.id, actions, bottomPad, back)
                    }
                }
            }
        }

        MiniPlayer(
            state, vm, onOpen = { nowPlayingOpen = true },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )

        AnimatedVisibility(
            visible = nowPlayingOpen,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen(
                state, vm,
                onClose = { nowPlayingOpen = false },
                onOpenArtist = { nowPlayingOpen = false; stack += Screen.ArtistPage(it) },
            )
        }
    }
}
