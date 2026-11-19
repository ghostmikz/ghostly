package com.ghostmikz.tune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ghostmikz.tune.BuildConfig
import com.ghostmikz.tune.data.Prefs
import com.ghostmikz.tune.lastfm.LastFM
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(songCount: Int, onRescan: () -> Unit, contentPadding: PaddingValues, onBack: () -> Unit) {
    val user by Prefs.lastfmUser.collectAsState()
    val scrobble by Prefs.scrobble.collectAsState()
    val nowPlaying by Prefs.nowPlaying.collectAsState()
    var showLogin by remember { mutableStateOf(false) }

    DetailScaffold("Settings", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = contentPadding.calculateBottomPadding())) {
            SectionTitle("Last.fm")
            when {
                !Prefs.lastfmAvailable -> ListItem(
                    leadingContent = { Icon(Icons.Rounded.Info, null) },
                    headlineContent = { Text("Not set up in this build") },
                    supportingContent = { Text("Add LASTFM_API_KEY and LASTFM_SECRET to local.properties and rebuild.") },
                )
                user.isBlank() -> ListItem(
                    leadingContent = { Icon(Icons.Rounded.Link, null) },
                    headlineContent = { Text("Link Last.fm account") },
                    supportingContent = { Text("Scrobble what you listen to") },
                    trailingContent = { Button(onClick = { showLogin = true }) { Text("Sign in") } },
                )
                else -> {
                    ListItem(
                        leadingContent = { Icon(Icons.Rounded.AccountCircle, null) },
                        headlineContent = { Text(user) },
                        supportingContent = { Text("Linked") },
                        trailingContent = { OutlinedButton(onClick = Prefs::unlinkLastfm) { Text("Unlink") } },
                    )
                    ListItem(
                        headlineContent = { Text("Scrobble") },
                        supportingContent = { Text("Send songs to your Last.fm profile after half (or 4 min) is played") },
                        trailingContent = { Switch(scrobble, Prefs::setScrobble) },
                    )
                    ListItem(
                        headlineContent = { Text("Show \"now playing\"") },
                        supportingContent = { Text("Show the current song on your profile") },
                        trailingContent = { Switch(nowPlaying, Prefs::setNowPlaying, enabled = scrobble) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Library")
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Refresh, null) },
                headlineContent = { Text("Rescan music") },
                supportingContent = { Text(countText(songCount, "song") + " found") },
                trailingContent = { TextButton(onClick = onRescan) { Text("Rescan") } },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("About")
            ListItem(
                headlineContent = { Text("Tune ${BuildConfig.VERSION_NAME}") },
                supportingContent = { Text("Local music player. GPLv3. Last.fm code adapted from BitChord by kushagrasinghx.") },
            )
        }
    }

    if (showLogin) LastfmLoginDialog(onDismiss = { showLogin = false })
}

@Composable
private fun LastfmLoginDialog(onDismiss: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("Sign in to Last.fm") },
        text = {
            Column {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    password, { password = it }, label = { Text("Password") }, singleLine = true,
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
                Text("Your password is only sent to Last.fm once to get a session; it is not saved.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            .onSuccess {
                                Prefs.linkLastfm(it.session.name, it.session.key)
                                onDismiss()
                            }
                            .onFailure { error = it.message ?: "Sign in failed" }
                        loading = false
                    }
                },
            ) { Text(if (loading) "Signing in..." else "Sign in") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text("Cancel") } },
    )
}
