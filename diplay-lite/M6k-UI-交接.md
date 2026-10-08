# M6k-UI 交接：体验版 UI 重做（零 XML → XML 布局 + 双形态适配）

- 日期：2026-10-06
- 来源：TRAE 侧工作树 `C:\Users\87878\AppData\Roaming\TRAE SOLO CN\ModularData\ai-agent\work-mode-projects\6abf76cb12b4fdb75f8e5212\Diplay-Chinese\diplay-lite`（构建基准，clean 全量重建通过）
- 状态：**已写入 DiPlay-4.1 权威树并完成整合构建验证（BUILD SUCCESSFUL，与 M6k12 shared 零冲突）**，待 GLM 做后续功能接线与真机验证
- 背景：按用户提供的 4 张 iOS 风白卡片渲染图重做 Lite 全部 UI，从"零 XML 纪律"切换为 XML 布局 + shape/selector 资源，适配车机横屏 + 手机竖屏双形态

## 一、交付文件清单

### 覆盖（3 个，写入前已核实权威树均为 M6k8 时代旧版，无 GLM 改动被覆盖）
| 文件 | 变更 |
|---|---|
| `app/src/main/java/com/shilapi/diplay/lite/MainActivity.kt` | 整体重写（694 → ~564 行）：纯代码 UI → XML 接线；连接/触摸/悬浮球/蓝牙/日志/系统跳转逻辑原样保留 |
| `app/src/main/java/com/shilapi/diplay/lite/LiteConfig.kt` | 新增 5 字段（orientationMode/avPathMode/audioChannel/bootAutostart/autoConnect）+ load/save 对应读写；`MAX_DUCK_PERCENT` 70 → 100 |
| `app/src/main/AndroidManifest.xml` | 整体重写：主题上提、**删除写死的 screenOrientation="landscape"**、注册 3 个新组件、新增 RECEIVE_BOOT_COMPLETED 权限 |

### 新增 Kotlin（4 个）
- `IconPainter.kt` — Canvas Path 现画图标（gear/back/arrows/upRight），替代 VectorDrawable
- `SettingsActivity.kt` — 设置页（含顶层函数 `applyOrientationMode(activity, mode)`）
- `PlaceholderActivity.kt` — 占位页（EXTRA_TITLE），"回到图标/滚轮"入口跳这里
- `BootReceiver.kt` — 开机自启（受 LiteConfig.bootAutostart 门控）

### 新增资源（20 个，权威树此前无 res/ 目录）
- `res/drawable/` ×11：bg_card / bg_ball / bg_divider / bg_input_box / bg_input_line / bg_log / btn_primary / btn_outline / btn_small_primary / btn_small_outline / btn_text
- `res/layout/` ×3：activity_main / activity_settings / activity_placeholder
- `res/layout-land/` ×2：activity_main / activity_settings（横竖屏控件 ID 完全一致）
- `res/values/` ×4：colors / strings / dimens / styles（含 DiPlayTheme，application 级引用）

### 明确未动（GLM 辖区 + 共享件）
`shared/` 全部、`LiteApp/LiteLog/LiteSessionService/LiteState/LiteVideoBridge`、`build.gradle.kts`、`gradle.properties`、native/

## 二、关键设计决策（整合时必须保持的约束）

1. **API16 红线**：无 VectorDrawable（图标一律走 IconPainter）、无 elevation、布局只用 marginLeft/Right（非 start/end）。
2. **旋转策略（核心）**：Manifest 全量 configChanges，Activity 旋转不重建；但 layout/ ↔ layout-land/ 不会自动切换，因此 `MainActivity.onConfigurationChanged` 在**空闲态（IDLE/FAILED）**手动 `setContentView` 重建视图树并 `bindViews()`；**ACTIVE/CONNECTING 态拒绝重建**——Surface 换 = 解码器重配 + 等关键帧 = 冻结 1-5s。横竖屏方向由 `LiteConfig.orientationMode` 经 `applyOrientationMode()` 驱动（land/portrait/auto）。
3. **配置合并落盘**：主页 `saveFromForm` 用 `LiteConfig.load(this).copy(...)` 只覆写 ssid/password/bluetoothAddress 三项；设置页 `persistFromForm` 同法合并。整包覆盖会把另一个页面的字段冲回默认，禁止。
4. **自动连接**：`maybeAutoConnect()` 一次性（autoConnectTried 守卫），条件：IDLE + ssid 非空 + 服务未跑 + appliedVideoKey 与上次一致（视频参数改过则跳过，避免开机弹确认框）。
5. **数值区间**：scale 10–100（>100 是放大超采样，弱机纯亏）；duck 30–100（100 = 不压低）；orientationMode ∈ {land, portrait, auto}；avPathMode ∈ {full, bt}；audioChannel ∈ {media, nav}。
6. **M15 纪律**：关于页显示真实 versionName（packageManager 读取），不写死。
7. 主页不再持有 Spinner/CheckBox 等 12 个表单字段——视频参数/高级参数全部迁到设置页；分辨率变更仍由主页连接时 videoKey 二次确认兜底。

## 三、新配置键消费状态（GLM 接线清单）

| 配置键 | 默认值 | 消费方 | 状态 |
|---|---|---|---|
| orientationMode | auto | applyOrientationMode + MainActivity 启动时 | ✅ 已生效 |
| bootAutostart | false | BootReceiver（RECEIVE_BOOT_COMPLETED 已在 Manifest） | ✅ 已生效 |
| autoConnect | false | MainActivity.maybeAutoConnect | ✅ 已生效 |
| avPathMode | full | 仅存储 + 设置页 UI | ⬜ **待 GLM 接线到 shared 音视频链路** |
| audioChannel | media | 仅存储 + 设置页 UI | ⬜ **待 GLM 接线到音频路由** |

原有键（videoScalePercent / limit30Fps / navDuckPercent / speakerForCalls / softwareVideoDecoder / channel / security）语义不变，只是 UI 位置迁到设置页。

## 四、整合构建验证

在权威树（DiPlay-4.1，shared 为 M6k12 时代版本）执行：

```
.\gradlew.bat assembleDebug
BUILD SUCCESSFUL in 2m 42s  (62 actionable tasks: 61 executed, 1 up-to-date)
```

- app 模块 UI 代码与 GLM shared **零冲突编译通过**
- 警告全部为预期内的 API16 兼容弃用项（Handler()/FLAG_FULLSCREEN/getColor/getDefaultAdapter），无需处理
- shared 侧警告属 GLM 辖区，未触碰

## 五、真机验证清单（按风险排序）

1. 旋转切换：空闲态转屏确认双目录布局切换 + 表单回填；ACTIVE 投屏中转屏确认不冻结
2. 开机自启：勾选后重启设备，验证 BootReceiver 拉起主页
3. 自动连接：填 ssid + 勾选 + 重启应用，确认只触发一次
4. 设置页即改即存：杀进程重开验证持久化
5. 主页蓝牙选择 / 热点跳转 / 日志导出回归
