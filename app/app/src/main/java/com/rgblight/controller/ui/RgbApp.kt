package com.rgblight.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rgblight.controller.MainViewModel
import com.rgblight.controller.ui.theme.*

private data class BottomTab(
    val label: String,
    val icon: String  // Unicode symbol — no emoji, per design spec
)

private val tabs = listOf(
    BottomTab("色轮", "◎"),
    BottomTab("场景", "▦"),
    BottomTab("蓝牙", "◈"),
    BottomTab("设置", "⚙")
)

@Composable
fun RgbApp(vm: MainViewModel, modifier: Modifier = Modifier) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier,
        containerColor = BgPrimary,
        bottomBar = {
            NavigationBar(
                containerColor = BgSecondary,
                contentColor = TextPrimary,
                tonalElevation = 0.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            Text(
                                tab.icon,
                                fontSize = 20.sp,
                                color = if (selectedTab == index) Accent else TextDisabled
                            )
                        },
                        label = {
                            Text(
                                tab.label,
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Semibold else FontWeight.Normal,
                                color = if (selectedTab == index) Accent else TextDisabled
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Accent.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> ColorWheelPage(vm)
                1 -> ScenePage(vm)
                2 -> BluetoothPage(vm)
                3 -> SettingsPage()
            }
        }
    }
}
