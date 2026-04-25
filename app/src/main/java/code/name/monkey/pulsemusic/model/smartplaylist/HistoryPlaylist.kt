package code.name.monkey.pulsemusic.model.smartplaylist

import code.name.monkey.pulsemusic.App
import code.name.monkey.pulsemusic.R
import code.name.monkey.pulsemusic.model.Song
import kotlinx.parcelize.Parcelize
import org.koin.core.component.KoinComponent

@Parcelize
class HistoryPlaylist : AbsSmartPlaylist(
    name = App.getContext().getString(R.string.history),
    iconRes = R.drawable.ic_history
), KoinComponent {

    override fun songs(): List<Song> {
        return topPlayedRepository.recentlyPlayedTracks()
    }
}