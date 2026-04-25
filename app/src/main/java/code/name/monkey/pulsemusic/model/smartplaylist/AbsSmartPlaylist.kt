package code.name.monkey.pulsemusic.model.smartplaylist

import androidx.annotation.DrawableRes
import code.name.monkey.pulsemusic.R
import code.name.monkey.pulsemusic.model.AbsCustomPlaylist

abstract class AbsSmartPlaylist(
    name: String,
    @DrawableRes val iconRes: Int = R.drawable.ic_queue_music
) : AbsCustomPlaylist(
    id = PlaylistIdGenerator(name, iconRes),
    name = name
)