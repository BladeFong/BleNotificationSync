package com.ble.notification.pairing

import android.content.Context
import com.ble.notification.ble.BleClient
import com.ble.notification.ble.ConnectionCallback
import com.ble.notification.protocol.FrameEncoder
import com.ble.notification.qr.QrResult

enum class PairingState {
    IDLE,
    CONNECTING,
    REGISTERING,
    PAIRED
}

interface PairingCallback {
    fun onScanSuccess()
    fun onConnecting()
    fun onRegistering()
    fun onPaired()
    fun onError(error: String)
}

class PairingManager {

    var currentState: PairingState = PairingState.IDLE
        private set

    private val pairedDevices = mutableMapOf<String, PairingInfo>()

    private data class PairingInfo(
        val mac: String,
        val appName: String
    )

    fun startPairing(context: Context, qrResult: QrResult, callback: PairingCallback) {
        if (currentState != PairingState.IDLE) {
            callback.onError("Already in state: $currentState")
            return
        }

        callback.onScanSuccess()
        transitionTo(PairingState.CONNECTING, callback)

        BleClient.connect(context, qrResult.mac, object : ConnectionCallback {
            override fun onReady(gatt: android.bluetooth.BluetoothGatt) {
                transitionTo(PairingState.REGISTERING, callback)

                val registerFrame = FrameEncoder.encodeRegister(
                    appName = qrResult.uuid,
                    packageName = qrResult.mac
                )
                gatt.getService(BleClient.SERVICE_UUID)
                    ?.getCharacteristic(BleClient.WRITE_CHARACTERISTIC_UUID)
                    ?.let { characteristic ->
                        characteristic.value = registerFrame
                        gatt.writeCharacteristic(characteristic)
                        transitionTo(PairingState.PAIRED, callback)
                        callback.onPaired()
                    }
                    ?: callback.onError("Required BLE service/characteristic not found")
            }

            override fun onError(error: String) {
                transitionTo(PairingState.IDLE, callback)
                callback.onError(error)
            }
        })
    }

    fun isPaired(packageName: String): Boolean {
        return pairedDevices.containsKey(packageName)
    }

    fun savePairing(packageName: String, mac: String, appName: String) {
        pairedDevices[packageName] = PairingInfo(mac, appName)
    }

    fun getPairedMac(packageName: String): String? {
        return pairedDevices[packageName]?.mac
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
}
