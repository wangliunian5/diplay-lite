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
        versionCode = 53
        // 构建标识：T15 曾因 versionName 恒为 0.1-lite 而误跑旧包、结论作废。

        // 版本迭代规则：1.0.x 系列止于 1.0.29，自 1.1.x 重新起排（每次出包 patch +1）。
        // 1.1.2：7100 端口 SO_REUSEADDR/显式关闭 + USBMUX 重置纳入重试 + NCM 桥死亡联动关
        // wiredCsm + 「Siri/语音提示走媒体音量」开关。
        // 1.1.2-test：bt_audio 排查探针包（/info 声明探针进 LiteLog + A2DP/HFP 状态广播 +
        // RTSP rx 入口打点），专供「carplay+蓝牙」外测取证。
        // 1.1.2-btaudio-test：bt_audio 试验包——移植上游 BluetoothAudioHandoff（A2DP 监听，
        // 仅 bt_audio 模式挂载）+ /info 声明变体（长按「蓝牙音频+CarPlay」切换 no_audio/
        // keep_formats），攻 iOS 16+ 零音频声明不建屏幕流。
        // 1.1.2-root-test：root 抢回试验包——claim NCM 失败且 su 可用时，sysfs unbind 内核
        // cdc_ncm + rmmod 后自动重试 claim 一次，攻「内核 CDC-NCM 抢占 NCM 数据接口」。
        // 1.1.3：蓝底图标 + 设置 UI 字号/小标题/弹窗确认重构 + 诊断弹窗与 USB 取证段
        //（usb-uevent/usb-devices/net-dev/ps-all/usb-holder）+ 8815 SDP 连续失败用户指引与
        // SDP 服务列表取证 + StandardCharsets→Charset.forName（API19 前车机 Lockdown 不再挂）
        // + 硬解渲染只渲最新帧/renderHold 恢复抑制（绿线+burst 节流）+ 开源声明弹窗 + 日志 token 轮换。
        versionName = "1.1.3"
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
            // 正式包：混淆 + 资源压缩开启（1.0.28-中继test 实验包曾临时关闭；
            // [BT-PROBE] 临时关闭已随 1.0.29 正式包恢复）。
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
