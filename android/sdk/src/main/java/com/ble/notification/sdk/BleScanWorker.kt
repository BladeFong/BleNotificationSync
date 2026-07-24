package com.ble.notification.sdk

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ble.notification.ble.BleClient
import com.ble.notification.pairing.PairingManager
import com.ble.notification.protocol.FrameEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * WorkManager Worker：后台 BLE 扫描 → 连接 → 发送通知。
 * WorkManager 上下文可能不受 MIUI 对 Service 的限制。
 */
class BleScanWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_BODY = "body"
        const val KEY_SEND_ID = "send_id"
        const val WORK_NAME = "ble_scan_send"
    }

    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE) ?: return Result.failure()
        val body = inputData.getString(KEY_BODY) ?: return Result.failure()
        val sendId = inputData.getString(KEY_SEND_ID) ?: ""

        android.util.Log.d("BleClient", "WorkManager: doWork started")

        return try {
            val scanResult = scanForDevice()
            if (scanResult == null) {
                android.util.Log.e("BleClient", "WorkManager: scan timeout, device not found")
                try {
                    BleNotificationSDK.init(applicationContext).notifySendResult(sendId, false, SdkError.Unknown("Device scan timeout"))
                } catch (_: Exception) {}
                return Result.failure()
            }

            android.util.Log.d("BleClient", "WorkManager: device found ${scanResult.device.name ?: "PC"}, connecting")
            val connected = connectAndSend(scanResult.device.address, title, body)
            val sdk = BleNotificationSDK.init(applicationContext)
            if (connected) {
                android.util.Log.d("BleClient", "WorkManager: send succeeded")
                sdk.notifySendResult(sendId, true)
                Result.success()
            } else {
                android.util.Log.e("BleClient", "WorkManager: connect/send failed")
                sdk.notifySendResult(sendId, false, SdkError.Unknown("WorkManager connect/send failed"))
                Result.failure()
            }
        } catch (e: Exception) {
            android.util.Log.e("BleClient", "WorkManager: exception ${e.message}", e)
            try {
                BleNotificationSDK.init(applicationContext).notifySendResult(sendId, false, SdkError.Unknown(e.message ?: "Exception"))
            } catch (_: Exception) {}
            Result.failure()
        }
    }

    private suspend fun scanForDevice(): ScanResult? {
        val manager = applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val adapter = manager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter() ?: return null
        val scanner = adapter.bluetoothLeScanner ?: return null

        android.util.Log.d("BleClient", "WorkManager: scanner=$scanner, state=${adapter.state}")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        return suspendCancellableCoroutine { cont ->
            var found = false
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (found) return
                    android.util.Log.d("BleClient", "WorkManager Scan hit: pc_name=${result.device.name}")

                    val uuids = result.scanRecord?.serviceUuids
                    if (uuids != null && uuids.contains(android.os.ParcelUuid(BleClient.SERVICE_UUID))) {
                        found = true
                        scanner.stopScan(this)
                        if (cont.isActive) cont.resume(result)
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    android.util.Log.e("BleClient", "WorkManager Scan failed: $errorCode")
                    if (cont.isActive) cont.resume(null)
                }
            }

            scanner.startScan(null, settings, callback)

            // 10 秒超时
            Handler(Looper.getMainLooper()).postDelayed({
                if (!found) {
                    found = true
                    scanner.stopScan(callback)
                    if (cont.isActive) cont.resume(null)
                }
            }, 10_000)
        }
    }

    private suspend fun connectAndSend(mac: String, title: String, body: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            val client = BleClient(applicationContext)
            client.connect(mac, object : com.ble.notification.ble.ConnectionCallback {
                override fun onReady(gatt: android.bluetooth.BluetoothGatt) {
                    val pm = PairingManager(applicationContext)
                    val devices = pm.getPairedDevices()
                    val baseKey = devices.firstOrNull()?.let { pm.getBaseKey(it.uuid) }
                    if (baseKey == null) {
                        try { gatt.close() } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(false)
                        return
                    }


                    val frame = FrameEncoder.encodeNotify(
                        baseKey, applicationContext.packageName, title, body, System.currentTimeMillis()
                    )
                    val service = gatt.getService(BleClient.SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(BleClient.WRITE_CHARACTERISTIC_UUID)
                    if (characteristic == null) {
                        try { gatt.close() } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(false)
                        return
                    }
                    val sent = com.ble.notification.ble.BleCompat.writeCharacteristic(gatt, characteristic, frame)
                    if (cont.isActive) cont.resume(sent)
                }

                override fun onError(error: com.ble.notification.sdk.SdkError) {
                    android.util.Log.e("BleClient", "WorkManager connection failed: ${error.message}")
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }
}
