plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shilapi.diplay.lite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shilapi.diplay.lite"
        minSdk = 16
        targetSdk = 19
        versionCode = 43
        // 构建标识：T15 曾因 versionName 恒为 0.1-lite 而误跑旧包、结论作废。

        // 版本迭代规则（1.0.x 起）：每次出包 patch +1（1.0.1、1.0.2…）。
        versionName = "1.0.28"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // 外发测试签名：沿用 docs/BUILD.md 的环境变量约定。
    // 只在显式提供 ANDROID_KEYSTORE_PATH 时才创建签名配置；未提供时 release 保持未签名
    // （与主工程「源码/CI 构建无签名」语义一致，避免把密钥路径/口令写死进仓库）。
    val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")

    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (!releaseKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        abortOnError = false
        // M6k24：release 构建跳过 lintVital（targetSdk=19 会触发 ExpiredTargetSdkVersion fatal）
        checkReleaseBuilds = false
        // M1（A4）：app lint 全量扫描 shared 依赖源码，NewApi 兜底。
        // 此前未开启时 shared 里的 API 21+ 调用（如 AudioAttributes）完全不在扫描范围。
        checkDependencies = true
    }
}

dependencies {
    // 零 AndroidX / 零 Compose —— 轻量化红线（设计文档 §2.2）
    implementation(project(":shared"))
}
