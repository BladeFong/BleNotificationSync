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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.ble.notification.pairing.PairedDevice
import com.ble.notification.pairing.PairingCallback
import com.ble.notification.sdk.BleNotificationSDK
import com.ble.notification.sdk.R
import com.ble.notification.sdk.SdkError

// Compose typography constants (CLAUDE.md: text_size_title=22sp, body=18sp, caption=16sp)
private val TextSizeTitle = 22.sp
private val TextSizeBody = 18.sp
private val TextSizeCaption = 16.sp

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
                val colors = if (isSystemInDarkTheme()) {
                    darkColors(
                        primary = primaryColor,
                        secondary = primaryColor
                    )
                } else {
                    lightColors(
                        primary = primaryColor,
                        secondary = primaryColor
                    )
                }
                MaterialTheme(colors = colors) {
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
        val fm = parentFragmentManager

        val scannerFragment = com.ble.notification.qr.QrScannerFragment.newInstance { qrResult ->
            fm.popBackStack()

            if (qrResult == null) return@newInstance

            sdk.startPairingDirectly(act, qrResult, object : PairingCallback {
                override fun onScanSuccess() {}
                override fun onConnecting() {}
                override fun onRegistering() {}
                override fun onPaired() {
                    act.runOnUiThread {
                        Toast.makeText(act, getString(R.string.s_pair_success), Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onError(error: SdkError) {
                    act.runOnUiThread {
                        val msg = if (error is SdkError.AlreadyPaired) {
                            getString(R.string.s_device_already_paired)
                        } else {
                            getString(R.string.s_pair_failed, error.message)
                        }
                        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        fm.beginTransaction()
            .replace((view?.parent as? View)?.id ?: android.R.id.content, scannerFragment)
            .addToBackStack(null)
            .commit()
    }


    private fun getHostPrimaryColor(context: Context): Color {
        val theme = context.theme
        val attrIds = intArrayOf(
            androidx.appcompat.R.attr.colorPrimary,
            android.R.attr.colorPrimary
        )

        for (attrId in attrIds) {
            val typedValue = TypedValue()
            if (theme.resolveAttribute(attrId, typedValue, true)) {
                val typedArray = theme.obtainStyledAttributes(intArrayOf(attrId))
                val colorInt = typedArray.getColor(0, 0)
                typedArray.recycle()

                if (colorInt != 0) {
                    val color = Color(colorInt)
                    val luminance = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
                    if (luminance < 0.95f && color.alpha > 0.1f) {
                        return color
                    }
                }
            }
        }
        return Color(0xFF2196F3)
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
                            stringResource(R.string.s_device_manager_title),
                            fontWeight = FontWeight.Bold,
                            color = onPrimaryColor
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.s_back),
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
            title = { Text(stringResource(R.string.s_unpair_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.s_unpair_confirm_message, device.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        sdk.unpair(device.uuid)
                        deviceToUnpair = null
                    }
                ) {
                    Text(stringResource(R.string.s_unpair), color = MaterialTheme.colors.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToUnpair = null }) {
                    Text(stringResource(R.string.s_cancel), color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
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
            .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
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
                    contentDescription = stringResource(R.string.s_pc_device),
                    tint = MaterialTheme.colors.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = device.name,
                        fontSize = TextSizeCaption,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colors.onSurface
                    )
                    Text(
                        text = stringResource(R.string.s_paired),
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            OutlinedButton(
                onClick = onUnpairClick,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colors.error),
                modifier = Modifier.height(36.dp)
            ) {
                Text(stringResource(R.string.s_unpair), fontSize = 13.sp)
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
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.s_no_paired_devices),
            fontSize = TextSizeCaption,
            color = MaterialTheme.colors.onBackground,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.s_empty_device_hint),
            fontSize = 13.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
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
                text = stringResource(R.string.s_bottom_scan_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
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
                        contentDescription = stringResource(R.string.s_scan_to_bind_new_device),
                        tint = MaterialTheme.colors.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.s_scan_to_bind_new_device),
                        fontSize = 15.sp,
                        color = MaterialTheme.colors.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
