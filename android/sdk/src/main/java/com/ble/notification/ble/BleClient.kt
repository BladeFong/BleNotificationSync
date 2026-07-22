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

    fun hasPermissions(context: Context): Boolean = getMissingPermissions(context).isEmpty()

    fun getMissingPermissions(context: Context): Array<String> {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    private var servicesDone = false
    private var mtuDone = false
    private var readyCalled = false
    private var activeScanCallback: android.bluetooth.le.ScanCallback? = null

    fun connect(context: Context, mac: String, callback: ConnectionCallback) {
        connectDirectly(context, mac, callback)
    }

    /** 绑定专用：扫描 UUID 找到实际 MAC 后连接 */
    fun connectWithScan(context: Context, callback: ConnectionCallback) {
        android.util.Log.d("BleClient", "connectWithScan called")
        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) { callback.onError(SdkError.BluetoothUnavailable()); return }
        if (!bluetoothAdapter.isEnabled) { callback.onError(SdkError.BluetoothDisabled()); return }

        val missing = getMissingPermissions(context)
        if (missing.isNotEmpty()) { callback.onError(SdkError.PermissionDenied(missing.toList())); return }

        // 检查位置服务是否开启（OEM 设备 GPS 关闭时 BLE 扫描静默返回 0 结果）
        val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val gpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        if (!gpsEnabled) {
            android.util.Log.w("BleClient", "位置服务未开启，BLE 扫描可能无结果")
        }

        // 直连超时后适配器可能需要恢复，重置蓝牙状态
        try {
            bluetoothAdapter.cancelDiscovery()
        } catch (_: Exception) {}

        val scanner = bluetoothAdapter.bluetoothLeScanner
        android.util.Log.d("BleClient", "connectWithScan: context=${context.javaClass.simpleName}, scanner=$scanner, state=${bluetoothAdapter.state}")
        if (scanner == null) { callback.onError(SdkError.ConnectionFailed("no scanner")); return }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var finished = false
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                if (finished) return
                android.util.Log.d("BleClient", "Scan hit: pc_name=${result.device.name} rssi=${result.rssi}")
                val uuids = result.scanRecord?.serviceUuids
                val rawBytes = result.scanRecord?.bytes?.joinToString(" ") { "%02x".format(it) }
                android.util.Log.d("BleClient", "  bytes=$rawBytes")
                // 先检查 UUID，如果广播包包含目标 UUID 则直接连接
                if (uuids != null && uuids.contains(android.os.ParcelUuid(SERVICE_UUID))) {
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    scanner.stopScan(this)
                    connectDirectly(context, result.device.address, callback)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                android.util.Log.e("BleClient", "Scan failed: errorCode=$errorCode")
            }
            override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>?) {
                android.util.Log.d("BleClient", "onBatchScanResults: ${results?.size ?: 0} results")
            }
        }
        android.util.Log.d("BleClient", "ScanCallback registered, scanner=$scanner")
        handler.postDelayed({
            if (!finished) { finished = true; scanner.stopScan(cb); callback.onError(SdkError.ConnectionFailed("scan timeout")) }
        }, 10_000)
        // Android 14 后台扫描必须带非空 ScanFilter，用设备名过滤
        val deviceNames = com.ble.notification.pairing.PairingManager(context.applicationContext)
            .getPairedDevices()
            .map { it.name }
            .filter { it.isNotEmpty() }


        val filters: MutableList<android.bluetooth.le.ScanFilter> = mutableListOf()
        for (name in deviceNames) {
            filters.add(android.bluetooth.le.ScanFilter.Builder().setDeviceName(name).build())
        }
        // 后备：用 Service UUID（部分设备支持硬件过滤）
        filters.add(android.bluetooth.le.ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(SERVICE_UUID)).build())

        android.util.Log.d("BleClient", "Starting scan with ${filters.size} filters: deviceNames=$deviceNames")
        activeScanCallback = cb
        try {
            scanner.startScan(filters, settings, cb)
            android.util.Log.d("BleClient", "startScan() called successfully, waiting for results...")
        } catch (e: Exception) {
            android.util.Log.e("BleClient", "startScan() failed: ${e.message}", e)
            callback.onError(SdkError.ConnectionFailed("startScan: ${e.message}"))
        }
    }

    private fun connectDirectly(context: Context, mac: String, callback: ConnectionCallback) {
        servicesDone = false; mtuDone = false; readyCalled = false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var finished = false
        var gattRef: android.bluetooth.BluetoothGatt? = null

        handler.postDelayed({
            if (!finished) {
                finished = true
                android.util.Log.w("BleClient", "Direct connect timeout")
                gattRef?.let { it.disconnect(); it.close() }
                callback.onError(SdkError.ConnectionFailed("timeout"))
            }
        }, 3_000)

        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) { callback.onError(SdkError.BluetoothUnavailable()); return }
        if (!bluetoothAdapter.isEnabled) { callback.onError(SdkError.BluetoothDisabled()); return }

        val missing = getMissingPermissions(context)
        if (missing.isNotEmpty()) { callback.onError(SdkError.PermissionDenied(missing.toList())); return }

        val device = bluetoothAdapter.getRemoteDevice(mac)
        if (device == null) { callback.onError(SdkError.DeviceNotFound(mac)); return }

        servicesDone = false; mtuDone = false; readyCalled = false
        android.util.Log.d("BleClient", "connectGatt to target device")

        gattRef = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                android.util.Log.d("BleClient", "Connection: status=$status newState=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    handler.removeCallbacksAndMessages(null)
                    finished = true
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    handler.removeCallbacksAndMessages(null)
                    if (status != 0 && !finished) {
                        finished = true
                        callback.onError(SdkError.ConnectionFailed("status=$status"))
                    }
                    gatt.close()
                }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                android.util.Log.d("BleClient", "Services: status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) { servicesDone = true; gatt.requestMtu(TARGET_MTU); tryReady(gatt, callback) }
                else { callback.onError(SdkError.ConnectionFailed("service discovery:$status")); gatt.close() }
            }
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                android.util.Log.d("BleClient", "MTU: status=$status mtu=$mtu")
                if (status == BluetoothGatt.GATT_SUCCESS) { mtuDone = true; tryReady(gatt, callback) }
            }
        }, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    private fun tryReady(gatt: BluetoothGatt, callback: ConnectionCallback) {
        if (servicesDone && mtuDone && !readyCalled) { readyCalled = true; callback.onReady(gatt) }
    }
}
