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
        versionCode = 56
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
        // 1.1.3(vc54)：修复 drainOutput 忙死循环（TRY_AGAIN 未无条件退出，解码线程卡死致全黑屏，
        // 8815 010252 定案）+ 设置页复选框字号统一 13f（实机反馈）。
        // 1.1.3(vc56)：运动噪块修复——MAX_FRAME_AGE 350ms→1100ms 消 900ms radio gap 引发的
        // flush churn + API<21 fps 协商压 30（Adreno 203 档峰值负载静默纠错噪块）。
        // 1.1.3(vc57)：系统诊断重做（中文检测报告）——首启快检（新装自动触发可跳过）+ 设置页
        // 复测逐项检测（ROOT/ADB/蓝牙 A2DP sink 探测/Wi-Fi 热点实测含恢复原状/USB 静态+动态
        // 人工步骤）+ 连接建议矩阵 + 「蓝牙音频+CarPlay」改操作指引弹窗（底层恒走全量 CarPlay）
        // + 主页面 hero 文案删除与右列对齐；取证数据只写日志不上屏。
        // 1.1.3(vc58)：诊断过程弹窗按钮被挤出可视区修复——ScrollView 加 weight=1，
        // 「下一步/跳过」恒定钉在弹窗底部（8815 横屏 540px 实测）。
        // 1.1.3(vc59)：分辨率设定落盘丢键修复——LiteConfig.save 漏写 video_resolution_mode/
        // manual_resolution_w/h，「手动设定分辨率」确认重启后单选弹回、输入行不显示。
        // 1.1.3(vc60)：分辨率交互改版（点选→右方应用键→重启生效，手动输入行恒显示）
        // vc61：FE-6 楔死自愈跳过重复选配（消除权限竞态误报）+ 诊断取证加 dmesg 采集
        // 1.1.4(vc62)：EU5 电话无收音修复（telephony 源序 6→1→7 避免源 7 毒化 HAL +
        // 上行启动失败延时重试 800ms/2s + 全败矩阵明细/音频系统快照落盘）+
        // wired 建立期看门狗 8s→15s（EU5 iPhone 起流可慢至 8~16s，8s 杀了两轮）。
        // 1.1.4(vc63)：TS7 mazda2 修复——①0x52 response=0x6（已配置拒绝重枚举）不再死等，
        // 直接开数据面；②多 NCM 组优选内核未绑定组 + 失败轮换（绕开 cdc_ncm 抢占致
        // NTB16 打碎，无 root 方案）。
        // 1.1.4(vc64)：无线 First TCP 超时自动打断残留会话（B 方案，TS7 170921 实测）——
        // 超时时拆蓝牙引导链并强制 ≥8s 全断窗口重启（热点不保留），模拟「断开→重连」
        // 强制 iOS 清残留 CarPlay 会话（此前保持热点的 RESTART 两代 30s 双超时）。
        // 1.1.4(vc65)：无线连接提速（10-12 TS7 Manual/Relay 双样本日志对比 0.2.6 结论）——
        // ①BT 单台直选：唯一 bonded iPhone 跳过 HFP/A2DP profile proxy 查询（省 ~8s）；
        // ②First TCP 看门狗 30s 后加 35s 宽限复查，窗口内 TCP 进入即放行（iPhone 实测
        // 入网 25~35s，旧值恰好杀在入网途中）；③重试/看门狗失败路径保留热点附着
        // （keepHotspotForReuse，iPhone 不掉网、下一轮秒连，对齐 0.2.6 热点常开模式）；
        // ④Bonjour 注销异步化（goodbye 限时 300ms，解除 JmDNS close 7~8s 阻塞）。
        // 1.1.4(vc66)：Bonjour start() 整体后台化——TS7 204308 实测 JmDNS
        // create+registerService 同步阻塞 7.5s/次（真凶是它而非 BT 探针，BT 快路径
        // vc65 已生效）。mDNS 广播非连接关键链路（0x5703 直驱），失败本就非致命降级；
        // 后台完成后代次过期自查 close，bonjour 字段引用比较防误伤新一代实例。
        // 实测 vc65 已达 Manual 15.4s / Relay 17.3s（vc64 为 104~107s），vc66 预期 ~8s。
        // 1.1.4(vc67)：AirPlay 监听端口回退——coocaa BEP10A（Android14）215128 实测车机自带
        // 投屏服务常驻监听 7100（EADDRINUSE，inode 随服务重启变化，无 root 不可清）。三层
        // 策略：①首选端口重试（自占用竞态自愈）②扫 /proc/net/tcp(6) LISTEN 黑名单后从
        // 7101..7110 直选空闲端口③bind(port=0) 内核随机端口兜底；实际端口注入 0x5703
        // airPlayPort + Bonjour TXT（iPhone 侧不写死端口），失败诊断升级为全 LISTEN 表。
        // 1.1.5/vc70：TS7 端点差分——NCM 选组反转为优先 3/4（OUT=0x06，带 interrupt EP），
        // A/B 测 MUSB EP 0x07 TX 静默丢弃嫌疑。vc70 实锤：iPhone 首次真实连入。
        // 1.1.5/vc71：建立看门狗 30s→60s（iPhone mDNS 收敛 ~30s 压线误杀）。vc71 实测画面
        // 已出但 MUSB 满流丢包致 NTB16 错位、一次校验失败即拆桥。
        // 1.1.5/vc72：NTB16 失步重同步（丢错位前缀对齐下一签名，风暴上限 64）。
        // 1.1.5/vc73：风暴计数窗口化（30s 无错位重置，窗口 256）——静态画面期错位
        // ~1-2 次/秒，vc72 累计 64 上限 2.5 分钟自拆（20:26:18 实测）。
        // 1.1.5/vc74：内置 cdc_ncm 解绑守护（WiredNcmGuard，2s 扫配置 6 接口，su 写
        // unbind，无 root 降级）——替代电脑侧外置循环，脱离电脑可独立工作。
        // 1.1.5/vc75：守护收窄为只解绑 6.3——解绑已 claim 的 6.4 会触发 alt 复位打死
        // bulk-out（20:56 实测两窗口全超时），外置守护成功行为就是只动 6.3。
        versionName = "1.1.5"
        versionCode = 75
    }

    // 日志上云 token 脱敏（公开源码前置条件）：不再硬编码进源码，改由 local.properties
    // 注入（GITEE_LOG_TOKEN=...，该文件已被 .gitignore 排除，永不入仓）。
    // 未配置时为空串：Gitee 上传按钮自动降级为「生成文件后手动发送」路径。
    defaultConfig {
        val localProps = java.util.Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField("String", "GITEE_LOG_TOKEN", "\"${localProps.getProperty("GITEE_LOG_TOKEN") ?: ""}\"")
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
