package com.ble.notification.pairing

import android.content.Context
import com.ble.notification.ble.BleClient
import com.ble.notification.ble.ConnectionCallback
import com.ble.notification.protocol.FrameEncoder
import com.ble.notification.qr.QrResult
import com.ble.notification.sdk.SdkError

enum class PairingState {
    IDLE, CONNECTING, REGISTERING, PAIRED
}

interface PairingCallback {
    fun onScanSuccess()
    fun onConnecting()
    fun onRegistering()
    fun onPaired()
    fun onError(error: SdkError)
}

data class PairedDevice(
    val packageName: String,
    val mac: String,
    val appName: String
)

class PairingManager(private val context: Context) {

    var currentState: PairingState = PairingState.IDLE
        private set

    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(
            "ble_notification_pairings", Context.MODE_PRIVATE
        )
    }

    fun startPairing(
        qrResult: QrResult,
        appName: String,
        packageName: String,
        callback: PairingCallback
    ) {
        if (currentState != PairingState.IDLE) {
            callback.onError(SdkError.Unknown("Already in state: $currentState"))
            return
        }

        callback.onScanSuccess()
        transitionTo(PairingState.CONNECTING, callback)

        val random = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

        BleClient.connect(context, qrResult.mac, object : ConnectionCallback {
            override fun onReady(gatt: android.bluetooth.BluetoothGatt) {
                transitionTo(PairingState.REGISTERING, callback)

                val registerFrame = FrameEncoder.encodeRegister(
                    appName = appName,
                    packageName = packageName,
                    random = random
                )
                gatt.getService(BleClient.SERVICE_UUID)
                    ?.getCharacteristic(BleClient.WRITE_CHARACTERISTIC_UUID)
                    ?.let { characteristic ->
                        characteristic.value = registerFrame
                        gatt.writeCharacteristic(characteristic)
                        // Derive and persist baseKey = HKDF(package+random)
                        val baseKey = deriveBaseKey(packageName, random)
                        savePairing(packageName, qrResult.mac, appName, baseKey)
                        transitionTo(PairingState.PAIRED, callback)
                        callback.onPaired()
                    }
                    ?: callback.onError(SdkError.ServiceNotFound())
            }

            override fun onError(error: SdkError) {
                transitionTo(PairingState.IDLE, callback)
                callback.onError(error)
            }
        })
    }

    fun isPaired(packageName: String): Boolean {
        return prefs.contains(keyFor(packageName))
    }

    fun savePairing(packageName: String, mac: String, appName: String, baseKey: ByteArray? = null) {
        val value = if (baseKey != null) {
            "$mac|$appName|${baseKey.joinToString(",")}"
        } else {
            "$mac|$appName"
        }
        prefs.edit().putString(keyFor(packageName), value).apply()
    }

    fun getPairedMac(packageName: String): String? {
        val value = prefs.getString(keyFor(packageName), null) ?: return null
        return value.substringBefore("|")
    }

    fun getPairedAppName(packageName: String): String? {
        val value = prefs.getString(keyFor(packageName), null) ?: return null
        val parts = value.split("|", limit = 3)
        return parts.getOrNull(1)
    }

    fun getBaseKey(packageName: String): ByteArray? {
        val value = prefs.getString(keyFor(packageName), null) ?: return null
        val parts = value.split("|", limit = 3)
        val keyStr = parts.getOrNull(2) ?: return null
        return keyStr.split(",").map { it.toByte() }.toByteArray()
    }

    fun getPairedDevices(): List<PairedDevice> {
        return prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith("pairing_")) return@mapNotNull null
            val packageName = key.removePrefix("pairing_")
            val parts = (value as String).split("|", limit = 3)
            if (parts.size < 2) return@mapNotNull null
            PairedDevice(packageName, parts[0], parts[1])
        }
    }

    fun unpair(packageName: String) {
        prefs.edit().remove(keyFor(packageName)).apply()
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

    private fun keyFor(packageName: String): String = "pairing_$packageName"

    private fun deriveBaseKey(packageName: String, random: ByteArray): ByteArray {
        val ikm = packageName.toByteArray(Charsets.UTF_8) + random
        return com.ble.notification.crypto.NativeCrypto.hkdfSha256(
            com.ble.notification.crypto.NativeCrypto.SALT, ikm, 32
        ) ?: throw IllegalStateException("HKDF key derivation failed")
    }
}
