package com.ble.notification.sdk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM_TRIGGER = "com.ble.notification.sdk.ACTION_ALARM_TRIGGER"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"

        private const val CHANNEL_ID = "ble_notify_reminders"
        private const val CHANNEL_NAME = "提醒通知"
        private const val NOTIFICATION_ID_BASE = 0x4000_0000

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "提醒通知" }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM_TRIGGER) return

        // goAsync: 延长 BroadcastReceiver 生命周期，防止 BLE 操作超时被杀
        val pending = goAsync()

        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(EXTRA_BODY) ?: return

        createNotificationChannel(context)
        showNotification(context, taskId, title, body)
        trySendBle(context, title, body, taskId)
        pending.finish()
    }

    private fun trySendBle(context: Context, title: String, body: String, taskId: String) {
        // 检查权限状态并输出 log（不请求，仅诊断）
        val missing = com.ble.notification.ble.BleClient.getMissingPermissions(context)
        if (missing.isNotEmpty()) {
            android.util.Log.w("BleClient", "AlarmReceiver: 缺少权限 $missing，扫描可能受限")
        }
        val bgLocation = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        android.util.Log.d("BleClient", "AlarmReceiver: ACCESS_BACKGROUND_LOCATION=$bgLocation")

        // 跳过直连 MAC，直接起前台 Service 扫描
        android.util.Log.d("BleClient", "AlarmReceiver: 启动前台服务扫描")
        startForegroundService(context, title, body, taskId)
    }

    private fun startForegroundService(context: Context, title: String, body: String, taskId: String) {
        val intent = Intent(context, BleForegroundService::class.java).apply {
            putExtra(BleForegroundService.EXTRA_TITLE, title)
            putExtra(BleForegroundService.EXTRA_BODY, body)
            putExtra(BleForegroundService.EXTRA_TASK_ID, taskId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun showNotification(context: Context, taskId: String, title: String, body: String) {
        val contentIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.let { pi ->
                PendingIntent.getActivity(
                    context, 0, pi,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                                PendingIntent.FLAG_IMMUTABLE else 0
                )
            }

        val notificationId = NOTIFICATION_ID_BASE + (taskId.hashCode() and 0x0FFF_FFFF)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId, notification)
    }

}
