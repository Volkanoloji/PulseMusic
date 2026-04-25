package code.name.monkey.pulsemusic.model.smartplaylist

import code.name.monkey.pulsemusic.App
import code.name.monkey.pulsemusic.R
import code.name.monkey.pulsemusic.model.Song
import kotlinx.parcelize.Parcelize

@Parcelize
class ShuffleAllPlaylist : AbsSmartPlaylist(
    name = App.getContext().getString(R.string.action_shuffle_all),
    iconRes = R.drawable.ic_shuffle
) {
    override fun songs(): List<Song> {
        return songRepository.songs()
    }
}