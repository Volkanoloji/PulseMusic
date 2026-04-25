package code.name.monkey.pulsemusic.fragments.settings

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.preference.Preference
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.pulsemusic.BLUETOOTH_PLAYBACK
import code.name.monkey.pulsemusic.EQUALIZER
import code.name.monkey.pulsemusic.R
import code.name.monkey.pulsemusic.activities.base.AbsBaseActivity.Companion.BLUETOOTH_PERMISSION_REQUEST


class AudioSettings : AbsSettingsFragment() {
    override fun invalidateSettings() {
        val eqPreference: Preference? = findPreference(EQUALIZER)

        // 🚀 MODİFİYE: Kill-Switch İptal Edildi! Tuş her zaman aktif.
        eqPreference?.isEnabled = true
        eqPreference?.summary = "Pulse Ekolayzır Paneli" // VIP Açıklama

        eqPreference?.setOnPreferenceClickListener {
            // 🚀 KESİN ÇÖZÜM: replace yerine add diyoruz, üstüne yapıştırıyoruz.
            requireActivity().supportFragmentManager.beginTransaction()
                .add(android.R.id.content, EqualizerFragment())
                .commit() // 🚀 addToBackStack'i de sildik, manuel sökeceğiz!
            true
        }

        val bluetoothPreference: Preference? = findPreference(BLUETOOTH_PLAYBACK)
        if (VersionUtils.hasS()) {
            bluetoothPreference?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    if (ActivityCompat.checkSelfPermission(
                            requireContext(),
                            BLUETOOTH_CONNECT
                        ) != PERMISSION_GRANTED
                    ) {
                        ActivityCompat.requestPermissions(
                            requireActivity(), arrayOf(
                                BLUETOOTH_CONNECT
                            ), BLUETOOTH_PERMISSION_REQUEST
                        )
                    }
                }
                return@setOnPreferenceChangeListener true
            }
        }
    }



    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_audio)
    }
}