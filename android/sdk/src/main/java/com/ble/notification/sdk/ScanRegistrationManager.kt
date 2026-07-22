package com.ble.notification.sdk

import android.app.PendingIntent
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelUuid
import com.ble.notification.ble.BleClient

/**
 * PendingIntent 后台 BLE 扫描：注册后系统自动扫 UUID，匹配到触发广播。
 * Android 设备重启通过 BOOT_COMPLETED 自动重注册。
 */
object ScanRegistrationManager {

    private const val REQUEST_CODE = 0x7300_0001
    const val ACTION_SCAN_RESULT = "com.ble.notification.sdk.ACTION_SCAN_RESULT"

    fun register(context: Context) {
        val scanner = android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner ?: return

        // 从已配对设备读取设备名，用作 PendingIntent 扫描过滤
        val pairingManager = com.ble.notification.pairing.PairingManager(context.applicationContext)
        val deviceName = pairingManager.getPairedDevices()
            .map { it.name }
            .firstOrNull { it.isNotEmpty() }

        if (deviceName == null) {
            android.util.Log.w("BleClient", "No device name saved, skip background scan registration")
            return
        }

        android.util.Log.d("BleClient", "Registering PendingIntent scan for device: $deviceName")
        val filter = ScanFilter.Builder()
            .setDeviceName(deviceName)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0)
            .build()

        val intent = Intent(context, ScanResultReceiver::class.java).apply { action = ACTION_SCAN_RESULT }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)

        try {
            scanner.startScan(listOf(filter), settings, pendingIntent)
            android.util.Log.d("BleClient", "Registered PendingIntent background scan for UUID: ${BleClient.SERVICE_UUID}")
        } catch (e: Exception) {
            android.util.Log.e("BleClient", "Failed to register background scan: ${e.message}")
        }
    }

    fun unregister(context: Context) {
        val scanner = android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner ?: return
        val intent = Intent(context, ScanResultReceiver::class.java).apply { action = ACTION_SCAN_RESULT }
        val flags = PendingIntent.FLAG_NO_CREATE or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
        pi?.let { scanner.stopScan(it); it.cancel() }
    }

    /** 设备重启后重注册 */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                register(context.applicationContext)
            }
        }
    }
}
