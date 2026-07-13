package com.ble.notification.qr

import android.app.Activity
import androidx.lifecycle.LifecycleOwner

/**
 * QR Scanner - Camera layer.
 *
 * Requires CameraX and ML Kit dependencies to be enabled.
 * Currently a placeholder until dependencies are available in offline build.
 */
class QrScanner(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner
) {
    private var onResult: ((QrResult?) -> Unit)? = null
    private var isScanning = false

    fun start(callback: (QrResult?) -> Unit) {
        onResult = callback
        isScanning = true
        // TODO: Implement CameraX + ML Kit barcode scanning when dependencies are available
        // For now, return null to indicate scanning not available
        callback(null)
    }

    fun stop() {
        isScanning = false
    }
}
