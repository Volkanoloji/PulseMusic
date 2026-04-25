package code.name.monkey.pulsemusic.model.smartplaylist

import code.name.monkey.pulsemusic.App
import code.name.monkey.pulsemusic.R
import code.name.monkey.pulsemusic.model.Song
import kotlinx.parcelize.Parcelize

@Parcelize
class TopTracksPlaylist : AbsSmartPlaylist(
    name = App.getContext().getString(R.string.my_top_tracks),
    iconRes = R.drawable.ic_trending_up
) {
    override fun songs(): List<Song> {
        return topPlayedRepository.topTracks()
    }
}