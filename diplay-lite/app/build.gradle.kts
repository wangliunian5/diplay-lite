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
        // 1.1.5/vc76：①WiredNcmGuard 守护关闭裁决版（WIRED_NCM_GUARD_ENABLED=false，
        // TS7 无 root A/B 实验：仅 NTB16 重同步能否稳住会话，vc77 按裁决回 true/false）；
        // ②无线媒体断供看门狗（音 102/视频 110 双静默 20s → 拆栈重试，攻 iPhone 野蛮
        // 退出无 teardown 的僵尸会话）；③失败重试路径保留热点附着（形态 B：BT 中止
        // teardown 后 5/5 SDP 全灭的根因是热点被释放，iPhone 掉网重入网恶性循环）；
        // ④BYD-HUD bindService 指数退避（8815 每 250ms 风暴实测）。
        // 1.1.5/vc77：①WIRED_NCM_GUARD_ENABLED=false 定案——vc76 guard-off 裁决通过
        // （TS7 无守护健康 80+ 分钟零异常，内核绑定 6.3 全程未解绑），正式支持无 root
        // 运行，守护代码保留作未来 root 平台的开关项；②触摸/控制通道 TCP_NODELAY
        // （eventSocket accept 侧补齐，对齐上游 0.2.13 触摸修复，降低 touch2frame 延迟）。
        // 1.1.5/fe6t1(vc78)：FE-6 外测专用试验包——①超宽屏声明覆盖：1920x620（96:31，
        // MFi 官方列表外）导致 iOS 首连接受（图标拉伸）重连回退 16:9（黑边 409px/侧），
        // 改声明官方超宽 8:3（高 snap 宽*3/8），keep-aspect 后黑边收窄 ~133px/侧且
        // 重连分辨率预期稳定（仅纵横比宽于 8:3 的屏受影响）；②应用层 pcap 抓包（无 root）：
        // NcmUsbBridge 入/出站以太网帧经 PcapTap 环形落盘（3 文件×32MB），设置页开关，
        // 文件在 Android/data/<pkg>/files/pcap/，攻 wedge/黑边类数据面取证。
        // 1.1.5/fe6t2(vc79)：方控监听——FE-6「方控上/下一首无效」，MediaSession/logcat/广播
        // 三条通路零触发记录，dispatchKeyEvent 全量 KeyEvent 落日志（只观察不改分发），
        // 确认车机按键是否以窗口 KeyEvent 形态进 App 及键码/来源，再定映射修复。
        // 1.1.5/vc80(relayfix)：中继踢出转正——攻「iPhone 提前连到中继 WiFi 后 CarPlay 永远
        // 连不上」。根因：iPhone 已在目标网络时 0x5703 不触发 join 事件（no-op），12 次重发
        // 全废，First TCP 看门狗 30s+35s 宽限判死 → 重试死循环。修复：中继模式恒启用假凭证
        // 踢出（原 [RELAY-TEST] 实验分支，曾因看门狗 65s < 三阶段 134s 而废弃，00:28 日志
        // phase2 从未触达），真凭证重发 8 次（40s）仍 0 TCP → 假 0x5703 踢出当前 AP → 10s
        // 后真凭证恢复 → 强制新鲜 join → ~62s 前完成，全程在 65s 看门狗窗口内。
        // 1.1.5/vc81(wifilock)：中继链路抗抖动——C8815 实测无线会话视频 rx=6.8fps、音频输入
        // maxGap 389~934ms、queuedMs 180~1427ms 震荡（听感一卡一卡），为 WiFi 省电轮询突发
        // 断供特征，App 此前全程未持 WifiLock。中继入网成功即持 WIFI_MODE_FULL_HIGH_PERF
        // 锁（RelayClientManager 幂等获取，close 释放），失败重试 keepForReuse 下重新持锁。
        // 1.1.5/vc83(relaykick2)：攻「中继热点要点两次连接才能配上」（082530 日志 8 次点击
        // 定案）。三根因三修：①踢出后 10s 凭证真空期 iPhone 无凭证可回、用户在恢复前放弃
        // →恢复延迟 10s→1.5s，且恢复后重发预算恢复满额 12×5s；②踢出时机 8 次重发(40s)太晚
        // →2 次重发(~10s)即踢，踢出→回连全程压缩 35s；③车机 WiFi 关闭态中继发起秒失败
        // →RelayClientManager 自动 setWifiEnabled(true)（等 8s）。④First TCP 看门狗 30s→60s
        // （082530 tap2 实证热点直连慢入网 39s，30s 判死差 9s；总上限 60+35=95s）。
        // 1.1.5/fe6t3(vc84)：Siri 后无声恢复——FE-6 USB 有线会话（fe6t2/vc79 日志实证）iPhone
        // 进 Siri 拆媒体音频流（TEARDOWN type=100 ended=true）→ SETUP speechrecognition 上行
        // → Siri 结束后第二个 TEARDOWN，此后 iPhone 不再重发媒体音频 SETUP，音乐静音到用户
        // 手动断开（会话其余全健康，enhancedSiri 会话特有，TS7/8815 无复现）。AirPlaySession
        // 层检测「speechrecognition 音频流已现 + 音频流被 TEARDOWN 且会话内无音频流」→ 10s
        // 看门狗（期间任一音频 SETUP 即撤销）→ onSiriAudioStall 回调；wired 编排层 close 会话
        // （先补发 best-effort TEARDOWN，iPhone 当真结束）→ 既有 wired 自动重试链全量重建。
        // 1.1.5/e5t1(vc85)：EU5 通话收音修复/取证——三招打「上行无声」：①数字静默自愈
        // （核心）：采集 peak==0（真全零，DSP 静音路径特征；<300 近静音是环境噪音绝不触发）
        // 连续 5s → 采集线程内热换下一个未试音频源（沿 6→1→7 逐个排除，RTP 不断流），
        // 全源轮完仍全零 → 停轮换并落 QG DSP 嫌疑取证；②麦克风静音位自动恢复：上行启动
        // 前检测 AudioManager mute 位，为 true 自动解除并复检落证（App 层能修的静音路径）；
        // ③QG DSP 存在性探测（pm list packages qinggan，无 root）附在取证里。
        // 1.1.5/fe6t4(vc86 sirirestore2)：Siri 看门狗装弹判据修复——fe6t3 实测
        // （diplay-20261015-185536）看门狗零触发、测试者仍手动重连两次（Siri 后静音 117s）。
        // 根因：Siri speech TEARDOWN 时 101/default 控制音频流残存（本就反复 flap），
        // 「会话内无任何音频流」判据永不满足。改用 type→audioType 映射（activeAudioKinds）
        // 跟踪活跃音频流，判据改为「无 audioType=media 音频流」；到期收会话走 wired
        // 重试链全量重建，预期 Siri 后 ~15s 音乐自动恢复。
        // 1.1.5/fe6t4(vc86)：Siri 恢复看门狗判据修正——fe6t3(185536 日志) 实测看门狗零触发
        // （Siri speech TEARDOWN 时 101/default 控制流残存，其本就反复 flap，「无任何音频流」
        // 判据永不满足，用户仍手动两次点击），判据改为「无 audioType=media 音频流」。
        // 1.1.5/fe6t5(vc87)：FE-6 三问题修复/取证——①方控派发接通：fe6t3 实锤按键以窗口
        // KeyEvent 形态进 App（code=87/88 dev=-1 src=0，MediaSession/logcat/广播三通路零触发），
        // 学习绑定建得起但永不派发；dispatchKeyEvent 观察侧升级观察+派发（DOWN 命中学习绑定、
        // UP 命中标准媒体键，不改 super 分发）。②掉线取证：fe6t3 实锤一次掉线=用户点断开、
        // 一次根因=iPhone 关 event 连接（Siri 结束后整 60s）→ wedge 看门狗连锁误判拆桥 →
        // auto-retry 其实跑通全量重建并重发 0x4301，但 iPhone 无响应且用户 29s 手动打断；
        // closeWiredStack 全调用点 reason 落盘 + event 连接关闭区分对端 EOF/空闲时长。
        // ③收音无：fe6t3 日志 uplink started 后无任何 level window/tx 心跳=AudioRecord.read
        // 恒返 0 的 HAL 出数失败形态（凑不满整帧连 e5t1 静默自愈都不触发），补零读换源自愈。
        // 1.1.5/fe6t5(vc87)：FE-6 三项修复（fe6t3 视频+日志定案）。①方控无响应：按键以窗口
        // KeyEvent 形态进 App（code=87/88 dev=-1 src=0），MediaSession/logcat/广播三通路零触发；
        // 学习绑定经 onKeyDown 能建立但标准派发只有 MediaSession+logcat/广播两条路，窗口 KeyEvent
        // 永不派发 → dispatchKeyEvent 加观察+派发（DOWN 命中学习绑定 event=ACTION_DOWN、
        // UP 命中标准媒体键）。②掉线取证：closeWiredStack 全调用点 reason 落盘 + event 连接
        // 关闭区分对端 EOF/空闲时长。③收音无：fe6t3 日志 uplink started 后无任何 level window/tx
        // 心跳=AudioRecord.read 恒返 0 的 HAL 出数失败形态（凑不满整帧连 e5t1 静默自愈都不触发），
        // 补零读换源自愈。
        // 1.1.5/direct1-3(vc88-90)：客户端直连实验（已终止并回退）——车机 STA 加入 iPhone 个人
        // 热点拓扑三轮实测（不带组/带组/全套 0x5703+0x4301=中继同款）均被 iPhone 完全忽略
        // （tcpAccepted=0，60s 看门狗判死）。终案：iOS 硬限制——拒绝对自身个人热点的端点声明，
        // 不会在自己热点接口上发起 CarPlay 会话，App 层无解。实验资产归档：apks/DiPlay-1.1.5-direct*。
        // 1.1.6(vc91)：①车标页全品牌选择器（assets/car-logos 141 品牌：搜索过滤/中文名/列数
        // 自适应/点选即补方 256 存 CarIconStore+名称替换，素材源 FreeAuto-Car-1.2.4 解包精选+小米补绘）；
        // ②WiredNcmGuard 解绑严谨化（借鉴 legacy-ref）：解绑前读 driver/uevent 驱动名，仅放行
        // cdc_ncm/cdc_ether，usbfs/未知驱动拒绝并落盘取证——不再无差别解绑。
        // 1.1.6(vc92)：未配 SSID 报错文案明确化（fe6 102301 日志：服务自启走默认通道秒失败弹窗
        // 误导测试者）——「当前是车机热点模式，未配置SSID，无法连接」/中继同款；手动点击路径原有
        // 引导 toast 不变；0x4301 重建后忽略问题（fe6 185536 日志二次实锤，57s 死窗主因）另案处理。
        // 1.1.6-fe6t5(vc92)：enhancedSiri 根治实验（用户拍板无开关直接关）：/info extendedFeatures
        // 移除 "enhancedRequestCarUI"（增强式 Siri 请求车机 UI 的唯一声明位）。SETUP 请求里
        // iPhone 确实带 enhancedSiri，但 SETUP 响应各平台本就一致不回（TS7/8815 同），关闭点
        // 收敛到 /info extendedFeatures。判据：Siri 后媒体流是否自动重发 SETUP（不再触发
        // onSiriAudioStall/60s 重建）；风险：若会话被拒则恢复双元素声明。
        // 1.1.7(vc93)：①VPN 授权三段式（ECARX 235521 实锤 ROM 裁剪 vpndialogs 致 USB 点击
        // 闪退）：startActivityForResult catch + root appops ACTIVATE_VPN 免对话框 + 明确报错；
        // ②车机热点状态三证据源（235233 实锤 ECARX state=11 但 ap0 存活）：state/isWifiApEnabled/
        // AP 专用接口交叉验证，不设机型判断；③蓝牙开关双证据源+自动补开（同车实锤 adapter
        // 失灵）：adapter ∥ Settings.Secure bluetooth_on，双关时 enable+轮询 8s；
        // ④楔死重建全量复位兜底（TS7 083438 实锤 self-heal 短路后 iPhone 无视 0x4301）：
        // wedge/watchdog/usbmux-reset 重试置 wiredFullResetPending 走 0x52+setConfiguration
        // 全量复位路径 + 0x4301 建立期重发预算（2s×6）。B 组安全项（ADB 开关/PIN 门禁）缓行。
        // 1.1.8(vc94)：全库逻辑审查 12 项修复（A 组 9 机械 + B 组 3 行为）——
        // ①A1 0x4301 重发检查移出 recv-null 分支（心跳常驻形态下 vc93 预算逻辑零触发）；
        // ②A2 fullReset 消费时重置重枚举预算（wedge 多轮重建后 attempts 耗尽落 self-heal 短路）；
        // ③A3 蓝牙补开 elvis-throw 死代码修复（ensureBluetoothEnabled 非空 Boolean，throw 不可达）；
        // ④A4 AirPlayTcpServer 回退绑定成功后 active 复位（bindOnce 失败释放 active 置 false，
        //   回退路径 acceptLoop 首判即退，iPhone 连入无会话假活）；
        // ⑤A5 音频 rx 循环兜底 catch（下游 RuntimeException 线程静默死亡，对齐 ScreenStream）；
        // ⑥A6 NcmUsbBridge root 抢回重试前经工厂重新 openDevice（openOnce 失败已 close connection，
        //   复用必败，1.1.3 起 root 抢回整体失效）；
        // ⑦A7 中继热点 PSK 一致性校验（改密后 configuredNetworks 命中同名即复用，join 恒超时）；
        // ⑧A8 三处 su 执行加超时（MainActivity/WiredNcmGuard/Diagnostics，exitValue 轮询，
        //   API16 兼容不用 waitFor(timeout)，超时 destroy 按失败处理）；
        // ⑨A9 WiredVpnService 快速重连旧 TUN fd 覆盖泄漏（establish 前关存量 fd）；
        // ⑩B10 NTP offset 应用门控加 60s 老化（极小 RTT 霸占延迟窗口致 offset 冻结、晶振漂移无界累积）；
        // ⑪B11 Ipv6NcmBridge close 写 ff02::1 哑包唤醒阻塞 TUN read 的出站线程（僵尸线程随重连累积）；
        // ⑫B12 音频重排 distance==0 交付前清 held 同序号残帧（PT=86 注入与原包竞速滞留，
        //   firstKey 回拨基准致整段重复交付）。
        // 1.1.9(vc95)：①中继网关探测放宽 2s×2→3s×4/间隔1s（SM-T210 实锤手机热点数据面启动
        //   窗口期 isReachable 假阴性致 RELAY_UNREACHABLE，用户需手动重试；全程上限 ~15s 消除
        //   误报）；②日志抬头新增 ROM 版本行（Build.DISPLAY→Build.ID→Build.TIME 三层兜底，
        //   ROM 版本+车机型号辨识测试机防日志混淆）；③车标选择改 5×2 横向滑动栏内联直选
        //   （厂家预置行+「全部品牌」跳页合并，部分车机网格页无法预览清单用户裁定；BEI JING/
        //   Stellantis raw 独有置顶+assets 141 品牌，独立网格页 BrandAdapter 机制移除）。
        // 1.1.9(vc96)（FE-6 断链分析定案，全部 app 层）：①WaitingForIphone 打 USB 总线拓扑
        //   快照（首拍+每5拍，区分设备掉总线 vs pid 变化退出 CarPlay 模式）；②黑等超 10s
        //   UI 提示「请拔插 USB 线」（实测 94s 无反馈车主以为 App 死）；③no_audio 会话
        //   wedge fatal 阈值 6s→15s（无音频恒流垫底，画面静止断流属正常防误杀）；④root
        //   USB 取证探针 WiredUsbForensics（会话期 dmesg USB 线增量进 LiteLog + wedge 时
        //   sysfs 设备树快照，内核级回答「谁先动手」；无 root 静默关闭）。
        // vc97：①A1 重发门控换「无流帧」判据（streamFrames 独立计数，0x5001/0x5201
        //   心跳污染 forwardedFrames 致重发饿死+wedge 误判——TS7 46s 死窗根因链闭合）；
        //   ②wedgeSuspect 接通后 wedge 重建置位真正生效；③wedge 判别器（usbmux 控制面
        //   活=端点真楔死按变体阈值快拆 / 同停=iPhone 停发型宽限 20s）；⑤usbfx 内核
        //   时间戳水位去重（T3 全量重放治）+snapshot 去 tail 依赖（toybox 无管道）；
        //   ④蓝牙多源枚举 BondedDevicesResolver（FE-6 getBondedDevices 失灵族）随包。
        //   vc98（T3 当晚续）：KK(API<21) 无 setInterface——旧 runCatching 兜底在 T3 上
        //   必然 NoSuchMethodError→false，设备侧 NCM alt 恒 0，iPhone 数据面零包、60s
        //   看门狗必触发（协议栈 claim/MFi/0x4301 全通只差 iPhone 第一个包）。改发
        //   SET_INTERFACE 类请求（claim 后可发，无需 root），App 自闭环不依赖外部时序。
        //   vc99（T3 当晚续2）：claim 失败 root 抢回根治——unbind 后内核 67ms re-probe 绑回
        //   （dmesg 实锤），unbind 必败竞速。改先扫 sysfs 取 iPhone PID → cdc_ncm remove_id
        //   （本开机周期内核失明，重启自动复原）→ 再 unbind 踢绑定 → 重试 claim 必成。
        //   vc100（T3 定案）：SuperSU 2.82 su -c 长命令静默降权缺陷（含重定向/分号整体
        //   降为 shell，uid 短命令 10 连测全过 vs 同串 id=2000 实锤）——reclaim 改脚本
        //   文件模式：写 filesDir/ncm-reclaim.sh → su -c "sh <path>" 单短命令 → stdout 判定。
        //   vc101（T3 定案2，借鉴 legacy-ref usb_host_compat）：root reclaim 竞速根治——
        //   remove_id+unbind 后内核 67ms re-probe 绑回（22:09 会话 dmesg 实锤两轮 unregister
        //   →register），sysfs 路线在 T3 静态 ID 表下必败。改 usbfs 原子路径（免 root）：
        //   ①NCM claim 失败先走 USBDEVFS_DISCONNECT_CLAIM ioctl（内核锁下一步「断驱动+claim」，
        //   消除竞速窗口，legacy-ref forceClaim 同款）；②vc98 SET_INTERFACE 升级为
        //   USBDEVFS_SETINTERFACE ioctl 优先（内核 usb_set_interface 同步 ep 启用表，
        //   controlTransfer 只切设备侧不更新内核，bulk URB 可能提交失败），controlTransfer
        //   兜底。NDK libusb_host_compat.so（v7a/v8a，API21 编译 API19 可运行）随包，
        //   加载失败自动降级原路径。已知遗留：KK 重插线内核选错配置（无 setConfiguration
        //   API+快照陈旧）留 vc102（KitKatUsbDescriptors+native setConfiguration）。
        // vc103r（Legacy 对齐三改动，目标 T3/AC822X「入向零帧」）：
        //   ①SET_INTERFACE 改描述符驱动 alt + 5次×100ms 重试（cdc_ncm claim/SET 间抢绑竞速）
        //   ②NCM 复用 usbmux 会话同一 fd（内核 claim 位图按 fd 记账，双 fd 割裂=KK ENOENT 根因）
        //   ③SETCONFIGURATION(6) 后 GET_CONFIGURATION 回读验证（errno=0≠内核真切过去）。
        // vc164：版本号开始迭代——versionName 第三位随每次出包 +1（1.2.0 基线 = vc160）。
        // 每次出包只改 versionPatch 一个数字（+1）：vc164 → 1.2.1，vc165 → 1.2.2，依此 +1。
        // vc166（外置 USB 蓝牙 dongle 第一步）：版本标记 + 实验开关 + USB 设备探测显示
        //（UsbDongleProbe.kt 只枚举不打开；前提验证：T3 插 dongle 能否被系统枚举）。
        // vc167（第二步）：落 A+B 批——usbbt 开关接入 CarPlayController 建链缝 +
        // USB 传输层探活（枚举→openDevice→HCI Reset→读 BD_ADDR）；UsbDongleProbe
        // 枚举能力收进 shared/.../usbbt/UsbBtSession 并删除。
        val versionPatch = 5
        versionName = "1.2.$versionPatch.dongle"
        // vc143：USBMUX 握手超时 60s→20s（快速进入 full-reset 重试）+ 重试轮次显示
        // （复位提示此前 8s 被覆盖、静默期观感=卡死）。17:40 会话实证 vc142 自愈链路正常。
        // vc144：NCM 同步读成功后 settle 1ms 再 arm 下一次传输——vc143-snap-2008 定案
        // MUSB 在短包结束→重新 arm 交界处吃掉下一 NTB 第一个 512B 包（remnant 1060 实锤）。
        // vc145：renderState 状态未变即短路（C8815 实测 dalvik 上每 500ms tick 主线程
        // ~11ms+持续分配，是未连接期按钮延迟的 app 侧放大器；首启快检对话框首次
        // inflate 触发 GC 风暴 12 连发/Skipped 57 帧为一次性事件，不在本修内）。
        // vc146（T3 实测）：悬浮球初始位按 displayMetrics.heightPixels(600) 计算，
        // wm overscan 车机可视仅 500 → 球落到 y≈526 可视区外被黑条吞掉；布局完成后
        // 按 root 实际高度 post 校正一次。
        // vc149（USB 拔插彻底清理）：注册 USB_DEVICE_DETACHED 监听，拔线即按「用户断开」
        // 等价清理（LiteSessionService.onDestroy 全拆 controller 栈/mediaSink/VPN）；
        // 重插 ATTACHED 经 onNewIntent 触发 maybeAutoConnect 全新建链，根治热重连脏会话
        // （shown=0/触摸延迟 190ms）。此前拔线 app 零感知，旧地基叠会话。
        // vc150（T3 来电假死）：静默渲染看门狗——rx 满帧但 shown=0 持续 3s（来电/通话/Siri
        // 流重建只发 P 帧不补参数集），自动 flush + 请求关键帧自愈，10s 限频。
        // vc151（K60 安卓14 上向兼容）：targetSdk 19→34 壳层适配（见上方 targetSdk 注释）。
        // vc152（K60 微信语音挂断后会话不自愈）：对端优雅结束会话后自动 0x4301 邀请重投。
        // vc153（K60 有线连接 0x4301 后 iPhone 静默假死）：有线启动看门狗 60s→15s 判死 +
        // 自动重试 2 次封顶 + 第 2 轮前 0x52 mode request/重枚举升级；MainActivity 有线
        // 连接前无线互斥（拆无线栈延迟 2.5s 再走有线）。
        // vc154（待办收口三件套）：④有线切换改拆栈完成回调驱动（替代固定 2.5s，8s 兜底）；
        // ⑤VPN 授权失败引导页（原两处失败终点只弹 toast，升级为重试授权/改无线引导弹窗）；
        // ⑦CI 化构建 task（签名固化缺省 debug keystore + ciRelease 一键构建归档）。
        // vc155（K60 实测 UI 修复）：主面板横屏右列 MATCH_PARENT→WRAP_CONTENT——断开连接
        // 按钮 GONE 左卡变矮后右列被压至同高，设置键底部溢出圆角被裁；改右列反撑行高根治。
        // vc156（上游 0.2.21 待移植双项）：①音频抗抖动=Opus 编码选择层（native→平台回退→
        // 失败容错）+ MediaPacketLagGuard 积压丢帧 + AudioTrack 预缓冲水位（audioPrebuffer 开关）；
        // ②Wi-Fi Direct fallback=热点建链失败时 P2P GO 兜底（wifiP2pFallback 开关，默认关）。
        // vc157（Wi-Fi Direct 升格为一级模式）：承载方式改为「车机热点 / Wi-Fi Direct」二选一，
        // 选 P2P 时直接建 GO，不再依赖车机热点开关状态（绕过 SoftAP 判死逻辑）；
        // 同时帧率由 limit30Fps 布尔升级为四档（25/30/40/60，默认 60），
        // 修复页面重建后指示灯变灰/断开按钮消失（render 末尾清空渲染哨兵）。
        // vc158（UI 调整）：①竖屏弹窗宽度收敛（AlertDialog 按物理横屏宽布局会被裁，
        // 22 处构建点统一走 showFitted()）；②Wi-Fi Direct 由「体验优化里的承载方式开关」
        // 改为「自动连接方式」第四项（车机热点/中继热点/Wi-Fi Direct/USB连接），
        // 承载方式单选删除，旧 wifi_p2p_mode=true 迁移为 activeChannel=p2p；
        // ③帧率四档从体验优化组移入「画面设置」，小节标题「画面帧率」。
        // vc159（设置页闪退修复）：vc158 帧率组迁移残留——rgFps 在体验优化组旧位置
        // 忘删（第二次 addView 同一实例）→ 点设置必崩 IllegalStateException；
        // logcat -b crash 实锤，删除旧挂载行（SettingsPage buildContent 单条 column，横竖屏同崩）。
        // vc160（弹窗错位修复）：vc158 的 fitWidthToScreen 用 displayMetrics 短边做锚点，
        // 冷启动横→竖旋转后 dm 陈旧 → 弹窗仍右溢（K60 实测）；改 decorView.width 优先
        // + setGravity(CENTER)。横竖屏弹窗行为改真实冷重启（启动级属性「下次连接生效」
        // 不成立），音视频/分辨率/超采样文案对齐「下次连接生效」。
        // vc161（弹窗错位根治）：放弃 AlertDialog 独立 Window——Manifest 是
        // screenOrientation=landscape + configChanges 自处理，运行时强制竖屏，
        // Dialog 由 WMS 按 display 坐标系居中于横屏 frame → 竖屏下必右溢，
        // 改宽度/锚点都无效（vc158/vc160 两次实测均未解决）。
        // 新增 AppDialog.kt：**应用内覆盖层**（同 errorCardView 做法），22 处调用点
        // 由 AlertDialog.Builder 机械替换；fitWidthToScreen/showFitted 随之删除。
        // 追加两条竖屏修复：①弹窗内容区改「包内容、限高 70%」（短内容不再留白）
        // ②自动连接方式/画面帧率两组单选改 WrapRadioGroup 可折行容器
        //   （竖屏四项一行放不下；首版折行挂在 onSizeChanged 导致整组空白，改 onMeasure）。
        // vc162：横竖屏确认后的重启方式由「Launcher intent + killProcess」改为
        // activity.recreate()——避免 startActivity 未送达就被杀（点了没起来），
        // 且走完整生命周期让 onDestroy 优雅拆栈（原实现不执行 onDestroy）。
        // vc163：**悬浮球全局化**——原先是两套球（home 可拖拽 46dp + 子页各自
        // attachPageBall 建的 34dp 不可拖），视觉/交互/位置都不统一且切页闪烁。
        // 改为 MainActivity.render() 里挂一颗全局球，所有页面共用、可拖拽、
        // 位置跨页面保留；点击语义集中一处（home=收起/唤出面板，子页=逐级返回）；
        // 删除 UiKit.attachPageBall 与三个子页的调用点。
        versionCode = 170
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
            // 正式包：混淆 + 资源压缩开启（1.0.28-中继test 实验包曾临时关闭；
            // [BT-PROBE] 临时关闭已随 1.0.29 正式包恢复）。
            isMinifyEnabled = true
            isShrinkResources = true
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
        val present = runtimeIdentityFiles.filter { File(srcDir, it).isFile }
        if (present.size != runtimeIdentityFiles.size) {
            throw GradleException(
                "身份资产不完整：$srcDir 中应有 $runtimeIdentityFiles，实得 $present"
            )
        }
        dstDir.mkdirs()
        present.forEach { name -> File(srcDir, name).copyTo(File(dstDir, name), overwrite = true) }
        logger.lifecycle("已注入 CarPlay 身份资产 $present（来源：$srcDir）")
    }
}

tasks.named("preBuild").configure {
    dependsOn("injectRuntimeIdentity")
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
