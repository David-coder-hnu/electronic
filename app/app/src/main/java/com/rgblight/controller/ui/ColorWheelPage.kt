package com.rgblight.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
fun ColorWheelPage(vm: MainViewModel) {
    val hsv by vm.hsv.collectAsState()
    val rgb by vm.rgb.collectAsState()
    val mode by vm.mode.collectAsState()
    val brightness by vm.brightness.collectAsState()
    val isConnected by vm.isConnected.collectAsState()
    val sendFeedback by vm.sendFeedback.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Top bar ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "RGB 灯控",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            // Bluetooth status dot
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) Success else AccentWarm)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isConnected) "已连接" else "未连接",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Color wheel ──
        HsvColorWheel(
            hue = hsv.hue,
            saturation = hsv.saturation,
            onColorPicked = { h, s -> vm.setHsv(h, s, hsv.value) },
            diameter = 260f
        )

        Spacer(Modifier.height(16.dp))

        // ── Current color bar + HEX ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(BgSecondary)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(rgb.r, rgb.g, rgb.b))
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = String.format(
                    "#%02X%02X%02X",
                    (rgb.r * 255).toInt(),
                    (rgb.g * 255).toInt(),
                    (rgb.b * 255).toInt()
                ),
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Semibold
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Brightness slider ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("亮度", color = TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            Slider(
                value = brightness.toFloat() / 100f,
                onValueChange = { vm.setBrightness((it * 100).toInt()) },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = BgTertiary
                )
            )
            Text(
                "${brightness}%",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.width(40.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Mode selector chips ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ModeChip("静态色", mode == 0) { vm.setMode(0) }
            ModeChip("呼吸", mode == 1) { vm.setMode(1) }
            ModeChip("流水", mode == 2) { vm.setMode(2) }
        }

        Spacer(Modifier.height(20.dp))

        // ── Send button ──
        Button(
            onClick = { vm.sendColor() },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (sendFeedback) Success else Accent
            ),
            enabled = isConnected
        ) {
            Text(
                if (sendFeedback) "已发送 ✓" else "发送颜色",
                fontSize = 17.sp,
                fontWeight = FontWeight.Semibold,
                color = Color.White
            )
        }

        if (!isConnected) {
            Spacer(Modifier.height(6.dp))
            Text("请先连接蓝牙设备", color = AccentWarm, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = 1.5.dp,
                color = if (selected) Accent else Divider,
                shape = RoundedCornerShape(10.dp)
            )
            .background(if (selected) Accent.copy(alpha = 0.12f) else BgSecondary)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Accent else TextSecondary,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Semibold else FontWeight.Normal
        )
    }
}
