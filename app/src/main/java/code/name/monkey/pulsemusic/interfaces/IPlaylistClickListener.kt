package code.name.monkey.pulsemusic.interfaces

import android.view.View
import code.name.monkey.pulsemusic.db.PlaylistWithSongs

interface IPlaylistClickListener {
    fun onPlaylistClick(playlistWithSongs: PlaylistWithSongs, view: View)
}