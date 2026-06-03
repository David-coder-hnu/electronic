plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rgblight.controller"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rgblight.controller"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.5" }

    kotlinOptions { jvmTarget = "17" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.01.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // WCH BLE SDK — CH9143 virtual serial port
    // Download from: https://www.wch.cn/downloads/BleUartAndroid_ZIP.html
    // Place the .aar in app/libs/ and uncomment:
    // implementation(files("libs/BleUartLibrary-release.aar"))

    debugImplementation("androidx.compose.ui:ui-tooling")
}
