package com.ble.notification.sdk

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.ble.notification.qr.QrResult
import com.ble.notification.qr.QrScannerFragment
import com.ble.notification.ui.DeviceManagerActivity
import com.ble.notification.ui.DeviceManagerFragment

/**
 * SDK 导航辅助类：统一管理 Fragment 路由与 Activity 跳转。
 */
internal class Navigator(private val context: Context) {

    /**
     * 弹出 QR 扫描 Fragment 并回调扫描结果。
     */
    fun showQrScanner(activity: FragmentActivity, onResult: (QrResult?) -> Unit) {
        val fragment = QrScannerFragment.newInstance { qrResult ->
            onResult(qrResult)
        }

        activity.supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .commit()
    }

    /**
     * 获取设备管理 Fragment 实例。
     */
    fun getDeviceManagerFragment(): Fragment = DeviceManagerFragment()

    /**
     * 打开设备管理 Activity。
     */
    fun openDeviceManager() {
        val intent = Intent(context, DeviceManagerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
