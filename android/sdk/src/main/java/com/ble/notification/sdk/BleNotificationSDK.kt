package com.ble.notification.sdk

import android.bluetooth.BluetoothGatt
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.ble.notification.ble.BleClient
import com.ble.notification.ble.ConnectionCallback
import com.ble.notification.pairing.PairedDevice
import com.ble.notification.pairing.PairingCallback
import com.ble.notification.pairing.PairingManager
import com.ble.notification.protocol.FrameEncoder
import com.ble.notification.qr.QrScannerFragment
import com.ble.notification.ui.DeviceManagerActivity
import com.ble.notification.ui.DeviceManagerFragment
import kotlinx.coroutines.flow.StateFlow

interface SendCallback {
    fun onSuccess()
    fun onError(error: SdkError)
}

interface ReminderCallback {
    fun onScheduled(taskId: String)
    fun onTriggered(taskId: String)
    fun onSynced(taskId: String, success: Boolean)
}

class BleNotificationSDK private constructor(private val context: Context) {

    private val pairingManager = PairingManager(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reminderCallbacks = mutableMapOf<String, ReminderCallback>()
    @Volatile private var closed = false

    // 两段式位置权限 Launcher
    private var foregroundLocationLauncher: ActivityResultLauncher<String>? = null
    private var backgroundLocationLauncher: ActivityResultLauncher<String>? = null

    val pairedDevicesState: StateFlow<List<PairedDevice>> = pairingManager.pairedDevicesFlow

    companion object {
        @Volatile
        private var instance: BleNotificationSDK? = null

        fun init(context: Context): BleNotificationSDK {
            return instance ?: synchronized(this) {
                instance ?: BleNotificationSDK(context.applicationContext).also { instance = it }
                    .also { AlarmReceiver.createNotificationChannel(context.applicationContext) }
            }
        }

        fun getInstance(): BleNotificationSDK {
            return instance ?: throw IllegalStateException(
                "BleNotificationSDK not initialized. Call init(context) first."
            )
        }
    }

    /**
     * 注册位置权限 Launcher。**必须在 Activity.onCreate() 中调用。**
     */
    fun registerPermissionLaunchers(activity: FragmentActivity) {
        foregroundLocationLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) requestBackgroundLocation()
        }

        backgroundLocationLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            android.util.Log.d("BleSDK", "后台位置权限回调: granted=$granted")
        }
    }

    /**
     * 统一检查 SDK 所需的所有权限。**在 Activity.onResume() 中调用。**
     */
    fun ensurePermissions(activity: FragmentActivity) {
        // 1. BLE 权限
        val missingBle = BleClient.getMissingPermissions(context)
        if (missingBle.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missingBle, 0x7100_0001)
            return
        }

        // 2. 位置权限（Android 10+）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (foregroundLocationLauncher == null) return

        val bgGranted = ActivityCompat.checkSelfPermission(
            activity, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (bgGranted) return

        val fineGranted = ActivityCompat.checkSelfPermission(
            activity, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            foregroundLocationLauncher?.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        requestBackgroundLocation()
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (backgroundLocationLauncher == null) return

        val bgGranted = ActivityCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (bgGranted) return

        backgroundLocationLauncher?.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    // ── 配对流程 ──

    fun startPairingDirectly(activity: FragmentActivity, qrResult: com.ble.notification.qr.QrResult, callback: PairingCallback) {
        if (checkClosed(callback)) return
        val appName = "DeviceManager"
        val packageName = context.packageName

        if (isPaired(qrResult.uuid)) {
            callback.onError(SdkError.AlreadyPaired())
            return
        }

        callback.onQrResult(qrResult.mac ?: "", qrResult.uuid)

        pairingManager.startPairing(qrResult, appName, packageName, object : PairingCallback {
            override fun onScanSuccess() = callback.onScanSuccess()
            override fun onConnecting() = callback.onConnecting()
            override fun onRegistering() = callback.onRegistering()
            override fun onPaired() {
                callback.onPaired()
            }
            override fun onError(error: SdkError) = callback.onError(error)
        })
    }

    fun startPairing(activity: FragmentActivity, appName: String, callback: PairingCallback) {
        if (checkClosed(callback)) return

        val fragment = QrScannerFragment.newInstance { qrResult ->
            if (qrResult == null) {
                callback.onError(SdkError.Unknown("QR scan cancelled or failed"))
                return@newInstance
            }

            startPairingDirectly(activity, qrResult, callback)
        }

        activity.supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .commit()
    }



    // ── 设备管理 ──

    fun isPaired(uuid: String? = null): Boolean = pairingManager.isPaired(uuid)

    fun getPairedDevices(): List<PairedDevice> = pairingManager.getPairedDevices()

    fun unpair(uuid: String) = pairingManager.unpair(uuid)

    fun unpairAll() = pairingManager.unpairAll()

    // ── UI 入口 ──

    fun getDeviceManagerFragment(): Fragment = DeviceManagerFragment()

    fun openDeviceManager(context: Context) {
        val intent = Intent(context, DeviceManagerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ── 通知发送 ──

    fun sendNotification(title: String, body: String, callback: SendCallback? = null) {
        if (checkClosed(callback)) return
        val devices = pairingManager.getPairedDevices()
        if (devices.isEmpty()) {
            callback?.onError(SdkError.NotPaired())
            return
        }

        val targetDevice = devices.first()
        val baseKey = pairingManager.getBaseKey(targetDevice.uuid)
            ?: run {
                callback?.onError(SdkError.NotPaired())
                return
            }

        BleClient.connectWithScan(context, object : ConnectionCallback {
            override fun onReady(gatt: BluetoothGatt) {
                val frame = FrameEncoder.encodeNotify(
                    key = baseKey,
                    packageName = context.packageName,
                    title = title,
                    body = body,
                    timestamp = System.currentTimeMillis()
                )

                val service = gatt.getService(BleClient.SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BleClient.WRITE_CHARACTERISTIC_UUID)

                if (characteristic == null) {
                    callback?.onError(SdkError.ServiceNotFound())
                    gatt.disconnect()
                    gatt.close()
                    return
                }

                val writeOk = com.ble.notification.ble.BleCompat.writeCharacteristic(gatt, characteristic, frame)
                if (writeOk) {
                    callback?.onSuccess()
                } else {
                    callback?.onError(SdkError.Unknown("Gatt write failed"))
                }

                mainHandler.postDelayed({

                    gatt.disconnect()
                    gatt.close()
                }, 3000)
            }

            override fun onError(error: SdkError) {
                callback?.onError(error)
            }
        })
    }

    // ── 闹钟 ──

    fun setReminder(
        taskId: String,
        title: String,
        body: String,
        triggerAt: Long,
        callback: ReminderCallback? = null
    ) {
        if (closed) return
        if (callback != null) {
            reminderCallbacks[taskId] = callback
        }
        ReminderScheduler.schedule(context, taskId, title, body, triggerAt)
        callback?.onScheduled(taskId)
    }

    fun cancelReminder(taskId: String) {
        ReminderScheduler.cancel(context, taskId)
        reminderCallbacks.remove(taskId)
    }

    internal fun notifySynced(taskId: String, success: Boolean) {
        val callback = reminderCallbacks.remove(taskId)
        callback?.onTriggered(taskId)
        callback?.onSynced(taskId, success)
    }

    // ── 生命周期 ──

    fun close() {
        closed = true
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun checkClosed(callback: PairingCallback?): Boolean {
        if (closed) {
            callback?.onError(SdkError.Closed())
            return true
        }
        return false
    }

    private fun checkClosed(callback: SendCallback?): Boolean {
        if (closed) {
            callback?.onError(SdkError.Closed())
            return true
        }
        return false
    }
}

