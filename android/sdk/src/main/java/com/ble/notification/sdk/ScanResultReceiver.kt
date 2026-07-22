package com.ble.notification.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收 PendingIntent 后台扫描结果，更新保存的 MAC。
 */
class ScanResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScanRegistrationManager.ACTION_SCAN_RESULT) return

        val device = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(
            android.bluetooth.BluetoothDevice.EXTRA_DEVICE
        ) ?: return

        val newMac = device.address
        val sdk = try { BleNotificationSDK.init(context.applicationContext) }
                  catch (_: Exception) { return }

        // 遍历所有已配对设备，找到已配对但 MAC 变化的，更新
        for (paired in sdk.getPairedDevices()) {
            if (paired.mac != newMac) {
                val oldMac = paired.mac
                sdk.updatePairedMac(paired.packageName, newMac)
                val msg = "MAC 更新: ${paired.packageName} $oldMac → $newMac"
                android.util.Log.d("BleClient", msg)
                LogRepository.append(context.applicationContext, msg)
            }
        }
    }
}
