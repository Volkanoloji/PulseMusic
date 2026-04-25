
package code.name.monkey.pulsemusic.adapter.album

import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import code.name.monkey.pulsemusic.glide.PulseGlideExtension
import code.name.monkey.pulsemusic.glide.PulseGlideExtension.albumCoverOptions
import code.name.monkey.pulsemusic.glide.PulseGlideExtension.asBitmapPalette
import code.name.monkey.pulsemusic.glide.PulseMusicColoredTarget
import code.name.monkey.pulsemusic.helper.HorizontalAdapterHelper
import code.name.monkey.pulsemusic.interfaces.IAlbumClickListener
import code.name.monkey.pulsemusic.model.Album
import code.name.monkey.pulsemusic.util.MusicUtil
import code.name.monkey.pulsemusic.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide

class HorizontalAlbumAdapter(
    activity: FragmentActivity,
    dataSet: List<Album>,
    albumClickListener: IAlbumClickListener
) : AlbumAdapter(
    activity, dataSet, HorizontalAdapterHelper.LAYOUT_RES, albumClickListener
) {

    override fun createViewHolder(view: View, viewType: Int): ViewHolder {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        HorizontalAdapterHelper.applyMarginToLayoutParams(activity, params, viewType)
        return ViewHolder(view)
    }

    override fun setColors(color: MediaNotificationProcessor, holder: ViewHolder) {
        // holder.title?.setTextColor(ATHUtil.resolveColor(activity, android.R.attr.textColorPrimary))
        // holder.text?.setTextColor(ATHUtil.resolveColor(activity, android.R.attr.textColorSecondary))
    }

    override fun loadAlbumCover(album: Album, holder: ViewHolder) {
        if (holder.image == null) return
        Glide.with(activity)
            .asBitmapPalette()
            .albumCoverOptions(album.safeGetFirstSong())
            .load(PulseGlideExtension.getSongModel(album.safeGetFirstSong()))
            .into(object : PulseMusicColoredTarget(holder.image!!) {
                override fun onColorReady(colors: MediaNotificationProcessor) {
                    setColors(colors, holder)
                }
            })
    }

    override fun getAlbumText(album: Album): String {
        return MusicUtil.getYearString(album.year)
    }

    override fun getItemViewType(position: Int): Int {
        return HorizontalAdapterHelper.getItemViewType(position, itemCount)
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    companion object {
        val TAG: String = AlbumAdapter::class.java.simpleName
    }
}
