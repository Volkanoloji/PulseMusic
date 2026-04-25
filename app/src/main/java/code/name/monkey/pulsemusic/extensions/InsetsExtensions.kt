package code.name.monkey.pulsemusic.extensions

import androidx.core.view.WindowInsetsCompat
import code.name.monkey.pulsemusic.util.PreferenceUtil
import code.name.monkey.pulsemusic.util.PulseUtil

fun WindowInsetsCompat?.getBottomInsets(): Int {
    return if (PreferenceUtil.isFullScreenMode) {
        return 0
    } else {
        this?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: PulseUtil.navigationBarHeight
    }
}
