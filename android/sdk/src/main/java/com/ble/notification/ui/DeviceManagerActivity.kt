package com.ble.notification.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ble.notification.sdk.BleNotificationSDK

class DeviceManagerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        BleNotificationSDK.getInstance().ensurePermissions(this)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, DeviceManagerFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        BleNotificationSDK.getInstance().ensurePermissions(this)
    }
}
