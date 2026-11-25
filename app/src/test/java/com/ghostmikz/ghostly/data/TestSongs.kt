package com.ghostmikz.ghostly.data

internal fun song(
    id: Long,
    title: String,
    artist: String = "Tron",
    seconds: Long = 200,
    album: String = "Album",
) = Song(
    id = id, title = title, artist = artist, album = album, albumId = 1, durationMs = seconds * 1000,
    track = 0, year = 0, dateAdded = 0, folder = "Music",
)
