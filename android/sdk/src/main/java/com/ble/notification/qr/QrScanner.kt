package com.ble.notification.qr

import android.app.Activity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class QrScanner(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner
) {
    private var onResult: ((QrResult?) -> Unit)? = null
    private var isScanning = false
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val barcodeScanner = BarcodeScanning.getClient()

    fun start(previewView: PreviewView, callback: (QrResult?) -> Unit) {
        if (isScanning) return
        onResult = callback
        isScanning = true

        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider
            bindCamera(provider, previewView)
        }, ContextCompat.getMainExecutor(activity))
    }

    fun stop() {
        isScanning = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor.shutdownNow()
    }

    private fun bindCamera(provider: ProcessCameraProvider, previewView: PreviewView) {
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    if (!isScanning) { imageProxy.close(); return@setAnalyzer }

                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val inputImage = InputImage.fromMediaImage(
                            mediaImage, imageProxy.imageInfo.rotationDegrees
                        )
                        barcodeScanner.process(inputImage)
                            .addOnSuccessListener { barcodes -> handleBarcodes(barcodes) }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }
            }

        try {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
        } catch (_: Exception) {
            onResult?.invoke(null)
        }
    }

    private fun handleBarcodes(barcodes: List<Barcode>) {
        if (!isScanning) return
        for (barcode in barcodes) {
            if (barcode.valueType == Barcode.TYPE_URL || barcode.valueType == Barcode.TYPE_TEXT) {
                val rawValue = barcode.rawValue ?: continue
                val result = QrDecoder.parseQrCode(rawValue)
                if (result != null) {
                    isScanning = false
                    cameraProvider?.unbindAll()
                    onResult?.invoke(result)
                    return
                }
            }
        }
    }
}
