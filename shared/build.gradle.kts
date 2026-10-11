plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk = 34

    defaultConfig {
        minSdk = 16
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // 协议栈第三方依赖（minapi21 分支 shared 同款，纯 Java，可跑 Dalvik）
    // M1 阶段拷入裁剪后的 shared 源码（清单见 超低版本-轻量版设计方案.md §2.1）
    // API16 适配：3.5.4+ 使用 Java 8 的 Map.getOrDefault，API16 上 NoSuchMethodError
    // （实测 10-02 20:00 ServiceInfoImpl.checkQualifiedNameMap 崩溃）。3.5.1 为 Maven
    // Central 上最后干净的 Java 6 字节码版（已验证 class major=50、无 Java 8 API
    // 引用；3.4.x 未发 Central）。勿升。
    api("org.jmdns:jmdns:3.5.1")
    // jdk15on 1.70：Ed25519/X25519/HKDF/ChaCha20Poly1305 齐备，且 R8 4.0.52 可正常 dex 化
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
}
