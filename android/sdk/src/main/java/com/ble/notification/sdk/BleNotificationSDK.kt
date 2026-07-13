package com.ble.notification.sdk

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import com.ble.notification.ble.BleClient
import com.ble.notification.ble.ConnectionCallback
import com.ble.notification.pairing.PairingCallback
import com.ble.notification.pairing.PairingManager
import com.ble.notification.protocol.FrameEncoder
import com.ble.notification.qr.QrScannerFragment

interface SendCallback {
    fun onSuccess()
    fun onError(error: String)
}

class BleNotificationSDK private constructor(private val context: Context) {

    private val pairingManager = PairingManager()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        @Volatile
        private var instance: BleNotificationSDK? = null

        fun init(context: Context): BleNotificationSDK {
            return instance ?: synchronized(this) {
                instance ?: BleNotificationSDK(context.applicationContext).also { instance = it }
            }
        }

        fun getInstance(): BleNotificationSDK {
            return instance ?: throw IllegalStateException(
                "BleNotificationSDK not initialized. Call init(context) first."
            )
        }
    }

    fun startPairing(activity: FragmentActivity, callback: PairingCallback) {
        val fragment = QrScannerFragment.newInstance { qrResult ->
            if (qrResult == null) {
                callback.onError("QR scan cancelled or failed")
                return@newInstance
            }
            pairingManager.startPairing(context, qrResult, object : PairingCallback {
                override fun onScanSuccess() = callback.onScanSuccess()
                override fun onConnecting() = callback.onConnecting()
                override fun onRegistering() = callback.onRegistering()
                override fun onPaired() {
                    pairingManager.savePairing(
                        packageName = context.packageName,
                        mac = qrResult.mac,
                        appName = qrResult.uuid
                    )
                    callback.onPaired()
                }
                override fun onError(error: String) = callback.onError(error)
            })
        }

        activity.supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .addToBackStack("ble_pairing")
            .commit()
    }

    fun isPaired(packageName: String): Boolean {
        return pairingManager.isPaired(packageName)
    }

    fun sendNotification(title: String, body: String, callback: SendCallback? = null) {
        val packageName = context.packageName
        val mac = pairingManager.getPairedMac(packageName)
            ?: run {
                callback?.onError("No paired device found for package: $packageName")
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
                    callback?.onError("Required BLE service/characteristic not found")
                    gatt.disconnect()
                    gatt.close()
                    return
                }

                characteristic.value = frame
                gatt.writeCharacteristic(characteristic)

                // BLE write is async; use a timeout to ensure cleanup
                mainHandler.postDelayed({
                    gatt.disconnect()
                    gatt.close()
                }, 3000)
            }

            override fun onError(error: String) {
                callback?.onError(error)
            }
        })
    }
}
