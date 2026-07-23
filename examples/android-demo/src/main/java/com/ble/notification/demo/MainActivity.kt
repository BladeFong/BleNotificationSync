package com.ble.notification.demo

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ble.notification.pairing.PairingCallback
import com.ble.notification.sdk.BleNotificationSDK
import com.ble.notification.sdk.LogRepository
import com.ble.notification.sdk.ReminderCallback
import com.ble.notification.sdk.SdkError

class MainActivity : AppCompatActivity() {

    private lateinit var sdk: BleNotificationSDK
    private lateinit var toolbar: Toolbar
    private lateinit var btnDeviceManager: Button
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnScanOnly: Button
    private lateinit var tvLog: TextView

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            log(getString(R.string.s_need_notification_permission))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val appBar = findViewById<android.view.ViewGroup>(R.id.app_bar)
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(R.string.s_app_name)

        sdk = BleNotificationSDK.init(this)
        sdk.registerPermissionLaunchers(this)
        tvLog = findViewById(R.id.tv_log)

        btnDeviceManager = findViewById(R.id.btn_device_manager)
        etMessage = findViewById(R.id.et_message)
        btnSend = findViewById(R.id.btn_send)
        btnScanOnly = findViewById(R.id.btn_scan_only)

        btnDeviceManager.setOnClickListener {
            sdk.openDeviceManager(this)
        }

        btnSend.setOnClickListener { doSendReminder() }
        btnScanOnly.setOnClickListener { doScanOnly() }
        findViewById<Button>(R.id.btn_clear_log).setOnClickListener {
            LogRepository.clear(this)
            tvLog.text = getString(R.string.s_log_waiting)
        }


        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 幂等方法：检查所有权限（BLE + 位置），从系统设置返回时会重新申请
        sdk.ensurePermissions(this)

        // 恢复缓存日志
        val cached = LogRepository.getAll(this)
        if (cached.isNotEmpty()) {
            tvLog.text = cached
        }

        updateButtonStates()
    }


    private fun log(msg: String) {
        val (entry, isUpdated) = LogRepository.appendOrUpdate(this, msg)
        runOnUiThread {
            if (isUpdated) {
                val currentText = tvLog.text.toString().trimEnd()
                val lastNewLineIndex = currentText.lastIndexOf('\n')
                if (lastNewLineIndex >= 0) {
                    tvLog.text = currentText.substring(0, lastNewLineIndex + 1) + entry + "\n"
                } else {
                    tvLog.text = entry + "\n"
                }
            } else {
                if (tvLog.text.isNotEmpty() && !tvLog.text.endsWith("\n")) {
                    tvLog.append("\n")
                }
                tvLog.append("$entry\n")
            }
        }
    }



    private fun startPairing() {
        log("开始扫描绑定…")
        sdk.startPairing(this, getString(R.string.s_app_display_name), object : PairingCallback {
            override fun onScanSuccess() {
                // logged in onQrResult above
            }
            override fun onQrResult(mac: String, uuid: String) {
                log("QR: mac=$mac uuid=$uuid")
                log("连接 $mac …")
            }
            override fun onConnecting() {
                log("GATT 连接中…")
            }
            override fun onRegistering() {
                log("正在注册…")
            }
            override fun onPaired() {
                log("绑定成功！")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "绑定成功！", Toast.LENGTH_SHORT).show()
                    updateButtonStates()
                }
            }

            override fun onError(error: SdkError) {
                val msg = if (error is SdkError.AlreadyPaired) "该设备已绑定" else "失败: ${error.message}"
                log(msg)
                runOnUiThread { updateButtonStates() }
            }
        })
    }

    private fun doUnpair() {
        sdk.unpairAll()
        val msg = getString(R.string.s_unpaired)
        log(msg)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        updateButtonStates()
    }


    private fun doSendReminder() {
        val alarmManager = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()) {
            log(getString(R.string.s_need_alarm_permission))
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.s_cannot_open_alarm_settings), Toast.LENGTH_SHORT).show()
            }
            return
        }

        val message = etMessage.text.toString().ifBlank {
            getString(R.string.s_default_text)
        }
        val triggerAt = System.currentTimeMillis() + 10_000

        log("设置闹钟: $message")
        sdk.setReminder(
            taskId = "demo_${System.currentTimeMillis()}",
            title = getString(R.string.s_notify_title),
            body = message,
            triggerAt = triggerAt,
            callback = object : ReminderCallback {
                override fun onScheduled(taskId: String) {
                    if (isFinishing || isDestroyed) return
                    log("闹钟已设置，10秒后触发，退出App")
                    Toast.makeText(this@MainActivity, getString(R.string.s_reminder_scheduled), Toast.LENGTH_SHORT).show()
                    finish()
                }
                override fun onTriggered(taskId: String) {}
                override fun onSynced(taskId: String, success: Boolean) {}
            }
        )
    }


    private var scanHandler: android.os.Handler? = null

    private fun doScanOnly() {
        val bluetoothManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter ?: @Suppress("DEPRECATION") android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            android.util.Log.e("BleClient", "Activity: 蓝牙不可用")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            android.util.Log.e("BleClient", "Activity: 蓝牙未开启")
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        android.util.Log.d("BleClient", "Activity: scanner=$scanner")
        if (scanner == null) {
            android.util.Log.e("BleClient", "Activity: Scanner 不可用")
            return
        }

        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        var count = 0
        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                count++
                android.util.Log.d("BleClient", "Activity Scan hit #${count}: name=${result.device.name} rssi=${result.rssi}")
            }
            override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>?) {
                android.util.Log.d("BleClient", "Activity onBatchScanResults: ${results?.size ?: 0} results")
            }
            override fun onScanFailed(errorCode: Int) {
                android.util.Log.e("BleClient", "Activity Scan failed: errorCode=$errorCode")
            }
        }

        android.util.Log.d("BleClient", "Activity: 开始前台扫描 (10秒)...")
        btnScanOnly.isEnabled = false
        scanner.startScan(null, settings, callback)

        scanHandler?.removeCallbacksAndMessages(null)
        scanHandler = android.os.Handler(android.os.Looper.getMainLooper()).apply {
            postDelayed({
                scanner.stopScan(callback)
                if (!isFinishing && !isDestroyed) {
                    btnScanOnly.isEnabled = true
                }
                android.util.Log.d("BleClient", "Activity: 扫描结束，共扫到 $count 个设备")
            }, 10_000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanHandler?.removeCallbacksAndMessages(null)
        scanHandler = null
    }


    private var lastPairedCount: Int? = null

    private fun updateButtonStates() {
        val pairedDevices = sdk.getPairedDevices()
        val count = pairedDevices.size

        btnDeviceManager.text = if (count > 0) {
            getString(R.string.s_device_manager_paired, count)
        } else {
            getString(R.string.s_device_manager_none)
        }

        if (lastPairedCount != count) {
            lastPairedCount = count
            log("设备绑定状态更新: 当前已绑定数量=$count")
        }
    }



}



