package com.ble.notification.demo

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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

class MainActivity : AppCompatActivity() {

    private lateinit var sdk: BleNotificationSDK
    private lateinit var toolbar: Toolbar
    private lateinit var btnScanPair: Button
    private lateinit var btnUnpair: Button
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button

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

        btnScanPair = findViewById(R.id.btn_scan_pair)
        btnUnpair = findViewById(R.id.btn_unpair)
        etMessage = findViewById(R.id.et_message)
        btnSend = findViewById(R.id.btn_send)

        btnScanPair.setOnClickListener { startPairing() }
        btnUnpair.setOnClickListener { doUnpair() }
        btnSend.setOnClickListener { doSendReminder() }

        updateButtonStates()
    }

    private fun startPairing() {
        sdk.startPairing(this, "BLE通知测试", object : PairingCallback {
            override fun onScanSuccess() {
                Toast.makeText(this@MainActivity, "扫码成功，连接中…", Toast.LENGTH_SHORT).show()
            }
            override fun onConnecting() {
                Toast.makeText(this@MainActivity, "正在连接…", Toast.LENGTH_SHORT).show()
            }
            override fun onRegistering() {
                Toast.makeText(this@MainActivity, "正在注册…", Toast.LENGTH_SHORT).show()
            }
            override fun onPaired() {
                Toast.makeText(this@MainActivity, "绑定成功！", Toast.LENGTH_SHORT).show()
                runOnUiThread { updateButtonStates() }
            }
            override fun onError(error: SdkError) {
                Toast.makeText(this@MainActivity, "绑定失败: ${error.message}", Toast.LENGTH_LONG).show()
                runOnUiThread { updateButtonStates() }
            }
        })
    }

    private fun doUnpair() {
        sdk.unpair(packageName)
        Toast.makeText(this, "已解除绑定", Toast.LENGTH_SHORT).show()
        updateButtonStates()
    }

    private fun doSendReminder() {
        val message = etMessage.text.toString().ifBlank {
            getString(R.string.s_default_text)
        }
        val triggerAt = System.currentTimeMillis() + 10_000

        sdk.setReminder(
            taskId = "demo_${System.currentTimeMillis()}",
            title = "BLE 通知测试",
            body = message,
            triggerAt = triggerAt,
            callback = object : ReminderCallback {
                override fun onScheduled(taskId: String) {
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
    }
}
