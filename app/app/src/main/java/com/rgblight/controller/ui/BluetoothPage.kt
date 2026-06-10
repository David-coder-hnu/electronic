package com.rgblight.controller.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rgblight.controller.MainViewModel
import com.rgblight.controller.data.BtDevice
import com.rgblight.controller.ui.theme.*

@Composable
fun BluetoothPage(vm: MainViewModel) {
    val devices by vm.devices.collectAsState()
    val isScanning by vm.isScanning.collectAsState()
    val isConnected by vm.isConnected.collectAsState()
    val connectionError by vm.connectionError.collectAsState()
    val context = LocalContext.current

    // Permission launcher for Android 12+
    var hasBlePermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                android.content.pm.PackageManager.PERMISSION_GRANTED ==
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            else true
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasBlePermission = grants.values.all { it }
    }

    fun requestAndScan() {
        if (hasBlePermission) {
            if (isScanning) vm.stopScan() else vm.startScan()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permLauncher.launch(arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                ))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            "蓝牙设备",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        // ── Connection error ──
        connectionError?.let { err ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AccentWarm.copy(alpha = 0.15f))
                    .padding(12.dp)
            ) {
                Text(err, color = AccentWarm, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
        }

        // Scan button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { requestAndScan() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning) AccentWarm else Accent
                )
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    if (isScanning) "停止扫描" else "扫描设备",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (isScanning) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Accent,
                trackColor = BgTertiary
            )
        }

        Spacer(Modifier.height(16.dp))

        // Connected device banner
        if (isConnected) {
            val connected = devices.find { it.isConnected }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Success.copy(alpha = 0.1f))
                    .border(1.dp, Success.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Success)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            connected?.name ?: "CH9143",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            connected?.address ?: "已连接",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                    TextButton(onClick = { vm.disconnect() }) {
                        Text("断开", color = AccentWarm, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Device list
        Text(
            if (devices.isEmpty() && !isScanning) "未发现设备，点击扫描开始搜索"
            else if (devices.isEmpty()) "正在搜索..."
            else "附近设备",
            color = TextSecondary,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices) { device ->
                DeviceCard(device, onConnect = { vm.connect(device) })
            }
        }
    }
}

@Composable
private fun DeviceCard(device: BtDevice, onConnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgSecondary)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                device.name,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(2.dp))
            Row {
                Text("${device.rssi} dBm", color = TextSecondary, fontSize = 13.sp)
                if (device.isPaired) {
                    Spacer(Modifier.width(8.dp))
                    Text("已配对", color = Success, fontSize = 13.sp)
                }
            }
        }
        Button(
            onClick = onConnect,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text("连接", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}
