plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.qianbi.writer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qianbi.writer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // 发布签名。密钥文件不进版本库（见 .gitignore），所以 clone 下来的人手上没有它 ——
    // 这时 release 自动回退到 debug 签名，保证「拉下来就能构建」；
    // 要出正式包，把自己的 keystore 放到项目根目录并改下面的口令即可。
    val keystoreFile = rootProject.file("release.keystore")
    val hasReleaseKey = keystoreFile.exists()

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = keystoreFile
                storePassword = "aigames2026"
                keyAlias = "customsystem"
                keyPassword = "aigames2026"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")   // SAF：支持用户自选的外部书架目录
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 纯 JVM 单元测试：验证 .book 打包加解密、书本结构、Web 协作服务端（不需要设备/模拟器）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
