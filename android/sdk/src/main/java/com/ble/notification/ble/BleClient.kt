package com.ble.notification.ble

import android.bluetooth.BluetoothGatt
import android.net.Uri
import com.ble.notification.qr.QrResult
import java.util.UUID

interface ConnectionCallback {
    fun onReady(gatt: BluetoothGatt)
    fun onError(error: String)
}

object BleClient {

    private const val QR_SCHEME = "ble"
    private const val QR_HOST = "pair"
    private const val TARGET_MTU = 247

    val SERVICE_UUID: UUID = UUID.fromString("0000A1B2-0000-1000-8000-00805F9B34FB")
    val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000C3D4-0000-1000-8000-00805F9B34FB")

    fun parseQrCode(url: String): QrResult? {
        if (url.isBlank()) return null

        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return null
        }

        if (uri.scheme != QR_SCHEME || uri.host != QR_HOST) return null

        val mac = uri.getQueryParameter("mac") ?: return null
        val uuid = uri.getQueryParameter("uuid") ?: return null

        return QrResult(mac, uuid)
    }

    @Suppress("MissingPermission")
    fun connect(mac: String, callback: ConnectionCallback) {
        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            callback.onError("Bluetooth not available")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            callback.onError("Bluetooth is disabled")
            return
        }

        val device = bluetoothAdapter.getRemoteDevice(mac)
        if (device == null) {
            callback.onError("Device not found for MAC: $mac")
            return
        }

        device.connectGatt(
            android.bluetooth.BluetoothAdapter.getDefaultAdapter().applicationContext,
            false,
            object : android.bluetooth.BluetoothGattCallback() {

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                    } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                        callback.onError("Disconnected with status: $status")
                        gatt.close()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                        gatt.requestMtu(TARGET_MTU)
                    } else {
                        callback.onError("Service discovery failed with status: $status")
                        gatt.close()
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                        callback.onReady(gatt)
                    } else {
                        callback.onError("MTU negotiation failed with status: $status")
                        gatt.close()
                    }
                }
            }
        )
    }
}
