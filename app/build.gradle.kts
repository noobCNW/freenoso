plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.xs.reader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xs.reader"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // sherpa-onnx 只发了 arm64-v8a / armeabi-v7a / x86_64 的 .so; 真机覆盖前两者就够,
        // 模拟器一般人用 arm64 镜像。把 x86 系列从 APK 中剔除, 避免无意义增大 APK。
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.media3.common.util.UnstableApi"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/NOTICE",
                "/META-INF/NOTICE.txt"
            )
        }
    }

    // matcha-icefall-zh-baker 预装资源(~100MB,16 个文件)。
    // 不允许 aapt 把 ONNX/字典文件压成 deflate, 否则:
    //  1) 首启拷贝时 AssetManager.open() 要现场解压, 大模型直接卡 10+ 秒;
    //  2) AAB / split APK 上传校验会因压缩文件 CRC 失败炸开;
    //  3) 节省安装包体积的意义不大: ONNX 已是接近随机的浮点权重, deflate 压缩率 < 5%。
    androidResources {
        noCompress += listOf("onnx", "fst", "utf8")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    implementation(libs.androidx.security.crypto)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.jsoup)
    implementation(libs.juniversalchardet)
    implementation(libs.epublib) {
        exclude(group = "org.slf4j")
        exclude(group = "xmlpull")
    }

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)

    // sherpa-onnx 离线神经 TTS 运行时 (AAR 含 onnxruntime + jni)。
    // 1.13.0 体积约 57MB,因带 4 个 ABI 的 onnxruntime .so;
    // 通过 abiFilters 已过滤掉 x86/x86_64,实际打包仅保留 arm64-v8a + armeabi-v7a。
    implementation(files("libs/sherpa-onnx-1.13.0.aar"))
}
