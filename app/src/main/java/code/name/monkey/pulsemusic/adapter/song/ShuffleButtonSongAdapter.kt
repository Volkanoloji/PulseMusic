
package code.name.monkey.pulsemusic.adapter.song

import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import code.name.monkey.pulsemusic.R
import code.name.monkey.pulsemusic.extensions.accentColor
import code.name.monkey.pulsemusic.extensions.accentOutlineColor
import code.name.monkey.pulsemusic.helper.MusicPlayerRemote
import code.name.monkey.pulsemusic.model.Song
import code.name.monkey.pulsemusic.util.PreferenceUtil
import code.name.monkey.pulsemusic.util.PulseUtil
import com.google.android.material.button.MaterialButton

class ShuffleButtonSongAdapter(
    activity: FragmentActivity,
    dataSet: MutableList<Song>,
    itemLayoutRes: Int
) : AbsOffsetSongAdapter(activity, dataSet, itemLayoutRes) {


    override fun createViewHolder(view: View): SongAdapter.ViewHolder {
        return ViewHolder(view)
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) OFFSET_ITEM else SONG
    }

    override fun onBindViewHolder(holder: SongAdapter.ViewHolder, position: Int) {
        if (holder.itemViewType == OFFSET_ITEM) {
            val viewHolder = holder as ViewHolder
            viewHolder.playAction?.let {
                it.setOnClickListener {
                    MusicPlayerRemote.openQueue(dataSet, 0, true)
                }
                it.accentOutlineColor()
            }
            viewHolder.shuffleAction?.let {
                it.setOnClickListener {
                    MusicPlayerRemote.openAndShuffleQueue(dataSet, true)
                }
                it.accentColor()
            }
        } else {
            super.onBindViewHolder(holder, position - 1)
            val landscape = PulseUtil.isLandscape
            if ((PreferenceUtil.songGridSize > 2 && !landscape) || (PreferenceUtil.songGridSizeLand > 5 && landscape)) {
                holder.menu?.isVisible = false
            }
        }
    }

    inner class ViewHolder(itemView: View) : AbsOffsetSongAdapter.ViewHolder(itemView) {
        val playAction: MaterialButton? = itemView.findViewById(R.id.playAction)
        val shuffleAction: MaterialButton? = itemView.findViewById(R.id.shuffleAction)

        override fun onClick(v: View?) {
            if (itemViewType == OFFSET_ITEM) {
                MusicPlayerRemote.openAndShuffleQueue(dataSet, true)
                return
            }
            super.onClick(v)
        }
    }

}
