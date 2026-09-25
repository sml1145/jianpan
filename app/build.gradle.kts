plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mengting.ime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mengting.ime"
        minSdk = 24
        targetSdk = 34
        versionCode = 15
        versionName = "1.1.4"
        // Real devices only need arm dual architectures; emulator self-check uses debug builds with x86_64
        ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // keep x86_64 for emulator self-check
            ndk.abiFilters.clear()
            ndk.abiFilters += listOf("x86_64", "arm64-v8a")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.create("release") {
                storeFile = file("../keystore/mengting.jks")
                storePassword = "mengting123"
                keyAlias = "mengting"
                keyPassword = "mengting123"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        // sherpa-onnx AAR 与 onnxruntime-android 都带 libonnxruntime.so，pickFirst 保留一份。
        // 两者已对齐到 ORT 1.27.0（sherpa-onnx 1.13.4 内置 VERS_1.27.0，官方 onnxruntime-android
        // 1.27.0 也是 VERS_1.27.0），两个 JNI 桥引用同一符号版本，故保留任一份都能同时满足，
        // 不存在 pickFirst 顺序问题（已用 ELF 符号解析确认两库都导出全部 3 个 Ort* 符号）。
        jniLibs.pickFirsts += listOf(
            "lib/arm64-v8a/libonnxruntime.so",
            "lib/armeabi-v7a/libonnxruntime.so",
            "lib/x86/libonnxruntime.so",
            "lib/x86_64/libonnxruntime.so"
        )
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.4.aar"))
    // 手写识别改为本地 ONNX 推理（模型运行时从 ModelScope 拉取，见 HandwritingModelStore）。
    // 原 MLKit digital-ink 只支持动态下载、不支持随包内置，无 GMS 设备完全不可用，已移除。
    //
    // 语音(sherpa)与手写(官方 ORT Java)都带一个同名 libonnxruntime.so，只能 pickFirst 保留一份。
    // 实测（ELF 符号版本解析）证明：ORT 的 patch 版本各自带独立符号版本节点，
    // 1.28.0 的 libonnxruntime4j_jni 需要 OrtGetApiBase@VERS_1.28.0，而 sherpa-onnx 1.13.8
    // 内置的是 VERS_1.28.2，两者不兼容 → dlopen "cannot locate symbol OrtGetApiBase"（插桩测试已复现）。
    // 故把两个消费者对齐到同一 VERS：sherpa-onnx 1.13.4 内置 ORT 1.27.0，官方取 1.27.0，
    // 两个 JNI 桥都引用 OrtGetApiBase@VERS_1.27.0，共用一份 libonnxruntime.so 即可。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.2")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 插桩测试：在真机/模拟器进程内验证 ORT JNI 与 sherpa-onnx 内置 native 库的符号兼容性，
    // 这是手写改造唯一无法在桌面侧验证的风险点（桌面用的是独立 ORT，不含 pickFirst 冲突）。
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}


