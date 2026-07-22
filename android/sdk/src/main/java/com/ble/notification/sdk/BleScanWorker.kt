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
        const val KEY_TASK_ID = "task_id"
        const val WORK_NAME = "ble_scan_send"
    }

    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE) ?: return Result.failure()
        val body = inputData.getString(KEY_BODY) ?: return Result.failure()
        val taskId = inputData.getString(KEY_TASK_ID) ?: ""

        android.util.Log.d("BleClient", "WorkManager: doWork 开始")

        return try {
            val scanResult = scanForDevice()
            if (scanResult == null) {
                android.util.Log.e("BleClient", "WorkManager: 扫描超时，未找到设备")
                return Result.failure()
            }

            android.util.Log.d("BleClient", "WorkManager: 扫描到设备 ${scanResult.device.address}，尝试连接")
            val connected = connectAndSend(scanResult.device.address, title, body)
            if (connected) {
                android.util.Log.d("BleClient", "WorkManager: 发送成功")
                val sdk = BleNotificationSDK.init(applicationContext)
                sdk.notifySynced(taskId, true)
                Result.success()
            } else {
                android.util.Log.e("BleClient", "WorkManager: 连接/发送失败")
                Result.failure()
            }
        } catch (e: Exception) {
            android.util.Log.e("BleClient", "WorkManager: 异常 ${e.message}", e)
            Result.failure()
        }
    }

    private suspend fun scanForDevice(): ScanResult? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
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
                    android.util.Log.d("BleClient", "WorkManager Scan hit: ${result.device.address} name=${result.device.name}")
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
            BleClient.connect(applicationContext, mac, object : com.ble.notification.ble.ConnectionCallback {
                override fun onReady(gatt: android.bluetooth.BluetoothGatt) {
                    val pm = PairingManager(applicationContext)
                    val baseKey = pm.getBaseKey(applicationContext.packageName)
                    if (baseKey == null) {
                        gatt.close()
                        if (cont.isActive) cont.resume(false)
                        return
                    }

                    val frame = FrameEncoder.encodeNotify(
                        baseKey, applicationContext.packageName, title, body, System.currentTimeMillis()
                    )
                    val service = gatt.getService(BleClient.SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(BleClient.WRITE_CHARACTERISTIC_UUID)
                    if (characteristic == null) {
                        gatt.close()
                        if (cont.isActive) cont.resume(false)
                        return
                    }
                    characteristic.value = frame
                    gatt.writeCharacteristic(characteristic)
                    Handler(Looper.getMainLooper()).postDelayed({
                        gatt.close()
                        if (cont.isActive) cont.resume(true)
                    }, 2000)
                }

                override fun onError(error: com.ble.notification.sdk.SdkError) {
                    android.util.Log.e("BleClient", "WorkManager 连接失败: ${error.message}")
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }
}
