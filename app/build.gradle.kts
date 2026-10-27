import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { load(it) }
}

fun localSecret(name: String): String = localProperties.getProperty(name, "")
fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.weiguang.drcontroller"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.weiguang.drcontroller"
        minSdk = 26
        targetSdk = 36
        versionCode = 3830
        versionName = "3.7.9.40"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ===== API 凭据（统一收敛到 BuildConfig，源码中不再明文） =====
        // 更换 Key 时只需修改此处；APK 仍会内嵌这些值，请定期到控制台轮换。
        // 2026-09-24 轮换：DeepSeek 与百度 Secret Key 已更新
        buildConfigField("String", "DEEPSEEK_API_KEY", buildConfigString(localSecret("DEEPSEEK_API_KEY")))
        buildConfigField("String", "BAIDU_API_KEY", buildConfigString(localSecret("BAIDU_API_KEY")))
        buildConfigField("String", "BAIDU_SECRET_KEY", buildConfigString(localSecret("BAIDU_SECRET_KEY")))
        buildConfigField("String", "BAIDU_APP_ID", buildConfigString(localSecret("BAIDU_APP_ID")))

        // WiFi X-Ray v2 帧 HMAC-SHA256 预共享密钥。迷你主机端必须配置相同值。
        // 该密钥用于防止同一 WiFi 内的伪造帧，不代替服务端密钥管理。
        buildConfigField("String", "WIFI_XRAY_SHARED_KEY", buildConfigString(localSecret("WIFI_XRAY_SHARED_KEY")))
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        jniLibs {
            // HCUSBSDK/JNA is loaded from a DexClassLoader and requires real .so files.
            useLegacyPackaging = true
        }
    }
}

dependencies {

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // AI 助手
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")

    // 百度语音唤醒 SDK
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    val cameraxVersion = "1.6.1"

    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
}
