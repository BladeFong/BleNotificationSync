package com.ble.notification.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ble.notification.sdk.BleNotificationSDK
import com.ble.notification.sdk.SendCallback
import com.ble.notification.sdk.SdkError

class DemoAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM_TRIGGER = "com.ble.notification.demo.ACTION_ALARM_TRIGGER"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM_TRIGGER) return

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Demo Reminder"
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""

        Log.d("DemoAlarmReceiver", "闹钟触发，准备通过 SDK 发送通知: title=$title, body=$body")

        try {
            val sdk = BleNotificationSDK.init(context.applicationContext)
            sdk.sendNotification(title, body, callback = object : SendCallback {
                override fun onSuccess() {
                    Log.d("DemoAlarmReceiver", "同步成功")
                }

                override fun onError(error: SdkError) {
                    Log.e("DemoAlarmReceiver", "同步失败: ${error.message}")
                }
            })
        } catch (e: Exception) {
            Log.e("DemoAlarmReceiver", "SDK 初始化或调用失败", e)
        }
    }
}
