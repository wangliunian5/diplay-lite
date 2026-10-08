# M6k14 UI 方案：三通道深色版（回归零 XML 程序化风格）

- 日期：2026-10-06
- 依据：用户 5 张新渲染图（竖屏主页 / 设置页 / 中继热点配置 / 车机热点配置 / 横屏主页）+ 指令「按 M6k13 之前的风格提供 UI 方案」
- 前作：M6k13 白卡片 XML 版（已交付并同步权威树）——本方案将其**表现层回退**，**行为层全部保留**

## 0. 总体判定

「M6k13 之前的风格」= 零 XML 纪律 + 程序化构建 + 深色气质 + 全屏 Surface + 浮层面板 + 悬浮球。
新渲染图的信息架构（三通道/配置页分离/设置编排）**采纳**，视觉底色本就是深色，与旧风格同源。

因此方案核心一句话：**用 M4k 时代的程序化手段，重建新图的深蓝三通道布局；M6k13 的行为层（旋转策略/合并落盘/自动连接/BootReceiver/IconPainter）原样保留。**

## 1. 识图与信息架构

```
MainActivity（三通道选择器，竖屏卡片纵排 / 横屏三列并排）
├── 头部：Diplay Lite + versionName + 状态点(绿/黄/红) + 齿轮→SettingsActivity
├── 卡片1 车机热点   [toggle] 说明 + 配置|连接|断开
├── 卡片2 中继热点   [toggle] 说明 + 配置|连接|断开
└── 卡片3 USB连接    [toggle] 说明 + 连接|断开
     （连接成功 → 浮层收起 + Surface 铺满 + 悬浮球，M4k 行为保留）

HotspotConfigActivity（新增，一个 Activity 双模式 mode=car|relay）
├── 配置参数：热点名称 / 热点密码（两列下划线输入）
├── 保存（描边）| 系统热点跳转（灰实心）
├── 蓝牙信息：已选择 <设备名>；蓝牙选择（蓝实心）| 系统蓝牙跳转（灰实心）
└── 热点信道：IPV6首选信道（输入）| IPV4首选信道（Spinner 下拉）

SettingsActivity（按图2 重排，无卡片、分组+分割线）
├── 分辨率自定义 [10%-150%] 应用
├── 导航音频触发音量降低 [30%-100%] 应用
├── 横竖屏切换：强制横屏/强制竖屏/自动旋转（RadioButton）
├── 音视频链路选择：全CarPlay/蓝牙音频+CarPlay
├── 车机音频通道选择：媒体声道/导航声道
├── 体验优化：开机自启/启动后自动连接/强制软解（CheckBox）
├── 自定义返回图标 → 进入（PlaceholderActivity）
├── 方向盘按键学习 → 进入（PlaceholderActivity）
├── 运行日志 → 预览（LogPreviewDialog）| 导出（exportLog 保留）
└── 关于：版本 <真实 versionName>（M15 纪律）
```

## 2. 与 M6k13 交付版的差异对照

| 维度 | M6k13（已交付） | M6k14（本方案） |
|---|---|---|
| 实现路线 | XML 布局 + res/ 20 文件 | **零 XML 程序化**（回退表现层） |
| 主页结构 | 热点表单 + 日志卡片 | 三通道卡片（车机/中继/USB）互斥 toggle |
| 凭据编辑 | 主页 ssid/密码 | 拆到 HotspotConfigActivity（车机/中继各一态） |
| 日志 | 主页日志卡片 | 设置页「运行日志」预览+导出 |
| 信道 | 单 channel（设置页高级） | IPV6/IPV4 双栈首选信道（配置页） |
| 图标 | IconPainter Canvas（零资源） | 保留（符合零 XML，齿轮/返回/蓝牙标继续用） |
| 行为层 | 旋转策略/合并落盘/自动连接/BootReceiver | **原样保留** |

## 3. 风格规格（程序化常量，全部代码内定义，零资源）

```
配色（从渲染图取色）：
  BG        #101B30   深海军蓝背景（竖屏）/ 横屏同一底色
  CARD      #1A2A47   卡片面（圆角 14dp，竖屏用；横屏无卡片底，竖分割线 #22304A）
  PRIMARY   #2E6BE6   主蓝（连接/保存/蓝牙选择/toggle 开）
  OUTLINE   #3D7BFF   描边按钮描边+文字（配置/保存/进入/预览/导出）
  GRAYBTN   #5A6472   灰实心（断开/系统跳转）
  TEXT      #FFFFFF   主文字（标题 22sp 粗、卡片题 17sp 粗、按钮 15sp）
  SUB       #97A0B3   说明/次要（13sp）
  LINE      #22304A   分割线
  GREEN     #34C759   状态点-连接成功（黄=连接中 / 红=失败，沿用旧 renderState 三色）
  输入：下划线式（图3/4，1dp LINE + 白字）与填充式（图2 数值框，#1E2C4A 圆角 8dp）两种
```

组件实现要点（API16 安全）：
- 卡片/胶囊按钮/填充输入框：GradientDrawable（旧版 dip() 体系内新增 helper：card()、pillOutline()、pillSolid()、lineInput()、fillInput()）
- **ToggleView 自绘**（新增 ~60 行纯代码）：底槽圆角矩形（开=PRIMARY / 关=#39435A）+ 白色圆形滑块，点击切换并回调；互斥逻辑在 MainActivity（开一个 → 关其余）
- RadioButton/CheckBox：原生控件 + 程序化染色（旧版先例），选中色 PRIMARY
- 图标：IconPainter（已有，纯 Canvas）——齿轮/返回/蓝牙小标/↗ 角标

## 4. 页面级设计

### 4.1 MainActivity（重构 UI 层，逻辑保留）
- 竖屏：ScrollView → 头部 + 3 张卡片纵排（边距 16dp，卡间距 12dp）
- 横屏（车机）：不滚动，头部行 + 三列等宽（weight=1），列间 1dp 竖线；按钮行压缩为单行三枚
- 每卡片：标题行（题 17sp 粗 + ToggleView 44x26dp）→ 说明（13sp SUB，1-2 行）→ 按钮行（配置=描边 / 连接=蓝实心 / 断开=灰实心；USB 卡只有 连接/断开）
- toggle 语义 = **通道选择**（互斥）：activeChannel ∈ {car, relay, usb}；连接按钮按激活通道走对应链路
- 连接成功：浮层收起 + Surface 铺满 + 悬浮球拖拽（M4k/M6k5 行为原样保留，attachBallDrag/performClick 不动）
- 状态：状态点三色 + 「连接成功/连接中…/已断开/失败」文案（renderState M6k5 节流保留）
- 图5 的 ⊗（中继/USB 卡右上）与「←」按钮语义不明确：本方案按"横屏按钮组与竖屏一致（配置/连接/断开）"统一，⊗ 不实现（待用户澄清）

### 4.2 HotspotConfigActivity（新增）
- Intent extra `mode`: "car"（车机热点配置=图4）| "relay"（中继热点配置=图3）
- 车机态读写 LiteConfig.ssid/password；中继态读写新增 relaySsid/relayPassword
- 蓝牙区：已选设备名（bluetoothDeviceName 缓存）+ 蓝牙选择（showBluetoothDeviceDialog 从 MainActivity 迁来/复用）+ 系统蓝牙跳转（openBluetoothSettings 降级逻辑复用）
- 信道区：ipv6Channel（EditText 数字，0=自动）/ ipv4Channel（Spinner：自动/1/6/9/11/13）
- 系统热点跳转：openHotspotSettings 三级降级复用
- 保存 = copy-merge 落盘（M6k13 纪律沿用）

### 4.3 SettingsActivity（重排）
- 按图2 顺序编排；无卡片，分组标题 + 分割线
- 数值行：左标题 + 右填充输入框 + 应用按钮（applyPercent 钳位逻辑复用）
- 移除：蓝牙/信道表单（已迁配置页）；新增：日志预览按钮
- 其余（三组单选、三复选、两入口、关于版本）逻辑不变，仅重排+程序化

### 4.4 日志预览
- LogPreviewDialog：AlertDialog + ScrollView + monospace TextView，读 LiteLog 内存环形缓冲尾部（exportLog 文件逻辑保留）

## 5. 配置模型变更（LiteConfig）

新增（save/load 同步扩展，copy-merge 语义不变）：
```kotlin
val activeChannel: String = "car",     // car | relay | usb（toggle 互斥状态）
val relaySsid: String = "",
val relayPassword: String = "",
val ipv6Channel: Int = 0,              // 0 = 自动
val ipv4Channel: Int = 0,              // 0 = 自动
```
保留全部现有字段（orientationMode/avPathMode/audioChannel/bootAutostart/autoConnect/…）。

## 6. 工程实施清单

| 操作 | 文件 |
|---|---|
| 删除 | res/ 全部 20 文件 + Manifest 的 DiPlayTheme 引用（回退零 XML；待决策点①确认） |
| 重构 UI 层 | MainActivity（三通道程序化）、SettingsActivity（图2 编排程序化） |
| 新增 | HotspotConfigActivity、ToggleView、StyleHelpers（card/pill/input helper） |
| 保留不动 | LiteState/LiteSessionService/LiteVideoBridge/LiteLog/LiteApp、IconPainter、PlaceholderActivity、BootReceiver、全部连接/触摸/悬浮球/导出逻辑 |
| 扩展 | LiteConfig（5 新键）、Manifest（注册 HotspotConfigActivity，主题行移除） |
| 横竖屏 | 程序化双布局（onConfigurationChanged 空闲态重建逻辑保留，重建时按方向重走 buildXxx()） |

## 7. 决策点（2026-10-06 已拍板）

1. **res/ 处置** ✅：**停用保留**——res/ 全部 20 文件移出资源目录（diplay-lite/attic/m6k13-res/），不参与编译；Manifest 移除 DiPlayTheme 引用。随时可切回白卡片风格。
2. **分辨率上限** ✅：**10%-150%**（按图）；**>100 应用时弹一次性确认**「放大=超采样，弱机卡顿自负」，确认后才落盘生效。
3. **主页日志** ✅：**严格按图移除**主页日志区，日志统一进设置页「运行日志 → 预览/导出」。
4. **无底层通道**（默认）：中继/USB 当前无链路实现，UI 先行，连接按钮 toast『该通道待接入』，接线归 GLM。

## 7.5 图例澄清结论（2026-10-06 用户批注+附图，全部已澄清）

1. **图5 的 ⊗** = toggle 关闭态的渲染噪声，横竖屏统一用 ToggleView，无独立 ⊗ 控件。
2. **USB 列按钮** = 连接/断开（豆包幻觉），横竖屏一致：USB 卡只有 连接/断开 两按钮（无配置，USB 无凭据）。
3. **toggle 语义（附图红字）**：toggle = **「默认自动连接方式」三选一互斥**（车机热点默认开）；切换时其余自动关闭并落盘 activeChannel；绿点「连接成功」= 实时连接状态，与 toggle 无耦合；配合设置页「启动后自动连接」实现开机/启动自动走激活通道。
4. **中继热点名称/密码** = **随身WiFi 的 SSID/密码**（手机与车机同连随身WiFi）；底层连接编排依赖 GLM 的中继热点方案。
5. **IPV6/IPV4 首选信道**：**两个配置页各一份，独立使用** → 配置模型为 4 个字段：carIpv6Channel/carIpv4Channel/relayIpv6Channel/relayIpv4Channel（0=自动）。
6. **USB 底层**：GLM 已有方案，UI 先行交付，出 UI 即整合（连接按钮在整合前 toast 提示待接入）。
7. **设置页数值框**：**回显当前值**；「10% - 150%」「30% - 100%」作为允许范围的 hint 文案。


## 8. GLM 接线清单（新增部分）

| 项 | 说明 |
|---|---|
| activeChannel | LiteSessionService 编排按通道分发：car=现有 MANUAL；relay=无线同网段模式（shared 有 CarPlayBonjour/WirelessHostAddress 零件）；usb=有线链路（全新） |
| ipv4/ipv6Channel | 热点建网首选信道（原 B3 难点扩展为双栈） |
| 方向盘按键学习 | 占位页（远期：AirPlayHid 反向注入） |
| 自定义返回图标 | 占位页（远期） |
| avPathMode/audioChannel | M6k-UI 交接文档既有待办，继续有效 |

## 9. 真机风险

1. 分辨率 >100% 在 C8815（Cortex-A5 无 NEON）上大概率不可用——确认弹窗兜底
2. 主页移除日志后排查动线变深（T51 类问题需两跳）
3. ToggleView/三列横屏为新增自绘件，低端机需验证无掉帧（纯 GradientDrawable+View，风险低）
4. 中继/USB 按钮在底层就绪前是"占位可点"，需 toast 明示，避免用户误认为故障
