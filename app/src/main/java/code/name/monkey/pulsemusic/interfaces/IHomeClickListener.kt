package code.name.monkey.pulsemusic.interfaces

import code.name.monkey.pulsemusic.model.Album
import code.name.monkey.pulsemusic.model.Artist
import code.name.monkey.pulsemusic.model.Genre

interface IHomeClickListener {
    fun onAlbumClick(album: Album)

    fun onArtistClick(artist: Artist)

    fun onGenreClick(genre: Genre)
}