package com.rgblight.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rgblight.controller.MainViewModel
import com.rgblight.controller.data.Scene
import com.rgblight.controller.ui.theme.*

private val modeNames = mapOf(0 to "静态色", 1 to "呼吸", 2 to "流水")

@Composable
fun ScenePage(vm: MainViewModel) {
    val scenes by vm.scenes.collectAsState()
    var showNewDialog by remember { mutableStateOf(false) }
    var newSceneName by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("场景库", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { showNewDialog = true }) {
                Text("+ 新建", color = Accent, fontWeight = FontWeight.Semibold, fontSize = 15.sp)
            }
        }

        Spacer(Modifier.height(4.dp))

        if (scenes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂无保存的场景\n调整颜色后点击「+ 新建」保存当前状态",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scenes) { scene -> SceneCard(scene, vm) }
            }
        }
    }

    // New scene dialog
    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            containerColor = BgSecondary,
            title = { Text("保存当前场景", color = TextPrimary, fontWeight = FontWeight.Semibold) },
            text = {
                OutlinedTextField(
                    value = newSceneName,
                    onValueChange = { newSceneName = it },
                    placeholder = {
                        Text("场景名称", color = TextDisabled)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Divider
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newSceneName.isNotBlank()) {
                            vm.saveScene(newSceneName.trim())
                            newSceneName = ""
                            showNewDialog = false
                            focusManager.clearFocus()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("保存", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun SceneCard(scene: Scene, vm: MainViewModel) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgSecondary)
            .clickable { vm.sendScene(scene) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Color preview square
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Color(
                        red = scene.r / 63f,
                        green = scene.g / 63f,
                        blue = scene.b / 63f,
                        alpha = scene.brightness / 100f
                    )
                )
        )
        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(scene.name, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                "${modeNames[scene.mode] ?: "静态色"} · 亮度${scene.brightness}%",
                color = TextSecondary,
                fontSize = 13.sp
            )
            Text(
                String.format("#%02X%02X%02X", scene.r * 4, scene.g * 4, scene.b * 4),
                color = TextDisabled,
                fontSize = 12.sp
            )
        }

        IconButton(onClick = { showDeleteConfirm = true }) {
            Text("🗑".let { "×" }, color = AccentWarm, fontSize = 18.sp)
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = BgSecondary,
            title = { Text("删除场景", color = TextPrimary) },
            text = { Text("确定删除「${scene.name}」？", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteScene(scene)
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentWarm),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("删除", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }
}
