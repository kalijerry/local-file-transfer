plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 版本号规则：
// - 发版（推送 vX.Y.Z 标签）：APP_VERSION_NAME=X.Y.Z，versionCode = X*1000000 + Y*1000 + Z
// - 日常 CI / 本地构建：versionName = 0.0.<构建号>-dev，versionCode = 构建号
val releaseVersion: String? = System.getenv("APP_VERSION_NAME")?.takeIf { it.isNotBlank() }
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

fun versionCodeOf(version: String): Int {
    val parts = version.split(".").map { it.toInt() }
    require(parts.size == 3 && parts[1] < 1000 && parts[2] < 1000) { "版本号必须是 X.Y.Z：$version" }
    return parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
}

// 发布签名：配置了 RELEASE_KEYSTORE_FILE 等环境变量（来自 GitHub Secrets）时使用正式密钥，否则沿用仓库内的调试密钥
val releaseKeystore: String? = System.getenv("RELEASE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.transferhub.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.transferhub.app"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersion?.let(::versionCodeOf) ?: buildNumber
        versionName = releaseVersion ?: "0.0.$buildNumber-dev"
    }

    signingConfigs {
        // 仓库内固定的调试签名：保证每次 CI 构建出的 APK 签名一致，可以直接覆盖升级。
        // 正式发布时请改用存放在 GitHub Secrets 中的发布密钥。
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            } else {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")

    // 内置 HTTP 服务器
    implementation("io.ktor:ktor-server-core:2.3.13")
    implementation("io.ktor:ktor-server-cio:2.3.13")
    implementation("org.slf4j:slf4j-nop:2.0.16")

    // 二维码生成
    implementation("com.google.zxing:core:3.5.3")
}
