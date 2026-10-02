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
        versionCode = 1
        versionName = "0.1-lite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        abortOnError = false
        // M1（A4）：app lint 全量扫描 shared 依赖源码，NewApi 兜底。
        // 此前未开启时 shared 里的 API 21+ 调用（如 AudioAttributes）完全不在扫描范围。
        checkDependencies = true
    }
}

dependencies {
    // 零 AndroidX / 零 Compose —— 轻量化红线（设计文档 §2.2）
    implementation(project(":shared"))
}
