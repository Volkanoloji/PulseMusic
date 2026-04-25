package code.name.monkey.pulsemusic.activities

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import code.name.monkey.appthemehelper.util.MaterialUtil
import code.name.monkey.pulsemusic.App
import code.name.monkey.pulsemusic.R
import code.name.monkey.pulsemusic.activities.base.AbsThemeActivity
import code.name.monkey.pulsemusic.billing.BillingManager
import code.name.monkey.pulsemusic.databinding.ActivityProVersionBinding
import code.name.monkey.pulsemusic.extensions.accentColor
import code.name.monkey.pulsemusic.extensions.setLightStatusBar
import code.name.monkey.pulsemusic.extensions.setStatusBarColor
import code.name.monkey.pulsemusic.extensions.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PurchaseActivity : AbsThemeActivity() {

    private lateinit var binding: ActivityProVersionBinding
    private lateinit var billingManager: BillingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProVersionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setStatusBarColor(Color.TRANSPARENT)
        setLightStatusBar(false)
        binding.toolbar.navigationIcon?.setTint(Color.WHITE)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        billingManager = App.getContext().billingManager

        MaterialUtil.setTint(binding.purchaseButton, true)

        binding.restoreButton.setOnClickListener {
            restorePurchase()
        }
        binding.purchaseButton.setOnClickListener {
            billingManager.launchBillingFlow(this@PurchaseActivity)
        }
        binding.bannerContainer.backgroundTintList =
            ColorStateList.valueOf(accentColor())
    }

    private fun restorePurchase() {
        billingManager.restorePurchases {
            if (App.isProVersion()) {
                showToast(R.string.restored_previous_purchase_please_restart)
                setResult(RESULT_OK)
            } else {
                showToast(R.string.no_purchase_found)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        billingManager.release()
        super.onDestroy()
    }
}