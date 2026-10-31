import java.util.Properties

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
        // vc151（K60 安卓14 上向兼容）：19→34。协议/栈零改动，仅壳层适配：
        // 明文 HTTP 放行、前台服务类型+通知渠道、动态 receiver EXPORTED 旗标、
        // 运行时权限（RECORD_AUDIO/定位/POST_NOTIFICATIONS）。老车机（KK API19）零回归。
        targetSdk = 34
        // versionCode 唯一生效处在下方 vc 迭代注释区（随版本递增，出包时改那里）。
        // 2026-10-21 整顿：此处原有 `versionCode = 56` 为失效的重复赋值，被后者静默
        // 覆盖；出包时改错一处即打错版本，已移除。
        // 构建标识：T15 曾因 versionName 恒为 0.1-lite 而误跑旧包、结论作废。

        versionCode = 199
        val versionPatch = 31
        versionName = "1.2.$versionPatch.fe6su"
    }

    // 日志上云 token 脱敏（公开源码前置条件）：不再硬编码进源码，改由 local.properties
    // 注入（GITEE_LOG_TOKEN=...，该文件已被 .gitignore 排除，永不入仓）。
    // 未配置时为空串：Gitee 上传按钮自动降级为「生成文件后手动发送」路径。
    defaultConfig {
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField("String", "GITEE_LOG_TOKEN", "\"${localProps.getProperty("GITEE_LOG_TOKEN") ?: ""}\"")
        // vc77 定案：有线 NCM 守护恒关（vc76 guard-off 裁决通过，无 root 正式支持）。
        buildConfigField("boolean", "WIRED_NCM_GUARD_ENABLED", "false")
        // vc193：FE6 root 语音通路取证开关。**只在本诊断包为 true**；正式 dongle 包
        // 必须置 false（层 B/C 全部入口由它包住，避免给普通用户暴露 root 写操作）。
        buildConfigField("boolean", "FE6_ROOT_PROBE", "true")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // 外发测试签名：vc154（⑦ CI 化构建 task·签名固化）。
    // 环境变量优先（CI/正式发布密钥可覆盖）；缺省回落 Android SDK 标准 debug keystore
    // （~/.android/debug.keystore，alias=androiddebugkey，口令均为 android）——即
    // docs/BUILD.md 手动打包约定的固化：任何环境 ./gradlew ciRelease 无需记环境变量
    // 直接产出已签名 APK。debug 凭据为 SDK 通用值，无个人路径/机密入仓，开源分支同步
    // 安全；keystore 文件不存在且未配环境变量时保持未签名（纯源码环境语义不变）。
    val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
        ?: "${System.getProperty("user.home")}${File.separator}.android${File.separator}debug.keystore"
    val releaseKeystoreUsable = releaseKeystorePath.isNotBlank() && File(releaseKeystorePath).exists()

    signingConfigs {
        if (releaseKeystoreUsable) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        release {
            // vc198：按用户长期决策**关闭混淆与资源压缩**（此前多次强调「不做混淆、不做加密加固」）。
            // 关掉之后 dex 里能直接查到 Fe6Device / MicrophoneUplink 等原类名，排障与验收都不必
            // 再靠字符串字面量绕；代价是包体积变大（R8 之前砍掉的那部分会回来）。
            // ⚠️ proguard-rules.pro 仍然保留且 proguardFiles 不删：将来若重新开启，native keep 规则
            // （xcertplay / LegacyUsbHostNative / BouncyCastle / JmDNS）必须在位，否则 NoSuchMethodError。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseKeystoreUsable) {
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
        // vc200：API16 兼容门禁。minSdk=16 下任何「高版本才有的平台 API」都升为 error 级别
        // （只提高报告严重度，不阻断构建——abortOnError 仍为 false）。
        // 出包前核 `app/build/reports/lint-results-release.xml` 里 NewApi 计数应为 0。
        // 依据：工程文档/审查-20261031-API16兼容性全量审查.md
        error += "NewApi"
    }
}

dependencies {
    // 零 AndroidX / 零 Compose —— 轻量化红线（设计文档 §2.2）
    implementation(project(":shared"))
}

// CarPlay 身份资产「构建期外部注入」（2026-10-21 整顿）。
// 背景：identity.pk8 / certificate.p7b 曾被直接跟踪入 Git 并随源码同步到公开仓，导致
// MFi 身份私钥进入历史（谁 clone 到旧提交都能取出，且与现役密钥为同一把）。现改为：
//   · 源码树内的 app/src/main/assets/offline-mfi/ 由 .gitignore 排除，永不入仓；
//   · 构建时从外部目录注入：优先环境变量 DIPLAY_IDENTITY_DIR，缺省回落 ../auth/offline-mfi
//     （工程本级私有目录，同样不入库）；
//   · 白名单仅两个文件，缺失即报错、多余不予理会，避免误塞其它资产；
//   · 外部目录不存在时按「纯源码环境」继续构建（产出 APK 不带身份，代码仍可正常编译），
//     与 release 签名「keystore 缺省即未签名」的处理保持一致。
val runtimeIdentityDir = System.getenv("DIPLAY_IDENTITY_DIR")?.takeIf { it.isNotBlank() }
    ?: rootProject.file("../auth/offline-mfi").absolutePath
val runtimeIdentityTarget = file("src/main/assets/offline-mfi")
val runtimeIdentityFiles = listOf("identity.pk8", "certificate.p7b")

tasks.register("injectRuntimeIdentity") {
    group = "build"
    description = "Copy allowed CarPlay identity assets from an external dir into app assets (never committed)"
    val srcDir = File(runtimeIdentityDir)
    val dstDir = runtimeIdentityTarget
    doLast {
        if (!srcDir.isDirectory) {
            logger.warn(
                "未找到 CarPlay 身份资产目录 $srcDir —— 将产出不带身份的 APK（纯源码构建语义）。" +
                    "需打包请把 identity.pk8 与 certificate.p7b 放入该目录，或设置环境变量 DIPLAY_IDENTITY_DIR。"
            )
            return@doLast
        }
        // vc188：vc187 的守卫只判 isFile（文件是否存在），但 File.isFile 对
        // 0 字节文件同样返回 true ⇒ 空文件能过检并被 copyTo 拷进 assets，
        // 运行时 readBounded() 必然抛 "Local identity file is empty"。
        // 这正是 220020 的失效形态——守卫恰好漏掉了它本该拦住的那一种情况。
        val present = runtimeIdentityFiles.filter {
            File(srcDir, it).let { file -> file.isFile && file.length() > 0 }
        }
        if (present.size != runtimeIdentityFiles.size) {
            throw GradleException(
                "身份资产不完整或为空：$srcDir 中应有 $runtimeIdentityFiles（且非空），实得 $present"
            )
        }
        dstDir.mkdirs()
        present.forEach { name -> File(srcDir, name).copyTo(File(dstDir, name), overwrite = true) }
        logger.lifecycle("已注入 CarPlay 身份资产 $present（来源：$srcDir）")
    }
}

// vc190：外置 USB 蓝牙固件（Realtek 等 blob，linux-firmware 再分发许可）。
// 与 offline-mfi 同策略：源文件放私有目录 auth/usbbt_fw（被 .gitignore 排除，永不入公开仓），
// 构建期注入 app/src/main/assets/usbbt_fw（该目录同样 gitignore），缺失则告警纯源码构建。
val usbFirmwareDir = System.getenv("DIPLAY_USB_FIRMWARE_DIR")?.takeIf { it.isNotBlank() }
    ?: rootProject.file("../auth/usbbt_fw").absolutePath
val usbFirmwareTarget = file("src/main/assets/usbbt_fw")

tasks.register("injectUsbbtFirmware") {
    group = "build"
    description = "Copy external Realtek/Broadcom USB Bluetooth firmware into app assets (never committed)"
    val srcDir = File(usbFirmwareDir)
    val dstDir = usbFirmwareTarget
    doLast {
        if (!srcDir.isDirectory) {
            logger.warn(
                "未找到 USB 蓝牙固件目录 $srcDir —— 将产出不带固件的 APK（纯源码构建语义）。" +
                    "需支持 Realtek/Broadcom ROM 模式 dongle（如 CM390）请把固件放入该目录，" +
                    "或设置环境变量 DIPLAY_USB_FIRMWARE_DIR（来源见 FIRMWARE.md）。"
            )
            return@doLast
        }
        // auth/usbbt_fw 为本仓受控私有源，整体递归拷贝（含 rtl_bt/ + manifest.json + LICENCE + README）。
        dstDir.mkdirs()
        srcDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(srcDir).path
            File(dstDir, rel).parentFile?.mkdirs()
            f.copyTo(File(dstDir, rel), overwrite = true)
        }
        logger.lifecycle("已注入 USB 蓝牙固件（来源：$srcDir → $dstDir）")
    }
}

// vc193：root PCM 直采二进制（diplay-pcmcap，源码与产物同在 auth/tinycap，被 .gitignore
// 排除，永不入公开仓）。用途：绕开 AudioFlinger/AudioPolicy 由 root 直接开
// /dev/snd/pcmC?D?c，回答「车机物理麦克风到底能不能采到非零 PCM」——App 侧 AudioRecord
// 被 HAL/策略卡住时，这是唯一能分开「设备没音」与「框架不给音」的实验。
// 缺失时告警并产出不带该产物的 APK（纯源码构建语义）；层 C 的 R1 档届时落 SKIP。
val pcmCapDir = System.getenv("DIPLAY_PCMCAP_DIR")?.takeIf { it.isNotBlank() }
    ?: rootProject.file("../auth/tinycap/bin").absolutePath
val pcmCapTarget = file("src/main/assets/tinycap")

tasks.register("injectTinycap") {
    group = "build"
    description = "Copy external diplay-pcmcap binaries (root PCM capture) into app assets (never committed)"
    val srcDir = File(pcmCapDir)
    val dstDir = pcmCapTarget
    doLast {
        if (!srcDir.isDirectory) {
            logger.warn(
                "未找到 PCM 直采二进制目录 $srcDir —— 产出不带 R1 root 直采档的诊断包（layer C R1=SKIP）。" +
                    "需要该能力请先执行 auth/tinycap/build.sh，或设置 DIPLAY_PCMCAP_DIR。"
            )
            return@doLast
        }
        dstDir.mkdirs()
        srcDir.listFiles()?.filter { it.isFile }?.forEach { f ->
            f.copyTo(File(dstDir, f.name), overwrite = true)
        }
        logger.lifecycle("已注入 PCM 直采二进制（来源：$srcDir → $dstDir）")
    }
}

tasks.named("preBuild").configure {
    dependsOn("injectRuntimeIdentity", "injectUsbbtFirmware", "injectTinycap")
}

// vc154（⑦ CI 化构建 task）：一键「签名 release 构建 + APK 归档」。
// 用法：./gradlew ciRelease（无需任何环境变量，签名走上方固化缺省）。
// 产物复制到仓库 apks/DiPlay-<versionName>-cluster1-vc<versionCode>.apk（与既有
// 归档命名一致）；归档目录可用 DIPLAY_APK_ARCHIVE_DIR 覆盖（独立 clone 无上级 apks 时）。
tasks.register("ciRelease") {
    group = "build"
    description = "Signed release build + archive APK to repo apks/ (vc154 signing-solidified CI task)"
    dependsOn("assembleRelease")
    doLast {
        val versionName = android.defaultConfig.versionName as String
        val versionCode = android.defaultConfig.versionCode
        val output = file("build/outputs/apk/release/app-release.apk")
        if (!output.exists()) {
            throw GradleException("ciRelease：未找到 ${output.absolutePath}（assembleRelease 产物缺失）")
        }
        val archiveDir = System.getenv("DIPLAY_APK_ARCHIVE_DIR")
            ?: rootProject.file("../apks").absolutePath
        val target = File(archiveDir, "DiPlay-$versionName-cluster1-vc$versionCode.apk")
        target.parentFile.mkdirs()
        output.copyTo(target, overwrite = true)
        println("ciRelease：APK 已归档 ${target.absolutePath}")
    }
}
