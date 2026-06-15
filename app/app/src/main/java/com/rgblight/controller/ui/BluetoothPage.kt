package com.rgblight.controller.ui

import android.Manifest
import android.os.Build
import android.util.Log
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
    val lastMac = vm.lastMac

    // ── Permission ──
    var hasBlePermission by remember {
        mutableStateOf(
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    android.content.pm.PackageManager.PERMISSION_GRANTED ==
                        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                else true
            } catch (_: Throwable) { false }
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        try {
            val granted = grants.values.all { it }
            hasBlePermission = granted
            if (granted) vm.loadPairedDevices()
        } catch (_: Throwable) {}
    }

    // Load paired devices on first composition
    LaunchedEffect(Unit) {
        if (hasBlePermission) vm.loadPairedDevices()
    }

    // ── MAC input state ──
    var macInput by remember { mutableStateOf("") }
    var macError by remember { mutableStateOf<String?>(null) }

    fun requestPermissionAndLoad() {
        try {
            if (hasBlePermission) {
                vm.loadPairedDevices()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permLauncher.launch(arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                ))
            }
        } catch (_: Throwable) {}
    }

    fun isValidMac(s: String): Boolean {
        val trimmed = s.trim().replace(":", "").replace("-", "").uppercase()
        return trimmed.length == 12 && trimmed.all { it in '0'..'9' || it in 'A'..'F' }
    }

    fun formatMac(s: String): String {
        val hex = s.trim().replace(":", "").replace("-", "").uppercase()
        if (hex.length != 12) return s
        return hex.chunked(2).joinToString(":")
    }

    fun tryConnectByMac() {
        macError = null
        if (!isValidMac(macInput)) {
            macError = "MAC 地址格式不正确（12位十六进制）"
            return
        }
        val formatted = formatMac(macInput)
        macInput = formatted
        vm.connectByAddress(formatted)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text("蓝牙设备", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        // ── Error banner ──
        connectionError?.let { err ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(AccentWarm.copy(alpha = 0.15f)).padding(12.dp)
            ) { Text(err, color = AccentWarm, fontSize = 14.sp) }
            Spacer(Modifier.height(8.dp))
        }

        // ── Connected banner ──
        if (isConnected) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Success.copy(alpha = 0.1f))
                    .border(1.dp, Success.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Success))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("CH9143 已连接", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(lastMac ?: "", color = TextSecondary, fontSize = 13.sp)
                    }
                    TextButton(onClick = { vm.disconnect() }) {
                        Text("断开", color = AccentWarm, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // ═══════════════════════════════════════════
        // 方式一：一键重连（上次连接的设备）
        // ═══════════════════════════════════════════
        if (!isConnected && lastMac != null) {
            Text("上次连接", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { vm.reconnectLast() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("重新连接 $lastMac", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
        }

        // ═══════════════════════════════════════════
        // 方式二：已配对设备
        // ═══════════════════════════════════════════
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("已配对设备", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { requestPermissionAndLoad() }) {
                Text("刷新", color = Accent, fontSize = 13.sp)
            }
        }

        val pairedDevices = devices.filter { it.isPaired || lastMac == it.address }
        if (pairedDevices.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(BgSecondary).padding(16.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("暂无已配对设备", color = TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "请先在手机 设置 → 蓝牙 中配对 CH9143 模块",
                        color = TextSecondary.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pairedDevices) { device ->
                    DeviceCard(device, isConnected = isConnected, onConnect = { vm.connect(device) })
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ═══════════════════════════════════════════
        // 方式三：手动输入 MAC 地址
        // ═══════════════════════════════════════════
        Text("手动输入 MAC", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = macInput,
                onValueChange = { s -> macInput = s; macError = null },
                modifier = Modifier.weight(1f),
                placeholder = { Text("AA:BB:CC:DD:EE:FF", color = TextSecondary.copy(alpha = 0.4f), fontSize = 13.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { tryConnectByMac() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = BgTertiary
                ),
                isError = macError != null,
                supportingText = macError?.let { { Text(it, color = AccentWarm, fontSize = 11.sp) } }
            )
            Button(
                onClick = { tryConnectByMac() },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text("连接", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(BgTertiary))
        Spacer(Modifier.height(12.dp))

        // ═══════════════════════════════════════════
        // 方式四：BLE 扫描（备选，可能不稳定）
        // ═══════════════════════════════════════════
        Text("BLE 扫描（备选）", color = TextSecondary.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (isScanning) vm.stopScan() else vm.startScan()
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning) AccentWarm else BgTertiary
                )
            ) {
                if (isScanning) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (isScanning) "停止扫描" else "扫描附近设备",
                    color = if (isScanning) Color.White else TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }

        if (isScanning) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = BgTertiary)
        }

        // Scanned devices
        val scannedDevices = devices.filter { !it.isPaired && lastMac != it.address }
        if (scannedDevices.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(scannedDevices) { device ->
                    DeviceCard(device, isConnected = isConnected, onConnect = { vm.connect(device) })
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "提示：优先使用\"已配对设备\"，扫描功能在部分手机上可能不稳定",
            color = TextSecondary.copy(alpha = 0.4f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DeviceCard(device: BtDevice, isConnected: Boolean, onConnect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(BgSecondary).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(device.name, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Row {
                Text(device.address, color = TextSecondary, fontSize = 13.sp)
                if (device.isPaired) {
                    Spacer(Modifier.width(8.dp))
                    Text("已配对", color = Success, fontSize = 12.sp)
                }
            }
        }
        Button(
            onClick = onConnect,
            enabled = !isConnected,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text("连接", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}
