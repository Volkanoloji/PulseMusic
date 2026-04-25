
package code.name.monkey.pulsemusic.activities

import android.os.Bundle
import android.view.MenuItem
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.pulsemusic.activities.base.AbsThemeActivity
import code.name.monkey.pulsemusic.databinding.ActivityDonationBinding
import code.name.monkey.pulsemusic.extensions.openUrl
import code.name.monkey.pulsemusic.extensions.setStatusBarColorAuto
import code.name.monkey.pulsemusic.extensions.setTaskDescriptionColorAuto
import code.name.monkey.pulsemusic.extensions.surfaceColor


class SupportDevelopmentActivity : AbsThemeActivity() {

    lateinit var binding: ActivityDonationBinding
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDonationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setStatusBarColorAuto()
        setTaskDescriptionColorAuto()

        setupToolbar()

        binding.kofi.setOnClickListener {
            openUrl(KOFI_URL)
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setBackgroundColor(surfaceColor())
        ToolbarContentTintHelper.colorBackButton(binding.toolbar)
        setSupportActionBar(binding.toolbar)
    }

    companion object {
        const val KOFI_URL = "https://ko-fi.com/quickersilver"
    }
}
