package com.ble.notification.ble

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    fun connect(context: Context, mac: String, callback: ConnectionCallback) {
        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) { callback.onError(SdkError.BluetoothUnavailable()); return }
        if (!bluetoothAdapter.isEnabled) { callback.onError(SdkError.BluetoothDisabled()); return }

        val missing = getMissingPermissions(context)
        if (missing.isNotEmpty()) { callback.onError(SdkError.PermissionDenied(missing.toList())); return }

        val device = bluetoothAdapter.getRemoteDevice(mac)
        if (device == null) { callback.onError(SdkError.DeviceNotFound(mac)); return }

        // 策略：先直连旧 MAC，失败再扫描 UUID 找新 MAC
        tryDirectConnect(device, context, mac, callback)
    }

    /** 直连旧 MAC，失败自动切换到扫描模式 */
    private fun tryDirectConnect(
        device: android.bluetooth.BluetoothDevice,
        context: Context,
        savedMac: String,
        callback: ConnectionCallback
    ) {
        val handler = Handler(Looper.getMainLooper())
        var done = false

        val timeout = Runnable {
            if (!done) { done = true; fallbackScan(context, savedMac, callback) }
        }
        handler.postDelayed(timeout, 15_000)

        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (done) return
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    handler.removeCallbacks(timeout)
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // status != 0 → 直连失败，转扫描
                    if (status != 0 && !done) {
                        done = true
                        handler.removeCallbacks(timeout)
                        gatt.close()
                        fallbackScan(context, savedMac, callback)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (done) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    servicesDone = true; gatt.requestMtu(TARGET_MTU); tryReady(gatt, callback)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (done) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mtuDone = true; tryReady(gatt, callback)
                }
            }
        }, TRANSPORT_LE)
    }

    private var servicesDone = false
    private var mtuDone = false
    private var readyCalled = false

    private fun tryReady(gatt: BluetoothGatt, callback: ConnectionCallback) {
        if (servicesDone && mtuDone && !readyCalled) {
            readyCalled = true
            callback.onReady(gatt)
        }
    }

    /** 扫描 UUID 找当前实际 MAC，然后连接 */
    private fun fallbackScan(context: Context, savedMac: String, callback: ConnectionCallback) {
        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            callback.onError(SdkError.ConnectionFailed("no scanner"))
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var retryCount = 0
        var doScan: Runnable? = null

        doScan = Runnable {
            if (finished) return@Runnable
            val scanSettings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val scanCallback = object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                    if (finished) return
                    val uuids = result.scanRecord?.serviceUuids
                    if (uuids == null || !uuids.contains(android.os.ParcelUuid(SERVICE_UUID))) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    try { scanner.stopScan(this) } catch (_: SecurityException) {}
                    servicesDone = false; mtuDone = false; readyCalled = false
                    tryDirectConnect(result.device, context, savedMac, callback)
                }

                override fun onScanFailed(errorCode: Int) {
                    if (finished) return
                    retryCount++
                    if (retryCount < 3) {
                        handler.postDelayed(doScan!!, 2000)
                    } else {
                        finished = true
                        callback.onError(SdkError.ConnectionFailed("scan failed after retries"))
                    }
                }
            }

            try { scanner.startScan(null, scanSettings, scanCallback) } catch (_: SecurityException) {}
            handler.postDelayed({ if (!finished) { finished = true; try { scanner.stopScan(scanCallback) } catch (_: Exception) {} } }, 5000)
        }
        doScan.run()
    }

    private val TRANSPORT_LE = android.bluetooth.BluetoothDevice.TRANSPORT_LE
}
