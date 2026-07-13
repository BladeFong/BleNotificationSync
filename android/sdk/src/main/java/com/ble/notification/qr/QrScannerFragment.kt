package com.ble.notification.qr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * QR Scanner Fragment - UI layer.
 *
 * Requires CameraX and ML Kit dependencies to be enabled.
 * Currently a placeholder until dependencies are available in offline build.
 */
class QrScannerFragment : Fragment() {

    private var onResult: ((QrResult?) -> Unit)? = null
    private lateinit var hintTextView: TextView

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // TODO: Start camera scanning when CameraX is available
            onResult?.invoke(null)
        } else {
            onResult?.invoke(null)
        }
    }

    companion object {
        fun newInstance(onResult: (QrResult?) -> Unit): QrScannerFragment {
            return QrScannerFragment().apply {
                this.onResult = onResult
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        hintTextView = TextView(requireContext()).apply {
            text = "Scan QR code to pair"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 100
            }
        }
        root.addView(hintTextView)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                // TODO: Start camera scanning when CameraX is available
                onResult?.invoke(null)
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }
}
