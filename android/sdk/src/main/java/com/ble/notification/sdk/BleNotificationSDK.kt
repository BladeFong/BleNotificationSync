package com.ble.notification.sdk

import android.bluetooth.BluetoothGatt
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import com.ble.notification.ble.BleClient
import com.ble.notification.ble.ConnectionCallback
import com.ble.notification.pairing.PairedDevice
import com.ble.notification.pairing.PairingCallback
import com.ble.notification.pairing.PairingManager
import com.ble.notification.protocol.FrameEncoder
import com.ble.notification.qr.QrScannerFragment

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

    companion object {
        @Volatile
        private var instance: BleNotificationSDK? = null

        private const val REQUEST_CODE_BLE_PERMISSIONS = 0x7100_0001

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

    fun startPairing(activity: FragmentActivity, appName: String, callback: PairingCallback) {
        if (checkClosed(callback)) return
        val packageName = context.packageName

        // Check BLE permissions before opening scanner
        val missing = BleClient.getMissingPermissions(context)
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missing, REQUEST_CODE_BLE_PERMISSIONS)
            return
        }

        val fragment = QrScannerFragment.newInstance { qrResult ->
            if (qrResult == null) {
                callback.onError(SdkError.Unknown("QR scan cancelled or failed"))
                return@newInstance
            }

            callback.onQrResult(qrResult.mac, qrResult.uuid)

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

        activity.supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .addToBackStack("ble_pairing")
            .commit()
    }

    fun isPaired(packageName: String): Boolean = pairingManager.isPaired(packageName)

    fun getPairedDevices(): List<PairedDevice> = pairingManager.getPairedDevices()

    fun unpair(packageName: String) = pairingManager.unpair(packageName)

    fun sendNotification(title: String, body: String, callback: SendCallback? = null) {
        if (checkClosed(callback)) return
        val packageName = context.packageName
        val mac = pairingManager.getPairedMac(packageName)
            ?: run {
                callback?.onError(SdkError.NotPaired())
                return
            }

        BleClient.connect(context, mac, object : ConnectionCallback {
            override fun onReady(gatt: BluetoothGatt) {
                val frame = FrameEncoder.encodeNotify(
                    packageName = packageName,
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

                characteristic.value = frame
                gatt.writeCharacteristic(characteristic)

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
