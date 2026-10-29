plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.nfctool.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nfctool.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"
        vectorDrawables { useSupportLibrary = true }

        // 只保留 64 位 ARM。现代设备（Android 6+）全是 arm64，
        // 保留 32 位与 x86 会让 APK 体积翻倍。
        // ⚠️ 这也顺带排除了「装到了不匹配 ABI 的包」这类安装失败。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // 开启 R8 混淆与资源裁剪。
            //
            // 为什么 debug 也要开：本应用的 debug 包有 19MB，
            // 通过下载 / 文件管理器 / IM 传输时容易被截断损坏，
            // 导致安装器报出与真实原因无关的错误。
            // 裁剪后能压到数 MB，传输成功率与安装速度都明显改善。
            //
            // ⚠️ 保留 `isDebuggable = true`（默认值），否则无法连调试器。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 显式启用 v1+v2+v3 全签名。
            //
            // 背景：部分国产 ROM（MIUI / HyperOS 等）的安装器对签名的校验
            // 比 AOSP 严格，只签 v2 时会在安装阶段报 "packageInfo is null"。
            // v1 兼容 Android 7 以下的设备，v2/v3 覆盖 Android 7+，
            // 三者齐开是兼容性最好的选择（v1 只在 minSdk 24 下才有意义，
            // 但开着无害）。
            signingConfig = signingConfigs.getByName("debug")
        }

        /**
         * 对照构建类型：**不混淆、不裁资源**，只靠 ABI 过滤减体积。
         *
         * 用途：安装出问题时用来区分「是 R8 裁剪导致的」还是「其他原因」——
         * 若这个能装而 debug 不能，问题在混淆；若两个都不能，问题在别处。
         * 体积约 19MB（因 icons.extended 未被裁剪）。
         *
         * 构建命令：./gradlew assembleNoShrink
         */
        create("noShrink") {
            initWith(getByName("debug"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("debug")
            // 独立包名，可与正式版共存，方便对比测试
            applicationIdSuffix = ".noshrink"
            versionNameSuffix = "-noshrink"
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // 单元测试只验证纯字节编解码逻辑，不触碰真实 Android 运行时
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    // ⚠️ icons.extended 含数千个图标矢量，本项目只用到十来个。
    // 保留 implementation（release 也要能编译），靠 R8 裁掉未引用的。
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)

    // ⚠️ androidx.ui.tooling 是 Android Studio 的 Live Preview / Layout Inspector
    // 用的，真机上完全用不到，只在 debug 构建引入。
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
