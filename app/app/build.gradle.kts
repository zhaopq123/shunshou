plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zpq.phoneagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zpq.phoneagent"
        // API 24 是硬地板：dispatchGesture 与 ACTION_SET_TEXT 都要 24+
        // （docs/COMPATIBILITY.md 的能力阶梯；低于 24 只读不写，本 App 直接拒绝安装）
        minSdk = 24
        targetSdk = 34
        versionCode = 15
        versionName = "1.3.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint { abortOnError = false }
}

// 不依赖 AndroidX / 第三方库：HTTP 用 ServerSocket，JSON 用 Android 内置 org.json。
// 原因：ThinkPad 上 Gradle 官方源超时、Maven 仅部分可达，依赖越少首次编译越不容易卡墙。
