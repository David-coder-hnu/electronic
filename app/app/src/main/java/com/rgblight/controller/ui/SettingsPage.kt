package com.rgblight.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rgblight.controller.MainViewModel
import com.rgblight.controller.ui.theme.*

@Composable
fun SettingsPage(vm: MainViewModel) {
    val autoReconnect by vm.autoReconnect.collectAsState()
    val sendConfirm by vm.sendConfirm.collectAsState()
    val breathPeriod by vm.breathPeriod.collectAsState()
    val chaseSpeed by vm.chaseSpeed.collectAsState()
    val maxBrightness by vm.maxBrightness.collectAsState()
    val isConnected by vm.isConnected.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text("设置", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))

        // ── Bluetooth settings ──
        SectionHeader("蓝牙设置")
        SettingsCard {
            SettingsRow("波特率", "115200") {}
            SettingsDivider()
            SettingsSwitch("自动重连", autoReconnect) { vm.setAutoReconnect(it) }
            SettingsDivider()
            SettingsSwitch("发送确认回显", sendConfirm) { vm.setSendConfirm(it) }
        }

        Spacer(Modifier.height(20.dp))

        // ── Effect parameters ──
        SectionHeader("灯效设置")
        SettingsCard {
            SettingsSliderRow(
                label = "呼吸周期",
                valueText = "${String.format("%.1f", breathPeriod)}s",
                progress = breathPeriod / 5f,
                enabled = isConnected
            ) { v -> vm.setBreathPeriod((v * 5f).coerceIn(1f, 5f)) }
            SettingsDivider()
            SettingsSliderRow(
                label = "流水速度",
                valueText = "${String.format("%.1f", chaseSpeed)}s",
                progress = chaseSpeed / 5f,
                enabled = isConnected
            ) { v -> vm.setChaseSpeed((v * 5f).coerceIn(0.5f, 5f)) }
            SettingsDivider()
            SettingsSliderRow(
                label = "最大亮度",
                valueText = "$maxBrightness%",
                progress = maxBrightness / 100f,
                enabled = isConnected
            ) { v -> vm.setMaxBrightness((v * 100).toInt().coerceIn(10, 100)) }
        }

        if (!isConnected) {
            Spacer(Modifier.height(8.dp))
            Text(
                "连接设备后可调整灯效参数",
                color = TextDisabled,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── About ──
        SectionHeader("关于")
        SettingsCard {
            SettingsInfoRow("RGB 蓝牙灯控 v1.0", "湖南大学 计算机学院 C301")
        }

        Spacer(Modifier.height(40.dp))
    }
}

// ── Private composables ──

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        color = Accent,
        fontSize = 14.sp,
        fontWeight = FontWeight.Semibold,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgSecondary)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        content = content
    )
}

@Composable
private fun SettingsRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary, fontSize = 15.sp)
        Text(value, color = TextSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary, fontSize = 15.sp)
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = BgTertiary
            )
        )
    }
}

@Composable
private fun SettingsSliderRow(
    label: String,
    valueText: String,
    progress: Float,
    enabled: Boolean,
    onValue: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = if (enabled) TextPrimary else TextDisabled, fontSize = 15.sp)
            Text(valueText, color = if (enabled) TextSecondary else TextDisabled, fontSize = 14.sp)
        }
        Slider(
            value = progress,
            onValueChange = onValue,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = BgTertiary,
                disabledThumbColor = TextDisabled,
                disabledActiveTrackColor = TextDisabled
            )
        )
    }
}

@Composable
private fun SettingsInfoRow(label: String, subtitle: String) {
    Column(modifier = Modifier.padding(vertical = 14.dp)) {
        Text(label, color = TextPrimary, fontSize = 15.sp)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = Divider, thickness = 0.5.dp)
}
