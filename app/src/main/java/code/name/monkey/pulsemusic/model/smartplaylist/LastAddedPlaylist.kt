package code.name.monkey.pulsemusic.model.smartplaylist

import code.name.monkey.pulsemusic.App
import code.name.monkey.pulsemusic.R
import code.name.monkey.pulsemusic.model.Song
import kotlinx.parcelize.Parcelize

@Parcelize
class LastAddedPlaylist : AbsSmartPlaylist(
    name = App.getContext().getString(R.string.last_added),
    iconRes = R.drawable.ic_library_add
) {
    override fun songs(): List<Song> {
        return lastAddedRepository.recentSongs()
    }
}