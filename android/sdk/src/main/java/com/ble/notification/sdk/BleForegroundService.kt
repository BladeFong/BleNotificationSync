package com.ble.notification.sdk

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.ble.notification.ble.BleClient
import com.ble.notification.ble.ConnectionCallback
import com.ble.notification.pairing.PairingManager
import com.ble.notification.protocol.FrameEncoder

class BleForegroundService : Service() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_SEND_ID = "send_id"
        private const val NOTIFICATION_ID = 0x7100_0003
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        val appIcon = applicationInfo.icon.let { if (it != 0) it else android.R.drawable.ic_dialog_info }
        val notification = NotificationCompat.Builder(this, BleNotificationSDK.getDefaultChannelId(this))
            .setContentTitle(getString(R.string.s_foreground_notify_title))
            .setContentText(getString(R.string.s_foreground_scanning))
            .setSmallIcon(appIcon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0

        androidx.core.app.ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, serviceType
        )


        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""
        val sendId = intent.getStringExtra(EXTRA_SEND_ID) ?: ""

        scanAndSend(title, body, sendId)
        return START_STICKY
    }

    private fun scanAndSend(title: String, body: String, sendId: String) {
        val client = BleClient(applicationContext)
        client.connectWithScan(object : ConnectionCallback {
            override fun onReady(gatt: android.bluetooth.BluetoothGatt) {
                val sdk = try { BleNotificationSDK.init(applicationContext) } catch (_: Exception) { stopSelf(); return }
                val pm = PairingManager(applicationContext)
                val devices = pm.getPairedDevices()
                val baseKey = devices.firstOrNull()?.let { pm.getBaseKey(it.uuid) } ?: run {
                    android.util.Log.e("BleClient", "BleForegroundService: no paired device key found")
                    sdk.notifySendResult(sendId, false, SdkError.NotPaired())
                    stopSelf()
                    return
                }

                val frame = FrameEncoder.encodeNotify(baseKey, applicationContext.packageName, title, body, System.currentTimeMillis())
                val service = gatt.getService(BleClient.SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BleClient.WRITE_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    android.util.Log.e("BleClient", "BleForegroundService: characteristic not found")
                    sdk.notifySendResult(sendId, false, SdkError.ServiceNotFound())
                    try { gatt.close() } catch (_: Exception) {}
                    stopSelf()
                    return
                }
                val sent = com.ble.notification.ble.BleCompat.writeCharacteristic(gatt, characteristic, frame)
                android.util.Log.d("BleClient", "BleForegroundService: writeCharacteristic sent=$sent")

                if (sent) {
                    sdk.notifySendResult(sendId, true)
                } else {
                    sdk.notifySendResult(sendId, false, SdkError.Unknown("Gatt write failed"))
                }
                // 这里只负责 stopSelf() 服务自身生命周期结束，Gatt 的 close 由 BleClient 中的回调自动闭环
                Handler(Looper.getMainLooper()).postDelayed({ stopSelf() }, 1500)

            }

            override fun onError(error: SdkError) {
                try { BleNotificationSDK.init(applicationContext).notifySendResult(sendId, false, error) } catch (_: Exception) {}
                stopSelf()
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
