
package code.name.monkey.pulsemusic.glide

import android.graphics.drawable.Drawable
import android.widget.ImageView
import code.name.monkey.pulsemusic.App
import code.name.monkey.pulsemusic.extensions.colorControlNormal
import code.name.monkey.pulsemusic.glide.palette.BitmapPaletteTarget
import code.name.monkey.pulsemusic.glide.palette.BitmapPaletteWrapper
import code.name.monkey.pulsemusic.util.color.MediaNotificationProcessor
import com.bumptech.glide.request.transition.Transition

abstract class PulseMusicColoredTarget(view: ImageView) : BitmapPaletteTarget(view) {

    protected val defaultFooterColor: Int
        get() = getView().context.colorControlNormal()

    abstract fun onColorReady(colors: MediaNotificationProcessor)

    override fun onLoadFailed(errorDrawable: Drawable?) {
        super.onLoadFailed(errorDrawable)
        onColorReady(MediaNotificationProcessor.errorColor(App.getContext()))
    }

    override fun onResourceReady(
        resource: BitmapPaletteWrapper,
        transition: Transition<in BitmapPaletteWrapper>?
    ) {
        super.onResourceReady(resource, transition)
        MediaNotificationProcessor(App.getContext()).getPaletteAsync({
            onColorReady(it)
        }, resource.bitmap)
    }
}
