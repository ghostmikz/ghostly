package com.ghostmikz.ghostly.ui

import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import com.ghostmikz.ghostly.data.stats.ListeningStats
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostmikz.ghostly.BuildConfig
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.HideSource
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwipeRight
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.VolumeMute
import com.ghostmikz.ghostly.data.BoolPref
import com.ghostmikz.ghostly.data.History
import com.ghostmikz.ghostly.playback.PlaybackService
import org.json.JSONObject
import com.ghostmikz.ghostly.data.Prefs
import com.ghostmikz.ghostly.lastfm.LastFM
import com.ghostmikz.ghostly.ui.icons.BitChordIcons
import kotlinx.coroutines.launch

// Laid out like BitChord's settings: a search bar, rounded cards of rows with an icon,
// title, grey subtitle and a chevron or switch, grey UPPERCASE section labels, and sub-pages.

private enum class Page(val title: String) {
    Main("Settings"), Account("Account & integrations"), Playback("Playback"), Appearance("Appearance"),
    Local("Local Music"), Storage("Storage"), Data("Your data"), Misc("Miscellaneous"),
}

@Composable
fun SettingsScreen(songCount: Int, onRescan: () -> Unit, bottomPad: Dp, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(Page.Main) }
    BackHandler(enabled = page != Page.Main) { page = Page.Main }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomPad)) {
        TopBarSpace()
        Text(page.title, style = MaterialTheme.typography.displayLarge, color = GhostlyColors.Text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        when (page) {
            Page.Main -> MainPage(songCount) { page = it }
            Page.Account -> AccountPage()
            Page.Playback -> PlaybackPage()
            Page.Appearance -> AppearancePage()
            Page.Local -> LocalPage(songCount, onRescan)
            Page.Storage -> StoragePage()
            Page.Data -> DataPage()
            Page.Misc -> MiscPage()
        }
    }
}

// ---------- pages ----------

@Composable
private fun MainPage(songCount: Int, open: (Page) -> Unit) {
    val user by Prefs.lastfmUser.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current

    SearchField(query) { query = it }

    // Every row the search can find, with the page it lives on.
    data class Entry(val icon: ImageVector, val title: String, val words: String, val page: Page)
    val entries = listOf(
        Entry(Icons.Outlined.Person, "Account & integrations", "last.fm lastfm scrobble scrobbling now playing sign in account", Page.Account),
        Entry(Icons.Outlined.PlayCircle, "Playback", "skip silence equalizer eq bass audio", Page.Playback),
        Entry(Icons.Outlined.Brush, "Appearance", "liquid glass glass performance theme dark mode light mode system reduce animation blur blend colors full-screen cover art album", Page.Appearance),
        Entry(Icons.Outlined.LibraryMusic, "Local Music", "rescan scan library songs folders filter non-music recordings wav", Page.Local),
        Entry(Icons.Outlined.Storage, "Storage", "clear image cache artwork space", Page.Storage),
        Entry(Icons.Outlined.Inventory2, "Your data", "clear history favorites export import backup json", Page.Data),
        Entry(Icons.Outlined.MoreHoriz, "Miscellaneous", "swipe play next stop close recents hide volume status bar queue history playing from stats for nerds bitrate", Page.Misc),
    )
    val q = query.trim()
    if (q.isNotEmpty()) {
        val hits = entries.filter { it.title.contains(q, true) || it.words.contains(q, true) }
        Spacer(Modifier.height(20.dp))
        if (hits.isEmpty()) {
            Text("No settings match \"$q\".", color = GhostlyColors.TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 28.dp))
        } else Card {
            hits.forEachIndexed { i, e ->
                NavRow(e.icon, e.title, null, last = i == hits.lastIndex) { open(e.page) }
            }
        }
        return
    }

    Spacer(Modifier.height(20.dp))
    Card {
        NavRow(Icons.Outlined.Person, "Account & integrations",
            if (user.isBlank()) "Last.fm: not signed in" else "Last.fm: $user", last = true) { open(Page.Account) }
    }
    Spacer(Modifier.height(28.dp))
    Card {
        NavRow(Icons.Outlined.PlayCircle, "Playback", null) { open(Page.Playback) }
        NavRow(Icons.Outlined.Brush, "Appearance", null) { open(Page.Appearance) }
        NavRow(Icons.Outlined.LibraryMusic, "Local Music", null, value = "$songCount") { open(Page.Local) }
        NavRow(Icons.Outlined.Storage, "Storage", null) { open(Page.Storage) }
        NavRow(Icons.Outlined.Inventory2, "Your data", null) { open(Page.Data) }
        NavRow(Icons.Outlined.MoreHoriz, "Miscellaneous", null, last = true) { open(Page.Misc) }
    }

    // footer like BitChord's "bitchord 1.9.1  GitHub  Developer"
    Row(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("ghostly ${BuildConfig.VERSION_NAME}", color = GhostlyColors.TextSecondary, fontSize = 14.sp)
        Spacer(Modifier.width(10.dp))
        Text("GitHub", color = GhostlyColors.Text, fontSize = 14.sp, textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ghostmikz/ghostly"))) }
            })
    }
    Text("Design and Last.fm code adapted from BitChord (GPLv3)", color = GhostlyColors.TextSecondary, fontSize = 13.sp,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 24.dp, end = 24.dp))
}

/** BitChord's AccountAndScrobblingScreen, Last.fm only (Ghostly has no other accounts). */
@Composable
private fun AccountPage() {
    val lastfmEnabled by Prefs.lastfmEnabled.state.collectAsState()
    val lastfmUsername by Prefs.lastfmUser.collectAsState()
    val lastfmScrobbleEnabled by Prefs.scrobbleOn.state.collectAsState()
    val lastfmNowPlayingEnabled by Prefs.nowPlayingOn.state.collectAsState()
    val lastfmPrimaryArtistOnly by Prefs.lastfmPrimaryArtistOnly.state.collectAsState()
    val scrobbleMinDuration by Prefs.scrobbleMinDuration.state.collectAsState()
    val scrobbleDelayPercent by Prefs.scrobbleDelayPercent.state.collectAsState()
    val scrobbleDelaySeconds by Prefs.scrobbleDelaySeconds.state.collectAsState()
    var showLogin by remember { mutableStateOf(false) }
    val signedIn = lastfmUsername.isNotBlank()

    Label("Scrobbling")
    Card {
        SwitchRow(
            Icons.Rounded.History, "Last.fm",
            when {
                !Prefs.lastfmAvailable -> "Add LASTFM_API_KEY and LASTFM_SECRET to local.properties"
                signedIn -> "Signed in as $lastfmUsername"
                else -> "Tap to sign in"
            },
            checked = lastfmEnabled,
            enabled = Prefs.lastfmAvailable,
            last = !(lastfmEnabled && signedIn),
            onClick = { if (signedIn) Prefs.unlinkLastfm() else showLogin = true },
        ) { checked -> if (checked && !signedIn) showLogin = true else Prefs.lastfmEnabled.set(checked) }
        if (lastfmEnabled && signedIn) {
            SwitchRow(Icons.Rounded.GraphicEq, "Scrobble tracks", "Log plays to your Last.fm timeline",
                checked = lastfmScrobbleEnabled) { Prefs.setScrobble(it) }
            SwitchRow(Icons.Rounded.GraphicEq, "Only scrobble primary artist", "Use the first artist when a track credits multiple artists",
                checked = lastfmPrimaryArtistOnly) { Prefs.lastfmPrimaryArtistOnly.set(it) }
            SwitchRow(Icons.Rounded.GraphicEq, "Now playing", "Update Last.fm with what you are listening to",
                checked = lastfmNowPlayingEnabled, last = true) { Prefs.setNowPlaying(it) }
        }
    }
    Note("Scrobble your listens to Last.fm.")

    if (lastfmEnabled && signedIn) {
        Label("Scrobble timing")
        Card {
            SliderRow(Icons.Rounded.Tune, "Minimum song duration", "Shorter songs will not scrobble",
                "${scrobbleMinDuration}s", scrobbleMinDuration.toFloat(), { Prefs.scrobbleMinDuration.set(it.roundToInt()) },
                15f..120f, 20)
            SliderRow(Icons.Rounded.Tune, "Scrobble delay", "How far into a song to scrobble it",
                "${(scrobbleDelayPercent * 100).roundToInt()}%", scrobbleDelayPercent, { Prefs.scrobbleDelayPercent.set(it) },
                0.1f..1.0f, 8)
            SliderRow(Icons.Rounded.Tune, "Maximum delay", "Maximum scrobble delay in seconds",
                "${scrobbleDelaySeconds}s", scrobbleDelaySeconds.toFloat(), { Prefs.scrobbleDelaySeconds.set(it.roundToInt()) },
                30f..300f, 26, last = true)
        }
    }
    Spacer(Modifier.height(24.dp))

    if (showLogin) LastfmLoginAlert { showLogin = false }
}

@Composable
private fun PlaybackPage() {
    val context = LocalContext.current
    Spacer(Modifier.height(12.dp))
    Card {
        PrefRow(Icons.Outlined.VolumeOff, "Skip silence", "Trim gaps longer than a second", Prefs.skipSilenceOn)
        NavRow(Icons.Outlined.Equalizer, "Equalizer", "Tone and bands, from your phone's equalizer", last = true) {
            val opened = runCatching {
                context.startActivity(
                    Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, PlaybackService.audioSessionId)
                        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
                )
            }.isSuccess
            if (!opened) Toast.makeText(context, "This phone has no equalizer app", Toast.LENGTH_SHORT).show()
        }
    }
    Note("Ghostly always pauses when headphones are unplugged.")
}

@Composable
private fun AppearancePage() {
    val modes = listOf("system", "light", "dark")
    val mode by Prefs.theme.collectAsState()
    Spacer(Modifier.height(12.dp))
    Card {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Brightness4, null, tint = GhostlyColors.Text, modifier = Modifier.size(26.dp))
            Text("Theme", color = GhostlyColors.Text, fontSize = 18.sp, modifier = Modifier.padding(start = 18.dp))
        }
        SegmentedTabs(
            listOf("System", "Light", "Dark"), modes.indexOf(mode).coerceAtLeast(0), { Prefs.setTheme(modes[it]) },
            Modifier.padding(bottom = 16.dp), container = GhostlyColors.CardHigh,
        )
    }
    Spacer(Modifier.height(20.dp))
    Card {
        PrefRow(Icons.Outlined.Animation, "Reduce animation", "Keeps the cover still when paused and opens the player instantly", Prefs.reduceAnimation)
        PrefRow(Icons.Outlined.BlurOn, "Reduce dynamic blur", "Swaps frosted glass for solid fills across the app", Prefs.reduceBlur)
        PrefRow(Icons.Outlined.Gradient, "Disable blend effects", "Plain dark player instead of tinting it with the artwork", Prefs.noBlend)
        val liquidOn by Prefs.liquidGlass.state.collectAsState()
        val supported = isGlassSupported()
        SwitchRow(
            Icons.Outlined.BubbleChart, "Liquid Glass",
            if (supported) "Real refracting glass on the floating nav bar. Android 12+ only; uses more GPU." else "Requires Android 12 or newer",
            checked = liquidOn && supported, enabled = supported, last = !(liquidOn && supported),
        ) { Prefs.liquidGlass.set(it) }
        if (liquidOn && supported) {
            PrefRow(Icons.Outlined.Speed, "Better Performance for Glass", "Frosted glass without the lens, for slower phones",
                Prefs.glassPerformance, last = true)
        }
    }
    Label("Covers")
    Card {
        PrefRow(Icons.Outlined.Fullscreen, "Full-screen cover art", "Runs the cover to the edges of the player instead of a square sleeve", Prefs.fullCover)
        PrefRow(Icons.Outlined.Album, "Full-screen album cover", "Runs album covers to the edges of the page instead of a framed sleeve", Prefs.fullAlbumCover, last = true)
    }
}

@Composable
private fun StoragePage() {
    val context = LocalContext.current
    Spacer(Modifier.height(12.dp))
    Card {
        NavRow(Icons.Outlined.Image, "Clear image cache", "Frees memory used by album artwork", last = true, chevron = false) {
            ArtCache.clear()
            Toast.makeText(context, "Image cache cleared", Toast.LENGTH_SHORT).show()
        }
    }
    Note("Ghostly keeps no copies of your music. Covers are read from the files and kept in memory while the app runs.")
}

@Composable
private fun MiscPage() {
    Spacer(Modifier.height(12.dp))
    Card {
        PrefRow(Icons.Outlined.SwipeRight, "Play next on swipe", "Swiping a song to the right plays it next", Prefs.swipePlayNext)
        PrefRow(Icons.Outlined.StopCircle, "Stop music on close from recents", "Stops playback when swiped away from recent apps", Prefs.stopOnClose, last = true)
    }
    Label("Player")
    Card {
        PrefRow(Icons.Outlined.VolumeMute, "Hide volume bar", "Removes the volume slider from the main player", Prefs.hideVolume)
        PrefRow(Icons.Outlined.HideSource, "Hide status bar in player", "Hides the clock, battery and notification icons while the player is open", Prefs.hideStatusBar)
        PrefRow(Icons.Rounded.History, "Show queue history", "Shows already played songs above the current one in the queue", Prefs.queueHistory)
        PrefRow(Icons.Outlined.TextFields, "Hide song status", "Hides the \"Playing from\" text in the main player", Prefs.hideSongStatus)
        PrefRow(Icons.Outlined.Insights, "Show stats for nerds", "Format, bitrate and sample rate on the player", Prefs.nerdStats, last = true)
    }
}

@Composable
private fun LocalPage(songCount: Int, onRescan: () -> Unit) {
    var scanned by remember { mutableStateOf(false) }
    Spacer(Modifier.height(12.dp))
    Card {
        PrefRow(Icons.Outlined.FilterAlt, "Filter non-music audio",
            "Hides clips under 30 seconds, WAV files, voice notes, recordings and system sounds", Prefs.filterNonMusic) { onRescan() }
    }
    Spacer(Modifier.height(20.dp))
    Card {
        NavRow(Icons.Outlined.LibraryMusic, "Songs on this phone", null, value = "$songCount", chevron = false) {}
        NavRow(Icons.Outlined.Refresh, "Rescan music", if (scanned) "Done" else "Look for new or removed files", last = true, chevron = false) {
            onRescan(); scanned = true
        }
    }
    Note("Ghostly plays the audio files Android knows about, from every folder on the phone.")
}

@Composable
private fun DataPage() {
    val stats by History.stats.collectAsState()
    val favs by History.favorites.collectAsState()
    var ask by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val json = JSONObject().put("app", "ghostly").put("version", 1)
                    .put("settings", Prefs.exportJson()).put("history", History.exportJson())
                    .put("playlists", com.ghostmikz.ghostly.data.Playlists.exportJson())
                    // Replay's monthly numbers, as BitChord's Backup carries them.
                    .put("listening", org.json.JSONArray(ListeningStats.exportJson()))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(json.toString(2).toByteArray()) }
                }
            }.isSuccess
            Toast.makeText(context, if (ok) "Data exported" else "Could not export", Toast.LENGTH_SHORT).show()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            val json = JSONObject(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
            require(json.optString("app") == "ghostly")
            json.optJSONObject("settings")?.let(Prefs::importJson)
            json.optJSONObject("history")?.let(History::importJson)
            json.optJSONObject("playlists")?.let(com.ghostmikz.ghostly.data.Playlists::importJson)
            json.optJSONArray("listening")?.let { ListeningStats.importJson(it.toString()) }
        }.isSuccess
        Toast.makeText(context, if (ok) "Data imported" else "That file is not a Ghostly export", Toast.LENGTH_SHORT).show()
    }
    Spacer(Modifier.height(12.dp))
    Card {
        NavRow(Icons.Outlined.FileUpload, "Export data", "Settings, listening history, playlists and Replay, as one JSON file", chevron = false) {
            exporter.launch("ghostly-backup.json")
        }
        NavRow(Icons.Outlined.FileDownload, "Import data", "Replaces the settings and history on this device", last = true, chevron = false) {
            importer.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
    }
    Spacer(Modifier.height(20.dp))
    Card {
        NavRow(Icons.Rounded.History, "Clear listening history", countText(stats.size, "song") + " in history", chevron = false) { ask = "history" }
        NavRow(Icons.Outlined.StarOutline, "Clear favorites", countText(favs.size, "song") + " starred", last = true, chevron = false) { ask = "favorites" }
    }
    Note("Your history and favorites only live on this phone. They are used for Home (Recently Played, Most Played). Exports leave out your Last.fm sign-in.")

    when (ask) {
        "history" -> Confirm("Clear listening history?", "Recently Played and Most Played start over.", "Clear",
            onConfirm = { History.clearHistory(); ask = null }, onDismiss = { ask = null })
        "favorites" -> Confirm("Clear favorites?", "All stars are removed.", "Clear",
            onConfirm = { History.clearFavorites(); ask = null }, onDismiss = { ask = null })
    }
}

// ---------- building blocks ----------

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 12.dp).height(52.dp)
            .clip(RoundedCornerShape(16.dp)).background(GhostlyColors.Card).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BitChordIcons.Search, null, tint = GhostlyColors.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search settings", color = GhostlyColors.TextSecondary, fontSize = 18.sp)
            BasicTextField(value, onChange, singleLine = true,
                textStyle = TextStyle(color = GhostlyColors.Text, fontSize = 18.sp, fontFamily = Inter),
                cursorBrush = SolidColor(GhostlyColors.Text), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) Icon(Icons.Rounded.Cancel, "Clear", tint = GhostlyColors.TextSecondary,
            modifier = Modifier.size(22.dp).clickable { onChange("") })
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp)).background(GhostlyColors.Card), content = content)
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), color = GhostlyColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 30.dp, top = 30.dp, bottom = 10.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, color = GhostlyColors.TextSecondary, fontSize = 15.sp, lineHeight = 20.sp,
        modifier = Modifier.padding(start = 30.dp, end = 24.dp, top = 10.dp))
}

/** Icon + title (+ grey subtitle) + optional value + chevron; divider starts at the text. */
@Composable
private fun NavRow(
    icon: ImageVector, title: String, subtitle: String?, value: String? = null,
    last: Boolean = false, chevron: Boolean = true, onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = GhostlyColors.Text, modifier = Modifier.padding(start = 20.dp).size(26.dp))
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Row(Modifier.padding(vertical = if (subtitle == null) 18.dp else 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = GhostlyColors.Text, fontSize = 18.sp)
                    if (subtitle != null) Text(subtitle, color = GhostlyColors.TextSecondary, fontSize = 15.sp)
                }
                if (value != null) Text(value, color = GhostlyColors.TextSecondary, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp))
                if (chevron) Icon(BitChordIcons.ChevronRight, null, tint = GhostlyColors.TextSecondary,
                    modifier = Modifier.padding(start = 10.dp).size(16.dp))
                Spacer(Modifier.width(18.dp))
            }
            if (!last) HorizontalDivider(thickness = 0.5.dp, color = GhostlyColors.CardHigh)
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector, title: String, subtitle: String?, checked: Boolean,
    enabled: Boolean = true, last: Boolean = false,
    /** What tapping the row (not the switch) does; flips the switch when null. */
    onClick: (() -> Unit)? = null,
    onChange: (Boolean) -> Unit,
) {
    val fade = if (enabled) 1f else 0.4f
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick?.invoke() ?: onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = GhostlyColors.Text.copy(alpha = fade), modifier = Modifier.padding(start = 20.dp).size(26.dp))
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Row(Modifier.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(title, color = GhostlyColors.Text.copy(alpha = fade), fontSize = 18.sp)
                    if (subtitle != null) Text(subtitle, color = GhostlyColors.TextSecondary.copy(alpha = fade), fontSize = 15.sp)
                }
                Switch(
                    checked, onChange, enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF34C759), checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = GhostlyColors.SwitchOff, uncheckedBorderColor = Color.Transparent,
                        disabledUncheckedThumbColor = Color.White.copy(alpha = 0.5f), disabledUncheckedTrackColor = GhostlyColors.SwitchOff.copy(alpha = 0.5f),
                        disabledUncheckedBorderColor = Color.Transparent,
                    ),
                    thumbContent = {},
                )
                Spacer(Modifier.width(16.dp))
            }
            if (!last) HorizontalDivider(thickness = 0.5.dp, color = GhostlyColors.CardHigh)
        }
    }
}

/** BitChord's SliderRow: icon, title, subtitle and the value, a bare slider underneath. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderRow(
    icon: ImageVector, title: String, subtitle: String?, value: String,
    sliderValue: Float, onSliderValue: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>, steps: Int, last: Boolean = false,
) {
    val colors = SliderDefaults.colors(
        thumbColor = GhostlyColors.Text, activeTrackColor = GhostlyColors.Text, inactiveTrackColor = GhostlyColors.CardHigh,
    )
    Row(Modifier.fillMaxWidth()) {
        Icon(icon, null, tint = GhostlyColors.Text, modifier = Modifier.padding(start = 20.dp, top = 14.dp).size(26.dp))
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Row(Modifier.padding(top = 12.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = GhostlyColors.Text, fontSize = 18.sp)
                    if (subtitle != null) Text(subtitle, color = GhostlyColors.TextSecondary, fontSize = 15.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text(value, color = GhostlyColors.TextSecondary, fontSize = 18.sp)
            }
            Slider(
                value = sliderValue, onValueChange = onSliderValue, valueRange = valueRange, steps = steps, colors = colors,
                // Bare track: the step ticks and the end-stop dot are noise when the
                // value is already spelled out on the line above.
                track = { state ->
                    SliderDefaults.Track(sliderState = state, colors = colors, drawStopIndicator = null, drawTick = { _, _ -> })
                },
                modifier = Modifier.padding(end = 18.dp),
            )
            if (!last) HorizontalDivider(thickness = 0.5.dp, color = GhostlyColors.CardHigh)
        }
    }
}

/** A switch row bound to one saved setting. [after] runs after it changes (e.g. rescan). */
@Composable
private fun PrefRow(icon: ImageVector, title: String, subtitle: String, pref: BoolPref, last: Boolean = false, after: () -> Unit = {}) {
    val on by pref.state.collectAsState()
    SwitchRow(icon, title, subtitle, checked = on, last = last) { pref.set(it); after() }
}

@Composable
private fun Confirm(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = GhostlyColors.Card,
        title = { Text(title, color = GhostlyColors.Text) },
        text = { Text(text, color = GhostlyColors.TextSecondary) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = GhostlyColors.Accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = GhostlyColors.Text) } },
    )
}

/**
 * BitChord's LastfmLoginAlert: the UIAlertController shape (AlertScaffold, pill
 * fields, hairline rules, stacked actions). In a window of its own, so the card
 * is BitChord's plain-surface variant rather than frosted.
 */
@Composable
private fun LastfmLoginAlert(onDismiss: () -> Unit) {
    var usernameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val signIn: () -> Unit = {
        loading = true
        error = null
        scope.launch {
            try {
                LastFM.initialize(apiKey = BuildConfig.LASTFM_API_KEY, secret = BuildConfig.LASTFM_SECRET)
                LastFM.getMobileSession(usernameInput.trim(), passwordInput)
                    .onSuccess { auth ->
                        Prefs.linkLastfm(auth.session.name, auth.session.key)
                        onDismiss()
                    }
                    .onFailure { e -> error = e.message ?: "Login failed" }
            } catch (e: Exception) {
                error = e.message ?: "Login failed"
            } finally {
                loading = false
            }
        }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (!loading) onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.width(270.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Last.fm login",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                    color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
                )
                Text(
                    error ?: "Sign in with your Last.fm account to enable scrobbling.",
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                PillTextField(usernameInput, { usernameInput = it }, "Username", enabled = !loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                Spacer(Modifier.height(8.dp))
                PillTextField(
                    passwordInput, { passwordInput = it }, "Password", enabled = !loading, isPassword = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (usernameInput.isNotBlank() && passwordInput.isNotBlank()) signIn() }),
                )
            }
            AlertRule()
            AlertAction(if (loading) "Signing in…" else "Sign in", emphasised = true, onClick = signIn,
                enabled = !loading && usernameInput.isNotBlank() && passwordInput.isNotBlank())
            AlertRule()
            AlertAction("Cancel", emphasised = false, onClick = onDismiss, enabled = !loading)
        }
    }
}

/** BitChord's PillTextField: flat surfaceVariant, 11dp radius, no outline, no floating label. */
@Composable
private fun PillTextField(
    value: String, onValueChange: (String) -> Unit, placeholder: String,
    enabled: Boolean = true, isPassword: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Box(
        Modifier.fillMaxWidth().height(40.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** BitChord's AlertAction: a full-width row that washes on press instead of rippling. */
@Composable
private fun AlertAction(label: String, emphasised: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        Modifier.fillMaxWidth().height(44.dp)
            .background(if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(enabled = enabled, indication = null, interactionSource = interactionSource, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = if (emphasised) FontWeight.W600 else FontWeight.W400),
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

/** BitChord's AlertRule: a hairline separator. */
@Composable
private fun AlertRule() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)))
}

