package com.ble.notification.pairing

import android.content.Context
import com.ble.notification.ble.BleClient
import com.ble.notification.ble.ConnectionCallback
import com.ble.notification.protocol.FrameEncoder
import com.ble.notification.qr.QrResult
import com.ble.notification.sdk.SdkError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PairingState {
    IDLE, CONNECTING, REGISTERING, PAIRED
}

interface PairingCallback {
    fun onScanSuccess()
    fun onQrResult(mac: String, uuid: String) {}
    fun onConnecting()
    fun onRegistering()
    fun onPaired()
    fun onError(error: SdkError)
}

data class PairedDevice(
    val uuid: String,
    val name: String,
    val appName: String,
    val pairedAt: Long = System.currentTimeMillis()
)

class PairingManager(private val context: Context) {

    var currentState: PairingState = PairingState.IDLE
        private set

    private val _pairedDevicesFlow = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevicesFlow: StateFlow<List<PairedDevice>> = _pairedDevicesFlow.asStateFlow()

    private val prefs by lazy {
        val masterKey = androidx.security.crypto.MasterKey.Builder(context.applicationContext)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            context.applicationContext,
            "ble_notification_pairings",
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        try {
            migrateLegacyPairing()
            refreshPairedDevices()
        } catch (_: Exception) {}
    }

    private fun refreshPairedDevices() {
        _pairedDevicesFlow.value = getPairedDevices()
    }

    private fun migrateLegacyPairing() {
        val allKeys = prefs.all.keys
        for (key in allKeys) {
            if (key.startsWith("pairing_") && !key.startsWith("pairing_uuid_")) {
                val value = prefs.getString(key, null) ?: continue
                val parts = value.split("|", limit = 5)
                if (parts.size >= 2) {
                    val mac = parts[0]
                    val appName = parts[1]
                    val baseKeyStr = parts.getOrNull(2) ?: ""
                    val deviceName = parts.getOrNull(3) ?: "Legacy Device"
                    val legacyUuid = "legacy_$mac"
                    savePairingInternal(legacyUuid, deviceName, appName, baseKeyStr, System.currentTimeMillis())
                    prefs.edit().remove(key).apply()
                }
            }
        }
    }

    fun startPairing(
        qrResult: QrResult,
        appName: String,
        packageName: String,
        callback: PairingCallback
    ) {
        if (isPaired(qrResult.uuid)) {
            callback.onError(SdkError.AlreadyPaired())
            return
        }

        if (currentState != PairingState.IDLE) {
            currentState = PairingState.IDLE
        }

        callback.onScanSuccess()
        transitionTo(PairingState.CONNECTING, callback)

        val random = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

        android.util.Log.d("BleClient", "PairingManager: starting connectWithScan")
        BleClient.connectWithScan(
            context = context,
            callback = object : ConnectionCallback {
                override fun onReady(gatt: android.bluetooth.BluetoothGatt) {
                    transitionTo(PairingState.REGISTERING, callback)

                    val registerFrame = FrameEncoder.encodeRegister(
                        appName = appName,
                        packageName = packageName,
                        random = random,
                        androidId = getAndroidId(),
                        deviceName = getDeviceName()
                    )
                    android.util.Log.d("BleClient", "REGISTER frame: ${registerFrame.size} bytes")


                    val service = gatt.getService(BleClient.SERVICE_UUID)
                    if (service == null) {
                        android.util.Log.e("Pairing", "Service not found: ${BleClient.SERVICE_UUID}")
                        callback.onError(SdkError.ServiceNotFound())
                        return
                    }
                    val characteristic = service.getCharacteristic(BleClient.WRITE_CHARACTERISTIC_UUID)
                    if (characteristic == null) {
                        android.util.Log.e("Pairing", "Characteristic not found: ${BleClient.WRITE_CHARACTERISTIC_UUID}")
                        callback.onError(SdkError.ServiceNotFound())
                        return
                    }
                    val writeOk = com.ble.notification.ble.BleCompat.writeCharacteristic(gatt, characteristic, registerFrame)
                    android.util.Log.d("BleClient", "writeCharacteristic returned: $writeOk")

                    // 异步发送 APP 图标（延迟 300ms，避开 REGISTER 帧底层的 GATT Write Pending 状态）
                    Thread {
                        try { Thread.sleep(300) } catch (_: InterruptedException) {}
                        sendAppIcon(gatt, characteristic, packageName)
                    }.start()

                    val baseKey = deriveBaseKey(packageName, random)
                    val actualMac = gatt.device.address
                    val deviceName = qrResult.name ?: gatt.device.name ?: "PC Device"
                    android.util.Log.d(
                        "BleClient",
                        "Saving pairing: pc_uuid=${qrResult.uuid} pc_name=$deviceName android_id=${getAndroidId()} phone_device=${getDeviceName()}"
                    )

                    savePairing(qrResult.uuid, deviceName, appName, baseKey)
                    transitionTo(PairingState.PAIRED, callback)
                    callback.onPaired()
                    currentState = PairingState.IDLE
                }

                override fun onError(error: SdkError) {
                    currentState = PairingState.IDLE
                    callback.onError(error)
                }
            }
        )
    }

    fun isPaired(uuid: String? = null): Boolean {
        if (uuid != null) {
            return prefs.contains(keyFor(uuid))
        }
        return getPairedDevices().isNotEmpty()
    }

    fun savePairing(
        uuid: String,
        deviceName: String,
        appName: String,
        baseKey: ByteArray? = null,
        pairedAt: Long = System.currentTimeMillis()
    ) {
        val keyStr = if (baseKey != null && baseKey.isNotEmpty()) {
            "b64:" + android.util.Base64.encodeToString(baseKey, android.util.Base64.NO_WRAP)
        } else ""
        savePairingInternal(uuid, deviceName, appName, keyStr, pairedAt)
        refreshPairedDevices()
    }

    private fun savePairingInternal(
        uuid: String,
        deviceName: String,
        appName: String,
        baseKeyStr: String,
        pairedAt: Long
    ) {
        val value = "$uuid|$deviceName|$appName|$baseKeyStr|$pairedAt"
        prefs.edit().putString(keyFor(uuid), value).apply()
    }

    fun getDeviceName(uuid: String): String? {
        val value = prefs.getString(keyFor(uuid), null) ?: return null
        val parts = value.split("|", limit = 5)
        return parts.getOrNull(1)
    }

    fun getPairedAppName(uuid: String): String? {
        val value = prefs.getString(keyFor(uuid), null) ?: return null
        val parts = value.split("|", limit = 5)
        return parts.getOrNull(2)
    }

    fun getBaseKey(uuid: String): ByteArray? {
        val value = prefs.getString(keyFor(uuid), null) ?: return null
        val parts = value.split("|", limit = 5)
        val keyStr = parts.getOrNull(3) ?: return null
        if (keyStr.isEmpty()) return null
        if (keyStr.startsWith("b64:")) {
            return try {
                android.util.Base64.decode(keyStr.substring(4), android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                null
            }
        }
        return try {
            keyStr.split(",").map {
                val v = it.toIntOrNull() ?: 0
                v.toByte()
            }.toByteArray()
        } catch (e: Exception) {
            null
        }
    }


    fun getPairedDevices(): List<PairedDevice> {
        return prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith("pairing_uuid_")) return@mapNotNull null
            val uuid = key.removePrefix("pairing_uuid_")
            val parts = (value as String).split("|", limit = 5)
            if (parts.size < 3) return@mapNotNull null
            val name = parts[1]
            val appName = parts[2]
            val pairedAt = parts.getOrNull(4)?.toLongOrNull() ?: System.currentTimeMillis()
            PairedDevice(uuid, name, appName, pairedAt)
        }
    }

    fun unpair(uuid: String) {
        prefs.edit().remove(keyFor(uuid)).apply()
        refreshPairedDevices()
    }

    fun unpairAll() {
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith("pairing_")) {
                editor.remove(key)
            }
        }
        editor.apply()
        refreshPairedDevices()
    }

    private fun transitionTo(state: PairingState, callback: PairingCallback) {
        currentState = state
        when (state) {
            PairingState.CONNECTING -> callback.onConnecting()
            PairingState.REGISTERING -> callback.onRegistering()
            PairingState.PAIRED -> { /* already called */ }
            PairingState.IDLE -> { /* reset */ }
        }
    }

    private fun keyFor(uuid: String): String = "pairing_uuid_$uuid"

    private fun deriveBaseKey(packageName: String, random: ByteArray): ByteArray {
        val ikm = packageName.toByteArray(Charsets.UTF_8) + random
        return com.ble.notification.crypto.NativeCrypto.hkdfSha256(
            com.ble.notification.crypto.NativeCrypto.SALT, ikm, 32
        ) ?: throw IllegalStateException("HKDF key derivation failed")
    }

    private fun getAndroidId(): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown_android"
    }

    private fun getDeviceName(): String {
        return try {
            val name = android.provider.Settings.Global.getString(context.contentResolver, "device_name")
            if (name.isNullOrBlank()) android.os.Build.MODEL else name
        } catch (e: Exception) {
            android.os.Build.MODEL ?: "Android Device"
        }
    }

    private fun sendAppIcon(
        gatt: android.bluetooth.BluetoothGatt,
        characteristic: android.bluetooth.BluetoothGattCharacteristic,
        packageName: String
    ) {
        android.util.Log.d("Pairing", "sendAppIcon: start for package=$packageName")
        val iconBytes = extractAppIcon(packageName)
        if (iconBytes == null || iconBytes.isEmpty()) {
            android.util.Log.w("Pairing", "sendAppIcon: iconBytes is null or empty, aborting icon send")
            return
        }

        val chunkSize = 200
        val totalSeq = (iconBytes.size + chunkSize - 1) / chunkSize
        android.util.Log.d("Pairing", "sendAppIcon: iconBytes size=${iconBytes.size}, totalSeq=$totalSeq")

        try { Thread.sleep(100) } catch (_: InterruptedException) {}

        // 使用标准的 WRITE_TYPE_DEFAULT 配合 sleep 间隔，避开 WinRT 对 WRITE_TYPE_NO_RESPONSE 的 COM Crash
        val writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        for (seq in 0 until totalSeq) {
            val start = seq * chunkSize
            val end = minOf(start + chunkSize, iconBytes.size)
            val chunk = iconBytes.copyOfRange(start, end)
            val iconFrame = FrameEncoder.encodeIconData(chunk, seq, totalSeq)

            var sent = false
            for (retry in 0 until 5) {
                val ok = com.ble.notification.ble.BleCompat.writeCharacteristic(gatt, characteristic, iconFrame, writeType)
                if (ok) {
                    sent = true
                    break
                }
                try { Thread.sleep(30) } catch (_: InterruptedException) {}
            }
            android.util.Log.d("Pairing", "Sent ICON_DATA frame $seq/$totalSeq, success=$sent")
            try { Thread.sleep(50) } catch (_: InterruptedException) {}
        }

        val iconEndFrame = FrameEncoder.encodeIconEnd(iconBytes.size)
        var endSent = false
        for (retry in 0 until 5) {
            val ok = com.ble.notification.ble.BleCompat.writeCharacteristic(gatt, characteristic, iconEndFrame, writeType)
            if (ok) { endSent = true; break }
            try { Thread.sleep(30) } catch (_: InterruptedException) {}
        }
        android.util.Log.d("Pairing", "App icon ICON_END sent, success=$endSent")
    }

    private fun extractAppIcon(packageName: String): ByteArray? {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val drawable = pm.getApplicationIcon(appInfo)
            val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 108
            val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 108

            val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)

            val scaledBmp = android.graphics.Bitmap.createScaledBitmap(bitmap, 64, 64, true)
            val stream = java.io.ByteArrayOutputStream()
            scaledBmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, stream)
            val bytes = stream.toByteArray()
            android.util.Log.d("Pairing", "extractAppIcon: SUCCESS, bytes=${bytes.size}")
            bytes
        } catch (e: Throwable) {
            android.util.Log.e("Pairing", "extractAppIcon: FAILED for package=$packageName", e)
            null
        }
    }
}


