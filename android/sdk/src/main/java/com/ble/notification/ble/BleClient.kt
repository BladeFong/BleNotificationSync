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

    val SERVICE_UUID: UUID = UUID.fromString("9e1d51a4-9c86-4447-9759-f6222b0f4b36")
    val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("f4788cde-8025-4c07-b352-87db1b272fdf")

    fun hasPermissions(context: Context): Boolean {
        return getMissingPermissions(context).isEmpty()
    }

    fun getMissingPermissions(context: Context): Array<String> {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+ 官方只需 BLUETOOTH_SCAN + BLUETOOTH_CONNECT
            // 但部分厂商仍要求定位权限，否则扫描静默返回空结果
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
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
            android.util.Log.w("BleClient", "Missing permissions: ${missing.joinToString()}")
            callback.onError(SdkError.PermissionDenied(missing.toList()))
            return
        }

        // 检查定位服务（部分厂商即使 API 31+ 仍需定位开启，否则扫描静默失败）
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        val locationEnabled = locationManager?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true
                || locationManager?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true
        if (!locationEnabled) {
            android.util.Log.w("BleClient", "Location services are OFF — BLE scan may return no results")
        }

        val device = bluetoothAdapter.getRemoteDevice(mac)
        if (device == null) {
            callback.onError(SdkError.DeviceNotFound(mac))
            return
        }

        // 先发起一轮 BLE 扫描以刷新系统的蓝牙缓存，解析设备地址类型（Public vs Random）
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            android.util.Log.w("BleClient", "No BLE scanner, direct connect")
            connectDirectly(device, context, callback)
            return
        }

        val scanSettings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val scanCallback = object : android.bluetooth.le.ScanCallback() {
            var isFinished = false

            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                if (isFinished) return
                val uuids = result.scanRecord?.serviceUuids
                android.util.Log.d("BleClient", "Scanned: addr=${result.device.address} uuids=$uuids rssi=${result.rssi}")
                if (uuids == null || !uuids.contains(android.os.ParcelUuid(SERVICE_UUID))) return
                isFinished = true
                try { scanner.stopScan(this) } catch (_: SecurityException) {}
                connectDirectly(result.device, context, callback)
            }

            override fun onScanFailed(errorCode: Int) {
                android.util.Log.e("BleClient", "Scan failed: errorCode=$errorCode")
                if (isFinished) return
                isFinished = true
                connectDirectly(device, context, callback)
            }
        }

        val timeoutRunnable = Runnable {
            if (!scanCallback.isFinished) {
                android.util.Log.w("BleClient", "Scan timeout, direct connect to: $mac")
                scanCallback.isFinished = true
                try { scanner.stopScan(scanCallback) } catch (_: SecurityException) {}
                connectDirectly(device, context, callback)
            }
        }

        handler.postDelayed(timeoutRunnable, 3000)
        try {
            android.util.Log.d("BleClient", "Start scan (no filter) for: $mac")
            scanner.startScan(null, scanSettings, scanCallback)
        } catch (e: SecurityException) {
            scanCallback.isFinished = true
            handler.removeCallbacks(timeoutRunnable)
            connectDirectly(device, context, callback)
        }
    }

    private fun connectDirectly(
        device: android.bluetooth.BluetoothDevice,
        context: Context,
        callback: ConnectionCallback
    ) {
        android.util.Log.d("BleClient", "connectGatt: addr=${device.address} type=${device.type}")
        device.connectGatt(
            context,
            false,
            object : BluetoothGattCallback() {

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    android.util.Log.d("BleClient", "Connection state: status=$status newState=$newState")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        callback.onError(SdkError.ConnectionFailed("status=$status"))
                        gatt.close()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    android.util.Log.d("BleClient", "Services discovered: status=$status")
                    if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                        gatt.requestMtu(TARGET_MTU)
                    } else {
                        callback.onError(SdkError.ConnectionFailed("service discovery: $status"))
                        gatt.close()
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    android.util.Log.d("BleClient", "MTU changed: status=$status mtu=$mtu")
                    if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                        callback.onReady(gatt)
                    } else {
                        callback.onError(SdkError.ConnectionFailed("MTU negotiation: $status"))
                        gatt.close()
                    }
                }
            },
            android.bluetooth.BluetoothDevice.TRANSPORT_LE
        )
    }
}
