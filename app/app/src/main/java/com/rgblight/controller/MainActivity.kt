package com.rgblight.controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rgblight.controller.ui.RgbApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = DarkColorScheme) {
                val vm: MainViewModel = viewModel()
                RgbApp(vm = vm, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6C5CE7),
    onPrimary = Color.White,
    surface = Color(0xFF0F0F14),
    onSurface = Color(0xFFF0F0F5),
    surfaceVariant = Color(0xFF1A1A23),
    onSurfaceVariant = Color(0xFF8E8E9A),
    error = Color(0xFFFF6B6B),
    background = Color(0xFF0F0F14),
)
