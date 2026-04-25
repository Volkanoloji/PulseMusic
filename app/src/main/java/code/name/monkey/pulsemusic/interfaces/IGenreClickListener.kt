package code.name.monkey.pulsemusic.interfaces

import android.view.View
import code.name.monkey.pulsemusic.model.Genre

interface IGenreClickListener {
    fun onClickGenre(genre: Genre, view: View)
}