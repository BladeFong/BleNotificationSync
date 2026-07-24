package com.ble.notification.sdk

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import com.ble.notification.ble.BleClient

/**
 * SDK 权限辅助类：统一管理 BLE、位置、定位总开关等权限的检查与申请流程。
 */
internal class PermissionHelper(private val context: Context) {

    private var foregroundLocationLauncher: ActivityResultLauncher<String>? = null
    private var backgroundLocationLauncher: ActivityResultLauncher<String>? = null

    /**
     * 注册位置权限 Launcher。**必须在 Activity.onCreate() 中调用。**
     */
    fun registerLaunchers(activity: FragmentActivity) {
        foregroundLocationLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) requestBackgroundLocation()
        }

        backgroundLocationLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            android.util.Log.d("BleSDK", "Background location permission result: granted=$granted")
        }
    }

    /**
     * 统一检查 SDK 所需的所有权限。**在 Activity.onResume() 中调用。**
     */
    fun ensurePermissions(activity: FragmentActivity) {
        // 1. BLE 权限
        val missingBle = BleClient.getMissingPermissions(context)
        if (missingBle.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missingBle, 0x7100_0001)
            return
        }

        // 1.5. 检查手机定位服务总开关
        if (!isLocationServiceEnabled(activity)) {
            showLocationServiceDialog(activity)
            return
        }

        // 2. 位置权限（Android 10+）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (foregroundLocationLauncher == null) return

        val bgGranted = ActivityCompat.checkSelfPermission(
            activity, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (bgGranted) return

        val fineGranted = ActivityCompat.checkSelfPermission(
            activity, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            foregroundLocationLauncher?.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        requestBackgroundLocation()
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (backgroundLocationLauncher == null) return

        val bgGranted = ActivityCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (bgGranted) return

        backgroundLocationLauncher?.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun isLocationServiceEnabled(activity: FragmentActivity): Boolean {
        val locationManager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun showLocationServiceDialog(activity: FragmentActivity) {
        try {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.s_location_service_title))
                .setMessage(activity.getString(R.string.s_location_service_message))
                .setPositiveButton(activity.getString(R.string.s_go_to_settings)) { _, _ ->
                    val intent = Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                }
                .setNegativeButton(activity.getString(R.string.s_cancel), null)
                .show()
        } catch (e: Exception) {
            android.util.Log.e("BleSDK", "Failed to show location dialog: ${e.message}")
        }
    }
}
