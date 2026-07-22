package com.ble.notification.ui

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.ble.notification.pairing.PairedDevice
import com.ble.notification.pairing.PairingCallback
import com.ble.notification.sdk.BleNotificationSDK
import com.ble.notification.sdk.SdkError

class DeviceManagerFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val primaryColor = getHostPrimaryColor(context)

        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme(
                    colors = lightColors(
                        primary = primaryColor,
                        secondary = primaryColor,
                        background = Color(0xFFF5F5F7),
                        surface = Color.White
                    )
                ) {
                    DeviceManagerScreen(
                        onBackClick = {
                            activity?.onBackPressedDispatcher?.onBackPressed()
                        },
                        onAddDeviceClick = {
                            startScanPairing()
                        }
                    )
                }
            }
        }
    }

    private fun startScanPairing() {
        val act = activity as? FragmentActivity ?: return
        val sdk = BleNotificationSDK.getInstance()

        sdk.startPairing(act, "DeviceManager", object : PairingCallback {
            override fun onScanSuccess() {}
            override fun onConnecting() {}
            override fun onRegistering() {}
            override fun onPaired() {
                act.runOnUiThread {
                    Toast.makeText(act, "绑定成功", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onError(error: SdkError) {
                act.runOnUiThread {
                    val msg = if (error is SdkError.AlreadyPaired) "该设备已绑定" else "绑定失败: ${error.message}"
                    Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun getHostPrimaryColor(context: Context): Color {
        val typedValue = TypedValue()
        val theme = context.theme
        val hasColor = theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true) ||
                theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)

        if (hasColor && typedValue.data != 0) {
            val color = Color(typedValue.data)
            val luminance = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
            if (luminance < 0.85f) {
                return color
            }
        }
        return Color(0xFF1565C0) // 经典 Material 深蓝色 (Deep Blue)
    }
}

@Composable
fun DeviceManagerScreen(
    onBackClick: () -> Unit,
    onAddDeviceClick: () -> Unit
) {
    val sdk = BleNotificationSDK.getInstance()
    val pairedDevices by sdk.pairedDevicesState.collectAsState(initial = emptyList())
    var deviceToUnpair by remember { mutableStateOf<PairedDevice?>(null) }

    val primaryColor = MaterialTheme.colors.primary
    val onPrimaryColor = MaterialTheme.colors.onPrimary

    Scaffold(
        topBar = {
            Surface(
                color = primaryColor,
                elevation = 4.dp
            ) {
                TopAppBar(
                    title = {
                        Text(
                            "已关联 PC 设备",
                            fontWeight = FontWeight.Bold,
                            color = onPrimaryColor
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = onPrimaryColor
                            )
                        }
                    },
                    backgroundColor = Color.Transparent,
                    contentColor = onPrimaryColor,
                    elevation = 0.dp,
                    modifier = Modifier.statusBarsPadding()
                )
            }
        },

        bottomBar = {
            BottomBarContent(onAddDeviceClick = onAddDeviceClick)
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colors.background)
        ) {
            if (pairedDevices.isEmpty()) {
                EmptyStateView()
            } else {
                DeviceListView(
                    devices = pairedDevices,
                    onUnpairClick = { device ->
                        deviceToUnpair = device
                    }
                )
            }
        }
    }

    deviceToUnpair?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToUnpair = null },
            title = { Text("解除绑定", fontWeight = FontWeight.Bold) },
            text = { Text("确定要解除与 \"${device.name}\" 的绑定关系吗？解除后将无法同步通知。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        sdk.unpair(device.uuid)
                        deviceToUnpair = null
                    }
                ) {
                    Text("解除绑定", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToUnpair = null }) {
                    Text("取消", color = Color.Gray)
                }
            }
        )
    }
}

@Composable
fun DeviceListView(
    devices: List<PairedDevice>,
    onUnpairClick: (PairedDevice) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(devices, key = { it.uuid }) { device ->
            DeviceCard(device = device, onUnpairClick = { onUnpairClick(device) })
        }
    }
}

@Composable
fun DeviceCard(
    device: PairedDevice,
    onUnpairClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        backgroundColor = MaterialTheme.colors.surface,
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Computer,
                    contentDescription = "PC",
                    tint = MaterialTheme.colors.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = device.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF212121)
                    )
                    Text(
                        text = "已绑定",
                        fontSize = 12.sp,
                        color = Color(0xFF757575)
                    )
                }
            }

            OutlinedButton(
                onClick = onUnpairClick,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD32F2F)),
                modifier = Modifier.height(36.dp)
            ) {
                Text("解除绑定", fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun EmptyStateView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Computer,
            contentDescription = "无设备",
            modifier = Modifier.size(64.dp),
            tint = Color.LightGray
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "暂无绑定的 PC 设备",
            fontSize = 16.sp,
            color = Color(0xFF424242),
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "点击下方按钮扫描 PC 端二维码进行关联",
            fontSize = 13.sp,
            color = Color.Gray
        )
    }
}

@Composable
fun BottomBarContent(onAddDeviceClick: () -> Unit) {
    Surface(
        elevation = 8.dp,
        color = MaterialTheme.colors.surface,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {

            Text(
                text = "提示：扫描 PC 端客户端显示的 BLE 配对二维码即可完成绑定",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Button(
                onClick = onAddDeviceClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.primary,
                    contentColor = MaterialTheme.colors.onPrimary
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = "Scan",
                        tint = MaterialTheme.colors.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "扫描二维码绑定新设备",
                        fontSize = 15.sp,
                        color = MaterialTheme.colors.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

