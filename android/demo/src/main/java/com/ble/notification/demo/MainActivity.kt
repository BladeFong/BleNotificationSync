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
import com.ble.notification.sdk.ReminderCallback
import com.ble.notification.sdk.SdkError
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var sdk: BleNotificationSDK
    private lateinit var toolbar: Toolbar
    private lateinit var btnScanPair: Button
    private lateinit var btnUnpair: Button
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var tvLog: TextView

    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
        tvLog = findViewById(R.id.tv_log)

        btnScanPair = findViewById(R.id.btn_scan_pair)
        btnUnpair = findViewById(R.id.btn_unpair)
        etMessage = findViewById(R.id.et_message)
        btnSend = findViewById(R.id.btn_send)

        btnScanPair.setOnClickListener { startPairing() }
        btnUnpair.setOnClickListener { doUnpair() }
        btnSend.setOnClickListener { doSendReminder() }

        updateButtonStates()
    }

    private fun log(msg: String) {
        val ts = sdf.format(Date())
        val line = "$ts $msg\n"
        tvLog.append(line)
    }

    private fun startPairing() {
        log("开始扫描绑定…")
        sdk.startPairing(this, "BLE通知测试", object : PairingCallback {
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
                Toast.makeText(this@MainActivity, "绑定成功！", Toast.LENGTH_SHORT).show()
                runOnUiThread { updateButtonStates() }
            }
            override fun onError(error: SdkError) {
                log("失败: ${error.message}")
                runOnUiThread { updateButtonStates() }
            }
        })
    }

    private fun doUnpair() {
        sdk.unpair(packageName)
        log("已解除绑定")
        Toast.makeText(this, "已解除绑定", Toast.LENGTH_SHORT).show()
        updateButtonStates()
    }

    private fun doSendReminder() {
        val message = etMessage.text.toString().ifBlank {
            getString(R.string.s_default_text)
        }
        val triggerAt = System.currentTimeMillis() + 10_000

        log("设置闹钟: $message")
        sdk.setReminder(
            taskId = "demo_${System.currentTimeMillis()}",
            title = "BLE 通知测试",
            body = message,
            triggerAt = triggerAt,
            callback = object : ReminderCallback {
                override fun onScheduled(taskId: String) {
                    log("闹钟已设置，10秒后触发，退出App")
                    Toast.makeText(this@MainActivity, "提醒已设置，10 秒后触发", Toast.LENGTH_SHORT).show()
                    finish()
                }
                override fun onTriggered(taskId: String) {}
                override fun onSynced(taskId: String, success: Boolean) {}
            }
        )
    }

    private fun updateButtonStates() {
        val paired = sdk.isPaired(packageName)
        btnScanPair.isEnabled = !paired
        btnUnpair.isEnabled = paired
        log("按钮状态: 绑定=$paired")
    }
}
