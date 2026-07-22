package com.ble.notification.sdk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 接收 PendingIntent 后台扫描结果，触发通知和写入日志。
 */
class ScanResultReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "ble_system_scan_channel"
        private const val CHANNEL_NAME = "BLE 系统扫描提醒"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScanRegistrationManager.ACTION_SCAN_RESULT) return

        @Suppress("DEPRECATION")
        val scanResults = intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_OF_SCAN_RESULTS)
        @Suppress("DEPRECATION")
        val singleDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

        val device = scanResults?.firstOrNull()?.device ?: singleDevice
        val deviceName = device?.name ?: scanResults?.firstOrNull()?.scanRecord?.deviceName ?: "PC Device"
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        val msg = "系统 BLE 扫描触发: 匹配设备 [$deviceName] ($timeStr)"
        android.util.Log.d("BleClient", msg)
        LogRepository.append(context.applicationContext, msg)

        // 发送系统通知
        sendNotification(context.applicationContext, deviceName, timeStr)
    }

    private fun sendNotification(context: Context, deviceName: String, timeStr: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "系统 BLE 后台扫描匹配通知"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("系统 BLE 扫描触发")
            .setContentText("匹配到已关联设备: $deviceName")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("【BLE 系统扫描命中】\n设备名称: $deviceName\n触发时间: $timeStr\n状态: 已成功接收系统广播并记录日志")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
    }
}
