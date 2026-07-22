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
        const val EXTRA_TASK_ID = "task_id"
        private const val NOTIFICATION_ID = 0x7100_0003
        private const val CHANNEL_ID = "ble_notify_service"
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID, "BLE 推送服务", android.app.NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        // 同步调用 startForeground，声明 CONNECTED_DEVICE + LOCATION 类型
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0

        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE 通知同步")
            .setContentText("正在扫描设备…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build(),
            serviceType)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: ""

        scanAndSend(title, body, taskId)
        return START_STICKY
    }

    private fun scanAndSend(title: String, body: String, taskId: String) {
        BleClient.connectWithScan(applicationContext, object : ConnectionCallback {
            override fun onReady(gatt: android.bluetooth.BluetoothGatt) {
                val sdk = try { BleNotificationSDK.init(applicationContext) } catch (_: Exception) { stopSelf(); return }
                val pm = PairingManager(applicationContext)
                val devices = pm.getPairedDevices()
                val baseKey = devices.firstOrNull()?.let { pm.getBaseKey(it.uuid) } ?: run {
                    android.util.Log.e("BleClient", "BleForegroundService: 未找到配对设备的密钥")
                    stopSelf()
                    return
                }

                val frame = FrameEncoder.encodeNotify(baseKey, applicationContext.packageName, title, body, System.currentTimeMillis())
                val service = gatt.getService(BleClient.SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BleClient.WRITE_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    android.util.Log.e("BleClient", "BleForegroundService: 特征值未找到")
                    gatt.close()
                    stopSelf()
                    return
                }
                characteristic.value = frame
                val sent = gatt.writeCharacteristic(characteristic)
                android.util.Log.d("BleClient", "BleForegroundService: writeCharacteristic sent=$sent")
                sdk.notifySynced(taskId, true)
                Handler(Looper.getMainLooper()).postDelayed({ gatt.close(); stopSelf() }, 3000)

            }

            override fun onError(error: SdkError) {
                try { BleNotificationSDK.init(applicationContext).notifySynced(taskId, false) } catch (_: Exception) {}
                stopSelf()
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
