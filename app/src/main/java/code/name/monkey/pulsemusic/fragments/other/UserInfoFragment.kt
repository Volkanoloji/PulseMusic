
package code.name.monkey.pulsemusic.fragments.other

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import code.name.monkey.pulsemusic.Constants.USER_BANNER
import code.name.monkey.pulsemusic.Constants.USER_PROFILE
import code.name.monkey.pulsemusic.R
import code.name.monkey.pulsemusic.databinding.FragmentUserInfoBinding
import code.name.monkey.pulsemusic.extensions.accentColor
import code.name.monkey.pulsemusic.extensions.applyToolbar
import code.name.monkey.pulsemusic.extensions.showToast
import code.name.monkey.pulsemusic.fragments.LibraryViewModel
import code.name.monkey.pulsemusic.glide.PulseGlideExtension
import code.name.monkey.pulsemusic.glide.PulseGlideExtension.profileBannerOptions
import code.name.monkey.pulsemusic.glide.PulseGlideExtension.userProfileOptions
import code.name.monkey.pulsemusic.util.ImageUtil
import code.name.monkey.pulsemusic.util.PreferenceUtil.userName
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.io.File

class UserInfoFragment : Fragment() {

    private var _binding: FragmentUserInfoBinding? = null
    private val binding get() = _binding!!
    private val libraryViewModel: LibraryViewModel by activityViewModel()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        sharedElementEnterTransition = MaterialContainerTransform().apply {
            drawingViewId = R.id.fragment_container
            duration = 300L
            scrimColor = Color.TRANSPARENT
        }
        _binding = FragmentUserInfoBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyToolbar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.nameContainer.accentColor()
        binding.next.accentColor()
        binding.name.setText(userName)

        binding.userImage.setOnClickListener {
            showUserImageOptions()
        }

        binding.bannerImage.setOnClickListener {
            showBannerImageOptions()
        }

        binding.next.setOnClickListener {
            val nameString = binding.name.text.toString().trim { it <= ' ' }
            if (nameString.isEmpty()) {
                showToast(R.string.error_empty_name)
                return@setOnClickListener
            }
            userName = nameString
            findNavController().navigateUp()
        }

        loadProfile()
        postponeEnterTransition()
        view.doOnPreDraw {
            startPostponedEnterTransition()
        }
        libraryViewModel.getFabMargin().observe(viewLifecycleOwner) {
            binding.next.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = it
            }
        }
    }

    private fun showBannerImageOptions() {
        val list = requireContext().resources.getStringArray(R.array.image_settings_options)
        MaterialAlertDialogBuilder(requireContext()).setTitle("Banner Image")
            .setItems(list) { _, which ->
                when (which) {
                    0 -> selectBannerImage()
                    1 -> {
                        val appDir = requireContext().filesDir
                        val file = File(appDir, USER_BANNER)
                        file.delete()
                        loadProfile()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
            .show()
    }

    private fun showUserImageOptions() {
        val list = requireContext().resources.getStringArray(R.array.image_settings_options)
        MaterialAlertDialogBuilder(requireContext()).setTitle("Profile Image")
            .setItems(list) { _, which ->
                when (which) {
                    0 -> pickNewPhoto()
                    1 -> {
                        val appDir = requireContext().filesDir
                        val file = File(appDir, USER_PROFILE)
                        file.delete()
                        loadProfile()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
            .show()
    }

    private fun loadProfile() {
        binding.bannerImage.let {
            Glide.with(this)
                .load(PulseGlideExtension.getBannerModel())
                .profileBannerOptions(PulseGlideExtension.getBannerModel())
                .into(it)
        }
        Glide.with(this)
            .load(PulseGlideExtension.getUserModel())
            .userProfileOptions(PulseGlideExtension.getUserModel(), requireContext())
            .into(binding.userImage)
    }


    private fun selectBannerImage() {
        pickBannerImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun pickNewPhoto() {
        pickProfileImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private val pickBannerImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) {
                setAndSaveBannerImage(uri)
            } else {
                showToast("No image selected")
            }
        }

    private val pickProfileImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) {
                setAndSaveUserImage(uri)
            } else {
                showToast("No image selected")
            }
        }


    private fun setAndSaveBannerImage(fileUri: Uri) {
        Glide.with(this)
            .asBitmap()
            .load(fileUri)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .listener(object : RequestListener<Bitmap> {
                override fun onResourceReady(
                    resource: Bitmap, // ? Kaldırıldı
                    model: Any, // ? Kaldırıldı
                    target: Target<Bitmap>?,
                    dataSource: DataSource, // ? Kaldırıldı
                    isFirstResource: Boolean,
                ): Boolean {
                    saveImage(resource, USER_BANNER)
                    return false
                }

                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Bitmap>, // ? Kaldırıldı
                    isFirstResource: Boolean,
                ): Boolean {
                    return false
                }
            })
            .into(binding.bannerImage)
    }

    private fun saveImage(bitmap: Bitmap, fileName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val appDir = requireContext().filesDir
            val file = File(appDir, fileName)
            var successful: Boolean
            file.outputStream().buffered().use {
                successful = ImageUtil.resizeBitmap(bitmap, 2048)
                    .compress(Bitmap.CompressFormat.WEBP, 100, it)
            }
            if (successful) {
                withContext(Dispatchers.Main) {
                    showToast(R.string.message_updated)
                }
            }
        }
    }

    private fun setAndSaveUserImage(fileUri: Uri) {
        Glide.with(this)
            .asBitmap()
            .load(fileUri)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .listener(object : RequestListener<Bitmap> {
                override fun onResourceReady(
                    resource: Bitmap, // ? Kaldırıldı
                    model: Any, // ? Kaldırıldı
                    target: Target<Bitmap>?,
                    dataSource: DataSource, // ? Kaldırıldı
                    isFirstResource: Boolean,
                ): Boolean {
                    saveImage(resource, USER_PROFILE)
                    return false
                }

                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Bitmap>, // ? Kaldırıldı
                    isFirstResource: Boolean,
                ): Boolean {
                    return false
                }
            })
            .into(binding.userImage)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}