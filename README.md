# Ghostly

A simple, offline music player for Android. It plays the audio files on your phone.
The only online feature is optional Last.fm scrobbling.

- BitChord-style design: black theme, floating tab bar and mini player, Apple Music-like player
- Home ("Listen Now") built from your own listening: recently played, most played, recently added
- Library with songs, artists, albums, folders, favorites and search
- Background playback with notification and lock-screen controls
- Shuffle, repeat, queue, "play next", volume bar
- Last.fm: sign in with your account, scrobble + "now playing"

## Build

Needs JDK 17+ and the Android SDK. Put this in `local.properties`:

```
sdk.dir=/path/to/Android/Sdk
LASTFM_API_KEY=...
LASTFM_SECRET=...
```

Get the Last.fm key at https://www.last.fm/api/account/create (leave them empty to build without Last.fm).

```
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

## License

GPLv3. The design, icons and Last.fm client are adapted from
[BitChord](https://github.com/kushagrasinghx/BitChord) (GPLv3).
Font: [Inter](https://rsms.me/inter/) (SIL Open Font License).
