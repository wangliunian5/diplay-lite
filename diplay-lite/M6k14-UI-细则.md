# M6k14 UI 组件细则 —— GLM 对接手册

- 日期：2026-10-06
- 定位：《M6k14 UI 方案》的落地实施细则。M6k14 全部代码已交付并构建通过（本地树 + DiPlay-4.1 权威树联编）。
- 用途：GLM 在权威树上做**中继/USB 链路整合、双栈信道消费、activeChannel 分发**时，按「页面 → 组件 → 行为 → 配置字段 → 接线点」逐项对照。
- 代码位置：`app/src/main/java/com/shilapi/diplay/lite/`（MainActivity / HotspotConfigActivity / SettingsActivity / PlaceholderActivity / UiKit / IconPainter / LiteConfig / LiteState）

---

## 0. 全局约定（所有页面共用）

### 0.1 零 XML 纪律
- 无 res/（已移 `attic/m6k13-res/` 停用）、无 R 引用、无 findViewById；全部视图在各 Activity 的 `buildRoot()` 中程序化创建并接线。
- 主题 = 系统 `@android:style/Theme.DeviceDefault.NoActionBar`，label 硬编码 "DiPlay Lite"（Manifest 无资源引用）。
- 旋转不重建 Activity（Manifest 全量 configChanges）；**空闲态**旋转走 `onConfigurationChanged` 重走 `buildRoot()`（程序化等效 XML 双目录）；**ACTIVE/CONNECTING 拒绝重建**（换 Surface = 解码器重配 + 画面冻结）。

### 0.2 色板（`Ui` object 常量，代码即文档）
| 常量 | 值 | 用途 |
|---|---|---|
| BG | #101B30 | 全局深海军蓝背景 |
| CARD | #1A2A47 | 竖屏通道卡面（圆角 14dp） |
| PRIMARY | #2E6BE6 | 主蓝：连接/保存/蓝牙选择/应用按钮/toggle 开/选中 radio·checkbox |
| OUTLINE | #3D7BFF | 描边按钮描边+文字：配置/进入/预览/导出/数值框应用 |
| GRAYBTN | #5A6472 | 灰实心：断开/系统跳转 |
| TEXT | #FFFFFF | 主文字 |
| SUB | #97A0B3 | 说明/次要文字/未选中描边 |
| LINE | #22304A | 分割线/下划线输入框底线/横屏竖分隔 |
| INPUT_FILL | #1E2C4A | 设置页数值框填充 |
| TRACK_OFF | #39435A | toggle 关底槽 |
| GREEN | #34C759 | 状态点-连接成功 |

### 0.3 按钮风格码（各页 `pillButton`）
| 码 | 样式 | 用途 |
|---|---|---|
| 0 | 描边蓝（Ui.pillOutline + OUTLINE 字） | 配置 / 保存 / 进入 |
| 1 | 实心蓝（Ui.pillSolid PRIMARY + 白字） | 连接 / 蓝牙选择 / 数值行应用；支持图标角标 |
| 2 | 实心灰（Ui.pillSolid GRAYBTN + 白字） | 断开 / 系统热点跳转 / 系统蓝牙跳转 |

按钮高度：主页 40dp / 配置页 42dp / 设置页 38dp。`setAllCaps(false)` 统一。

### 0.4 自绘控件协议
- **ToggleView**（UiKit.kt，44×26dp）：底槽圆角矩形（开=PRIMARY / 关=TRACK_OFF）+ 白色圆滑块。`onCheckedChange` **仅用户点击触发**；`setCheckedSilent()` 程序化同步不触发回调（互斥同步专用）。
- **Ui.radio / Ui.checkbox**：GradientDrawable 圆圈/方框，选中=PRIMARY 实心，未选=SUB 描边。⚠️ 自绘 drawable 不感知 checked 状态变化 —— **每次选中态变化必须重设 drawable**（设置页 `refreshRadioDrawables(group)` / `refreshAllDrawables()`，checkbox 在自己的监听里重设）。
- **IconPainter**（Canvas 现画，零资源）：`gear` 齿轮（设置入口）/ `back` ←（返回）/ `arrows` ↔（悬浮球）/ `upRight` ↗（系统跳转角标）/ `bluetooth` ᛒ（蓝牙选择角标）。
- 图标为独立 Drawable 实例，视图重建时须重取，不做缓存。

### 0.5 状态通道（UI ← 服务）
- `LiteState.phase ∈ {IDLE, CONNECTING, ACTIVE, FAILED}` + `LiteState.statusText`（服务侧写入）。
- 主页 500ms 轮询 `renderState()`；`LiteSessionService.current` 判服务存在。

---

## 1. 主页 MainActivity（三通道选择器）

### 1.1 视图分层（`buildRoot`，FrameLayout 三层）
1. **SurfaceView 铺满**：CarPlay 画面（`LiteVideoBridge.updateSurface`）+ 触摸转发（`forwardTouch` → `service.sendTouch(CarPlayTouchMapper…)`，未连接返回 false 不消费）。
2. **通道面板**：竖屏 = ScrollView + 头部 + 三卡纵排（CARD 圆角卡，边距 16dp 间距 12dp）；横屏 = 头部横跨 + 三列等宽（weight=1）+ 1dp LINE 竖分割线，**无卡片底**（`carded=false`）。
3. **悬浮球**：46dp 半透明白圆（#66FFFFFF）+ arrows 图标，TOP|END 定位可拖拽（`attachBallDrag`，位移 >6dp 判拖拽否则算点击）；点击 = 面板显隐切换；**面板收起后的唯一唤回入口**；面板展开时 alpha 0.5、收起时 1。

### 1.2 头部（`buildHeader`）
| 组件 | 行为 |
|---|---|
| 标题 "Diplay Lite" 22sp 粗 + 版本号 12sp SUB | 只读（真实 versionName） |
| 状态点 10dp 圆 | IDLE=灰 / CONNECTING=黄(240,200,60) / ACTIVE=绿 GREEN / FAILED=红(240,80,80) |
| 状态文字 14sp | `LiteState.statusText` 直显 |
| 齿轮 22dp | → SettingsActivity |

### 1.3 通道卡（`channelCard(key, title, desc, hasConfig, carded)`，三张同构）
| 卡 | key | hasConfig | 特有按钮 |
|---|---|---|---|
| 车机热点 | car | ✓ | 配置/连接/断开 |
| 中继热点 | relay | ✓ | 配置/连接/断开 |
| USB连接 | usb | ✗（无凭据页） | 连接/断开 |

| 组件 | 功能 | 字段/动作 |
|---|---|---|
| 标题 17sp 粗 | 静态 | — |
| **ToggleView（卡右上）** | **「默认自动连接方式」三选一互斥** | 点开 = `activateChannel(key)`：落盘 `activeChannel` + 其余卡静默关闭；**点关弹回**（必须恒有一个激活通道）；与连接状态无耦合；`loadToggles()` 按 config.activeChannel 回显 |
| 说明 13sp SUB | 静态文案 | — |
| 配置（描边） | → `HotspotConfigActivity.intent(mode=key)` | 仅 car/relay 有 |
| 连接（实心蓝） | `connect(key)` 按通道分发 | 见 1.4 |
| 断开（灰） | 三卡同效 | `stopService(LiteSessionService)` |

### 1.4 `connect(channel)` 分发 —— **GLM 替换点**
| 通道 | 现状（已实现） | GLM 期望 |
|---|---|---|
| car | ssid 空 → toast 引导配置；`videoKeyOf(videoScalePercent, limit30Fps)` ≠ `appliedVideoKey` → `confirmVideoChange`（断开+AlarmManager 重启）；否则 `startSession` → LiteSessionService（MANUAL 链路） | 既有链路，不动 |
| relay | relaySsid 空 → toast 引导配置；否则 **toast「中继通道待接入」+ 日志**（MainActivity.kt `connect()` else 分支前） | 替换 toast 为无线同网段编排（shared 已有 CarPlayBonjour/WirelessHostAddress 零件） |
| usb | **toast「USB 通道待整合」+ 日志** | 替换 toast 为有线链路（GLM 方案） |

### 1.5 自动连接（`maybeAutoConnect`，onResume 触发）
条件全部满足才发起：进程生命周期一次（`autoConnectTried`）+ `phase==IDLE` + 服务未运行 + `config.autoConnect`（设置页开关）+ **`activeChannel=="car"`** + ssid 非空 + videoKey 未变更（变更则记日志跳过）。
⚠️ GLM 就绪 relay/usb 后需把这里的通道白名单放开。

### 1.6 状态联动
`renderState()`：ACTIVE 时面板自动收起（全屏看画面 + 悬浮球），断开自动展开。连接编排最终以 `startService(LiteSessionService)` 触发，状态经 LiteState 回流。

---

## 2. 热点配置页 HotspotConfigActivity（一页双模式）

### 2.1 入口与模式
静态工厂 `HotspotConfigActivity.intent(activity, mode)`；`mode` ∈ `MODE_CAR` / `MODE_RELAY`；页内标题随模式切换「车机热点配置」/「中继热点配置」。

### 2.2 组件清单（`buildRoot`，ScrollView 纵排）
| 区块 | 组件 | 行为 | 落盘字段 |
|---|---|---|---|
| 头部 | back ← | `finish()` | — |
| | 标题 20sp 粗 | 按模式切换文案 | — |
| | 齿轮 | → SettingsActivity | — |
| 配置参数 | 名称输入（下划线式 `Ui.lineInput`，hint「请输入热点名称」） | trim 后保存 | car→`ssid` / relay→`relaySsid`（**随身WiFi 的 SSID**） |
| | 密码输入（下划线式，hint「请输入密码」） | 不 trim | car→`password` / relay→`relayPassword` |
| | 保存（描边0） | `saveFromForm()` + toast「已保存」 | 见 2.4 |
| | 系统热点跳转（灰2 + ↗） | `openHotspotSettings()` 三级降级：TetherSettings 组件 → 隐式 TETHER_SETTINGS → ACTION_WIRELESS_SETTINGS → toast 兜底 | — |
| 蓝牙信息 | 摘要 TextView | 「未选择（自动匹配已配对 iPhone）」/「已选择 <名> <MAC>」 | `bluetoothAddress` + bt_name 缓存（**全局一份，两模式共用**） |
| | 蓝牙选择（实心蓝1 + ᛒ 角标） | `showBluetoothDeviceDialog`：已配对设备列表（名字+MAC）+「手动输入地址…」+「清除选择（恢复自动）」；`saveBluetoothPick` 成对写地址与名字 | `bluetoothAddress` |
| | 系统蓝牙跳转（灰2 + ↗） | ACTION_BLUETOOTH_SETTINGS → ACTION_SETTINGS 兜底 | — |
| 热点信道选择 | IPV6首选信道（数字输入，hint「自动」，宽 120dp） | 空/非法/0 = 自动 | car→`carIpv6Channel` / relay→`relayIpv6Channel` |
| | IPV4首选信道（Spinner，宽 120dp） | 六项映射：自动/1/6/9/11/13 → 0/1/6/9/11/13 | car→`carIpv4Channel` / relay→`relayIpv4Channel` |

### 2.3 落盘规则（`saveFromForm`，copy-merge 纪律）
- car 态 `load().copy(ssid, password, carIpv6Channel, carIpv4Channel)`；relay 态 `load().copy(relaySsid, relayPassword, relayIpv6Channel, relayIpv4Channel)`。
- **两态互不触碰对方字段**；onPause 兜底再存一次。
- ⚠️ 旧单信道字段 `channel`（B3 难点遗留）UI 已不写入——GLM 接双栈时决定弃用或做映射。

### 2.4 GLM 消费点
- **双栈首选信道 4 字段**（0=自动）：热点建网时的首选信道，car/relay 两套独立使用。
- **中继凭据**：`relaySsid/relayPassword` = 随身WiFi 凭据（手机与车机同连该 WiFi）→ 中继链路编排输入。

---

## 3. 设置页 SettingsActivity（图2 编排，无卡片、分组+分割线）

### 3.1 组件清单（`buildRoot`，ScrollView 纵排，编排顺序即下表）
| 组件 | 视觉 | 行为 | 字段 |
|---|---|---|---|
| 头部 back + 「设置」 | ← 22dp | `finish()` | — |
| 分辨率自定义 | 数值行（标题 16sp ｜ 填充框 110dp ｜ 应用钮实心蓝） | hint=「10% - 150%」（允许范围），text=当前值回显；「应用」→ `applyScale`：非数字 toast；钳位 10–150；**>100 弹「放大超采样确认」**（取消=回显旧值），确认后落盘 + toast | `videoScalePercent`（常量 MIN/MAX_SCALE_PERCENT=10/150） |
| 导航音频触发音量降低 | 数值行 | hint=「30% - 100%」；`applyDuck` 钳位（100=不压低）+ toast | `navDuckPercent` |
| 横竖屏切换 | RadioGroup **竖排三选**（强制横屏/强制竖屏/自动旋转） | 选中即落盘 + **立即 `applyOrientationMode`**（本页立刻转向） | `orientationMode` land/portrait/auto |
| 音视频链路选择 | RadioGroup 横排二选（全CarPlay / 蓝牙音频+CarPlay） | 选中即落盘 | `avPathMode` full/bt_audio |
| 车机音频通道选择 | RadioGroup 横排二选（媒体声道/导航声道） | 选中即落盘 | `audioChannel` media/nav |
| 体验优化 | 5 checkbox 竖排（图只画 3，30帧/扬声器为既有功能保留）：开机自启 / 启动后自动连接 / 强制软解 / 限制30帧 / 通话声音走扬声器 | 勾选即落盘 | `bootAutostart` / `autoConnect` / `softwareVideoDecoder` / `limit30Fps` / `speakerForCalls` |
| 功能入口 | 「自定义返回图标」「方向盘按键学习」+ 进入钮（描边0） | → PlaceholderActivity(EXTRA_TITLE)，占位 | — |
| 运行日志 | 标题 + 预览/导出（小描边钮 38dp） | 预览 = AlertDialog（monospace 内存缓冲尾部 + neutral「清空」）；导出 = 时间戳新文件（避 MTP 缓存） | — |
| 关于 | 「关于」+ 版本 13sp SUB | 真实 versionName | — |

### 3.2 即改即存模型（persistFromForm）
- 所有 radio/checkbox 监听 → `persistFromForm()`；`isLoading` 守卫防半加载写盘；onPause 兜底。
- `persistFromForm` = `load().copy(本页 10 字段)`，**不触碰**凭据/双栈信道/activeChannel/bluetoothAddress/appliedVideoKey。
- 细节：数值框的当前文本也会在 persistFromForm 时被钳位顺带写入——即改数值未点「应用」但动了任何开关，数值也会生效（显式「应用」才有确认弹窗与 toast）。
- ⚠️ 不变式：三组 RadioGroup 打开页面时由 `refreshAllDrawables()` 统一刷新选中态染色（自绘 drawable 不感知 checked 变化）。

### 3.3 GLM 消费点
- `avPathMode`（full=全 CarPlay / bt_audio=蓝牙出音频、CarPlay 只出视频）与 `audioChannel`（media/nav）——M6k 交接既有待办，继续有效。
- `bootAutostart` 已由 BootReceiver 消费（读后决定是否拉起主页）；`autoConnect` 已由主页 maybeAutoConnect 消费（car-only）。
- `videoScalePercent/limit30Fps` 经 `videoKeyOf` → `appliedVideoKey` 变更守卫（改参数必须断开重启）。

---

## 4. 占位页 PlaceholderActivity
EXTRA_TITLE 标题 + 居中「功能建设中，敬请期待」+ back 返回。GLM 远期功能（自定义返回图标 / 方向盘按键学习）落地时替换此页。

---

## 5. LiteConfig 字段字典（UI ↔ 底层接口总表）

| 字段 | 默认 | 写入组件 | 消费方 |
|---|---|---|---|
| ssid / password | "" / "" | 配置页 car 态 | LiteSessionService MANUAL（既有） |
| channel / security | 0 / WPA2 | **无 UI**（B3 遗留） | GLM 接双栈时决定弃用或映射 |
| bluetoothAddress（bt_address） | ""（自动匹配已配对 iPhone） | 配置页蓝牙选择（成对写 bt_name） | 连接时蓝牙匹配 |
| limit30Fps | false | 设置页 checkbox | videoKeyOf → /info maxFPS |
| videoScalePercent | 150（钳位 10–150） | 设置页数值行 | videoKeyOf → /info 分辨率 |
| navDuckPercent | 50（钳位 30–100） | 设置页数值行 | 导航播报压低（音频侧） |
| speakerForCalls / softwareVideoDecoder | false / false | 设置页 checkbox | 通话路由 / 解码兜底 |
| orientationMode | auto | 设置页 radio | applyOrientationMode（顶层函数） |
| avPathMode / audioChannel | full / media | 设置页 radio | **GLM 待接线**（既有待办） |
| bootAutostart | false | 设置页 checkbox | BootReceiver（已实现） |
| autoConnect | false | 设置页 checkbox | 主页 maybeAutoConnect（已实现，car-only） |
| **activeChannel** | "car" | 主页 toggle（三选一） | **GLM 分发**：car=MANUAL / relay=同网段 / usb=有线 |
| relaySsid / relayPassword | "" / "" | 配置页 relay 态 | **GLM 中继方案** |
| carIpv6Channel / carIpv4Channel | 0 / 0 | 配置页 car 态 | **GLM 建网**（0=自动） |
| relayIpv6Channel / relayIpv4Channel | 0 / 0 | 配置页 relay 态 | **GLM 建网**（0=自动） |
| appliedVideoKey | "" | **仅** saveAppliedVideoKey() | videoKey 变更守卫（save() 刻意排除） |

SharedPreferences 名 `diplay_lite`；键名见 LiteConfig.load/save。

---

## 6. GLM 待接线汇总（按优先序）

| # | 接线点 | UI 入口（替换位置） | 现状 → 期望 |
|---|---|---|---|
| 1 | activeChannel 分发 | MainActivity `connect()` relay/usb 两分支的 toast；LiteSessionService 编排 | toast 占位 → 按通道建链（relay=无线同网段，usb=有线） |
| 2 | 自动连接白名单 | MainActivity `maybeAutoConnect()` 的 `activeChannel != "car"` 判断 | car-only → 链路就绪的通道均可 |
| 3 | 双栈信道消费 | 配置页 4 字段落盘 | 待消费：热点建网首选信道（0=自动）；并处置旧 `channel` 字段 |
| 4 | 中继凭据消费 | relaySsid/relayPassword | 待消费：随身WiFi 同连编排 |
| 5 | avPathMode / audioChannel | 设置页两组 radio | 既有 M6k 交接待办继续有效 |
| 6 | 蓝牙 | bluetoothAddress（空=自动） | 已可用，中继/有线链路沿用 |

## 7. 交互不变式（GLM 改码红线）

1. 三选一互斥必须**恒有一个激活通道**（toggle 点关弹回；activeChannel 不允许空/非法值）。
2. **copy-merge 落盘**：任何页面保存 = `load().copy(本页管辖字段)`，禁止整包覆盖（appliedVideoKey 会被冲掉）。
3. appliedVideoKey 只经 `saveAppliedVideoKey()` 维护。
4. **ACTIVE/CONNECTING 禁止重建视图**（换 Surface = 冻结）；空闲态旋转重走 buildRoot 时必须重取 IconPainter 图标与 toggle 回显（loadToggles）。
5. 自动连接一个进程生命周期只发起一次。
6. 自绘 radio/checkbox 选中态变化必须重设 drawable。
7. 视频参数变更必须经 confirmVideoChange 断开+重启（渲染异常兜底），不可绕过。
