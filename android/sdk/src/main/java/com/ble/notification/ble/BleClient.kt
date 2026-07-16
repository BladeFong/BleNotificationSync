package com.ble.notification.ble

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ble.notification.sdk.SdkError
import java.util.UUID

interface ConnectionCallback {
    fun onReady(gatt: BluetoothGatt)
    fun onError(error: SdkError)
}

object BleClient {

    private const val TARGET_MTU = 247

    val SERVICE_UUID: UUID = UUID.fromString("0000A1B2-0000-1000-8000-00805F9B34FB")
    val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000C3D4-0000-1000-8000-00805F9B34FB")

    fun hasPermissions(context: Context): Boolean {
        return getMissingPermissions(context).isEmpty()
    }

    fun getMissingPermissions(context: Context): Array<String> {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        return required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    fun connect(context: Context, mac: String, callback: ConnectionCallback) {
        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            callback.onError(SdkError.BluetoothUnavailable())
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            callback.onError(SdkError.BluetoothDisabled())
            return
        }

        val missing = getMissingPermissions(context)
        if (missing.isNotEmpty()) {
            callback.onError(SdkError.PermissionDenied(missing.toList()))
            return
        }

        val device = bluetoothAdapter.getRemoteDevice(mac)
        if (device == null) {
            callback.onError(SdkError.DeviceNotFound(mac))
            return
        }

        device.connectGatt(
            context,
            false,
            object : BluetoothGattCallback() {

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        callback.onError(SdkError.ConnectionFailed("status=$status"))
                        gatt.close()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                        gatt.requestMtu(TARGET_MTU)
                    } else {
                        callback.onError(SdkError.ConnectionFailed("service discovery: $status"))
                        gatt.close()
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                        callback.onReady(gatt)
                    } else {
                        callback.onError(SdkError.ConnectionFailed("MTU negotiation: $status"))
                        gatt.close()
                    }
                }
            }
        )
    }
}
