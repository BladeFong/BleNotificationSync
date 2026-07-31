package com.ble.notification.sdk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.ble.notification.pairing.PairedDevice
import com.ble.notification.pairing.PairingCallback
import com.ble.notification.pairing.PairingManager
import kotlinx.coroutines.flow.StateFlow

interface SendCallback {
    fun onSuccess()
    fun onError(error: SdkError)
}

data class NotificationAction(
    val label: String,
    val actionId: String
)

class BleNotificationSDK private constructor(private val context: Context) {

    private val pairingManager = PairingManager(context)
    private val permissionHelper = PermissionHelper(context)
    private val navigator = Navigator(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sendCallbacks = mutableMapOf<String, SendCallback>()
    @Volatile private var closed = false

    val pairedDevicesState: StateFlow<List<PairedDevice>> = pairingManager.pairedDevicesFlow

    companion object {
        fun getDefaultChannelId(context: Context): String = "${context.packageName}.notify"

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

        fun getAppName(context: Context): String {
            return try {
                val pm = context.packageManager
                val ai = pm.getApplicationInfo(context.packageName, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                "Notification"
            }
        }

        fun createNotificationChannel(
            context: Context,
            channelId: String = getDefaultChannelId(context),
            channelName: String? = null,
            channelDescription: String? = null
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val existingChannel = nm.getNotificationChannel(channelId)
            if (existingChannel != null) {
                return
            }

            val name = channelName ?: getAppName(context)
            val desc = channelDescription ?: context.getString(R.string.s_notification_channel_desc)

            val channel = android.app.NotificationChannel(
                channelId,
                name,
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = desc
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 注册位置权限 Launcher。**必须在 Activity.onCreate() 中调用。**
     */
    fun registerPermissionLaunchers(activity: FragmentActivity) {
        permissionHelper.registerLaunchers(activity)
    }

    /**
     * 统一检查 SDK 所需的所有权限。**在 Activity.onResume() 中调用。**
     */
    fun ensurePermissions(activity: FragmentActivity) {
        permissionHelper.ensurePermissions(activity)
    }

    // ── 配对流程 ──

    fun startPairingDirectly(activity: FragmentActivity, qrResult: com.ble.notification.qr.QrResult, callback: PairingCallback) {
        if (checkClosed(callback)) return
        val appName = "DeviceManager"
        val packageName = context.packageName

        if (isPaired(qrResult.uuid)) {
            callback.onError(SdkError.AlreadyPaired())
            return
        }

        callback.onQrResult(qrResult.mac ?: "", qrResult.uuid)

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

    fun startPairing(activity: FragmentActivity, appName: String, callback: PairingCallback) {
        if (checkClosed(callback)) return

        navigator.showQrScanner(activity) { qrResult ->
            if (qrResult == null) {
                callback.onError(SdkError.Unknown("QR scan cancelled or failed"))
                return@showQrScanner
            }

            startPairingDirectly(activity, qrResult, callback)
        }
    }



    // ── 设备管理 ──

    fun isPaired(uuid: String? = null): Boolean = pairingManager.isPaired(uuid)

    fun getPairedDevices(): List<PairedDevice> = pairingManager.getPairedDevices()

    fun unpair(uuid: String) {
        pairingManager.unpair(uuid)
    }

    fun unpairAll() {
        pairingManager.unpairAll()
    }



    // ── UI 入口 ──

    fun getDeviceManagerFragment(): Fragment = navigator.getDeviceManagerFragment()

    fun openDeviceManager() {
        navigator.openDeviceManager()
    }

    // ── 通知发送 ──

    /** 获取集成 App 的显示名称（来自 AndroidManifest application label） */
    fun getAppName(): String = getAppName(context)

    fun sendNotification(
        title: String,
        body: String,
        actions: List<NotificationAction> = emptyList(),
        callback: SendCallback? = null
    ) {
        if (checkClosed(callback)) return
        val finalTitle = title.ifBlank { getAppName() }
        val defaultChannelId = getDefaultChannelId(context)

        createNotificationChannel(context, defaultChannelId)

        val appIcon = context.applicationInfo.icon.let { if (it != 0) it else android.R.drawable.ic_dialog_info }
        val builder = androidx.core.app.NotificationCompat.Builder(context, defaultChannelId)
            .setSmallIcon(appIcon)
            .setContentTitle(finalTitle)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)

        actions.forEachIndexed { index, action ->
            val intent = Intent("com.ble.notification.ACTION_NOTIFICATION_CLICK").apply {
                `package` = context.packageName
                putExtra("action_id", action.actionId)
                putExtra("title", finalTitle)
                putExtra("body", body)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0x7000_0000 + action.actionId.hashCode() + index,
                intent,
                flags
            )
            builder.addAction(0, action.label, pendingIntent)
        }

        sendNotification(builder, null, callback)
    }

    /**
     * 发送通知并通过 BLE 同步到已配对设备。
     *
     * @param builder 已构建好的 [NotificationCompat.Builder]
     * @param notificationId 本地通知 ID，为 null 时根据 title+body 哈希自动生成
     * @param callback 发送结果回调
     */
    fun sendNotification(
        builder: androidx.core.app.NotificationCompat.Builder,
        notificationId: Int? = null,
        callback: SendCallback? = null
    ) {
        if (checkClosed(callback)) return
        val notification = builder.build()
        val title = notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: getAppName()
        val body = notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""

        // 1. 弹出本地通知
        val id = notificationId ?: (0x4000_0000 + (title.hashCode() xor body.hashCode()))
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(id, notification)

        // 2. 检查是否有配对的设备
        val devices = pairingManager.getPairedDevices()
        if (devices.isEmpty()) {
            callback?.onError(SdkError.NotPaired())
            return
        }

        // 3. 开启前台服务进行蓝牙同步发送
        val sendId = java.util.UUID.randomUUID().toString()
        if (callback != null) {
            sendCallbacks[sendId] = callback
        }

        val intent = Intent(context, BleForegroundService::class.java).apply {
            putExtra(BleForegroundService.EXTRA_TITLE, title)
            putExtra(BleForegroundService.EXTRA_BODY, body)
            putExtra(BleForegroundService.EXTRA_SEND_ID, sendId)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    internal fun notifySendResult(sendId: String, success: Boolean, error: SdkError? = null) {
        mainHandler.post {
            val callback = sendCallbacks.remove(sendId)
            if (success) {
                callback?.onSuccess()
            } else {
                callback?.onError(error ?: SdkError.Unknown("Send failed"))
            }
        }
    }


    // ── 生命周期 ──

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

