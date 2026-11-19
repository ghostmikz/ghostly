# Tune

A simple, offline music player for Android. It plays the audio files on your phone.
The only online feature is optional Last.fm scrobbling.

- Songs, albums, artists, folders, search
- Background playback with notification and lock-screen controls
- Now-playing screen colored from the album art
- Shuffle, repeat, queue, "play next"
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

GPLv3. The Last.fm client and scrobble logic are adapted from
[BitChord](https://github.com/kushagrasinghx/BitChord) (GPLv3).
