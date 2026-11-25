package com.ghostmikz.ghostly.data

internal fun song(
    id: Long,
    title: String,
    artist: String = "Tron",
    seconds: Long = 200,
    album: String = "Album",
    file: String = "",
    albumId: Long = 1,
) = Song(
    id = id, title = title, artist = artist, album = album, albumId = albumId, durationMs = seconds * 1000,
    track = 0, year = 0, dateAdded = 0, folder = "Music", fileName = file,
)
