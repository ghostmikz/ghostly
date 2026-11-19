package com.ghostmikz.tune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostmikz.tune.BuildConfig
import com.ghostmikz.tune.data.Prefs
import com.ghostmikz.tune.lastfm.LastFM
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(songCount: Int, onRescan: () -> Unit, bottomPad: Dp, onBack: () -> Unit) {
    val user by Prefs.lastfmUser.collectAsState()
    val scrobble by Prefs.scrobble.collectAsState()
    val nowPlaying by Prefs.nowPlaying.collectAsState()
    var showLogin by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomPad)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) { CircleButton(Icons.Rounded.ChevronLeft, "Back", 52.dp, onBack) }
        BigTitle("Settings")

        GroupLabel("Last.fm")
        Group {
            when {
                !Prefs.lastfmAvailable -> InfoRow("Not set up in this build", "Add LASTFM_API_KEY and LASTFM_SECRET to local.properties and rebuild.")
                user.isBlank() -> ActionRow("Sign in to Last.fm", "Scrobble what you listen to", "Sign in", filled = true) { showLogin = true }
                else -> {
                    ActionRow(user, "Linked", "Sign out", filled = false, onClick = Prefs::unlinkLastfm)
                    Divider()
                    SwitchRow("Scrobble", "Send songs to your profile after half of the song (or 4 minutes) has played", scrobble, Prefs::setScrobble)
                    Divider()
                    SwitchRow("Show \"Now Playing\"", "Show the current song on your profile", nowPlaying && scrobble, Prefs::setNowPlaying, enabled = scrobble)
                }
            }
        }

        GroupLabel("Library")
        Group { ActionRow("Rescan music", countText(songCount, "song") + " found", "Rescan", filled = false, onClick = onRescan) }

        GroupLabel("About")
        Group {
            InfoRow("Tune ${BuildConfig.VERSION_NAME}",
                "Offline music player. GPLv3. Design and Last.fm code adapted from BitChord by kushagrasinghx. Font: Inter (OFL).")
        }
    }

    if (showLogin) LastfmLoginDialog { showLogin = false }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text.uppercase(), color = TuneColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 8.dp))
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(TuneColors.Card), content = content)
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = TuneColors.CardHigh)

@Composable
private fun InfoRow(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, color = Color.White, fontSize = 17.sp)
        Text(text, color = TuneColors.TextSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun ActionRow(title: String, text: String, button: String, filled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 17.sp)
            Text(text, color = TuneColors.TextSecondary, fontSize = 14.sp)
        }
        Box(
            Modifier.clip(CircleShape).background(if (filled) Color.White else TuneColors.CardHigh)
                .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp),
        ) { Text(button, color = if (filled) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun SwitchRow(title: String, text: String, on: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = if (enabled) Color.White else TuneColors.TextSecondary, fontSize = 17.sp)
            Text(text, color = TuneColors.TextSecondary, fontSize = 14.sp)
        }
        Switch(
            on, onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF34C759),
                uncheckedThumbColor = Color.White, uncheckedTrackColor = TuneColors.CardHigh, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun LastfmLoginDialog(onDismiss: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color.White, unfocusedBorderColor = TuneColors.CardHigh,
        focusedLabelColor = Color.White, cursorColor = Color.White,
    )

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        containerColor = TuneColors.Card,
        title = { Text("Sign in to Last.fm", color = Color.White) },
        text = {
            Column {
                error?.let { Text(it, color = TuneColors.Accent, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true,
                    colors = fieldColors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    password, { password = it }, label = { Text("Password") }, singleLine = true, colors = fieldColors,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show password")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("Your password is sent to Last.fm once to get a session. It is not saved.",
                    color = TuneColors.TextSecondary, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && username.isNotBlank() && password.isNotBlank(),
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        LastFM.getMobileSession(username.trim(), password)
                            .onSuccess { Prefs.linkLastfm(it.session.name, it.session.key); onDismiss() }
                            .onFailure { error = it.message ?: "Sign in failed" }
                        loading = false
                    }
                },
            ) { Text(if (loading) "Signing in..." else "Sign in", color = Color.White, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text("Cancel", color = TuneColors.TextSecondary) } },
    )
}
