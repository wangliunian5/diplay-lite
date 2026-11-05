package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.diag.AnchorLog
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream

/**
 * vc167（外置 USB 蓝牙 dongle 第 2 步 B 批）/ vc181（外置 USB 蓝牙 C/D 批）：usbbt 接入缝。
 *
 * 接入缝（唯一）：CarPlayController 无线建链处 `UsbBtBootstrapStream.open(context, name)`，
 * 该文件一行不改。C/D 批起 [open] 走完整建链：
 * 枚举 dongle → 权限 → 打开驱动 → Host 初始化（HCI Reset→…→ScanEnable）
 * → 等待 iPhone 回连（SSP Just-Works 自动配对）→ 认证/加密 → SDP 查 RFCOMM 信道
 * → RFCOMM 建链 → 返回跑在 iAP2 RFCOMM DLC 上的 [UsbBtRfcommStream]。
 * 状态沿途写 [lastStatusText]（设置页显示 + LiteLog 落日志）；失败抛带状态的异常。
 */
class UsbBtBootstrapStream private constructor() : BlockingDuplexByteStream {
    override fun send(data: ByteArray) {
        throw UnsupportedOperationException("usbbt 接入缝不可实例化（仅用 open() 静态入口）")
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        throw UnsupportedOperationException("usbbt 接入缝不可实例化（仅用 open() 静态入口）")
    }

    override fun close() = Unit

    companion object {
        private const val TAG = UsbBtSession.TAG

        /** 建链各阶段超时（毫秒）：初始化 / 等 iPhone 回连+配对+建 RFCOMM 的总窗口。 */
        private const val INIT_TIMEOUT_MILLIS = 15_000L
        private const val LINK_TIMEOUT_MILLIS = 90_000L
        private const val TAKE_SLICE_MILLIS = 1000L

        /** vc184：等系统 USB 授权弹窗的最长窗口（车机屏幕上用户读题+点允许要留足时间）。 */
        private const val PERMISSION_WAIT_MILLIS = 90_000L

        /** 最近一次探测/建链的逐级状态（设置页状态行直接显示；未探测过 = 空串）。 */
        @Volatile
        var lastStatusText: String = ""
            private set

        /** 探活成功的标志：dongle 本机地址（初始化完成后可读）。 */
        @Volatile
        var lastBdAddr: String? = null
            private set

        /**
         * usbbt 建链入口（开关开启时由 CarPlayController 调用，工作线程）。
         * 成功返回跑在 iAP2 RFCOMM DLC 上的流；任何一步失败抛带状态文本的异常。
         *
         * vc192：若 [UsbBtStandby] 正在常驻广播（外置蓝牙选中后 App 一起来就拉活 dongle），
         * 这里不再自己开 dongle（会二次 claim 冲突），而是直接取它交出来的那条流。
         * 常驻广播是 legacy 0.3.1 的既有行为：dongle 一直以我们的广播名可发现，iPhone 主动回连。
         */
        fun open(
            context: Context,
            name: String,
            onDiagnostic: (String) -> Unit = {},
        ): BlockingDuplexByteStream {
            if (UsbBtStandby.isRunning) {
                status("等待常驻广播交出 iPhone 链路…")
                // vc215：已配对则主动 page 回连（用户指令：配对成功后主动用 dongle 建立 iAP2 链路），
                // 不再只等 iPhone 自发回连；未配对/发起失败维持原被动等待语义不变。
                // vc219：实测点连接时 standby 的 host 可能还在初始化（kicked=false）→ 轮询重踢，
                // 最多等 20s，踢出去为止再进 awaitStream。
                if (UsbBtHost.pairStateSnapshot == PairState.PAIRED) {
                    var kicked = UsbBtHost.requestActiveConnect()
                    val kickDeadline = System.currentTimeMillis() + 20_000L
                    while (!kicked && System.currentTimeMillis() < kickDeadline && UsbBtStandby.isRunning) {
                        try {
                            Thread.sleep(2_000L)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                        kicked = UsbBtHost.requestActiveConnect()
                    }
                    diagSink("usbbt: 已配对，主动回连已配对 iPhone（kicked=$kicked）")
                }
                return UsbBtStandby.awaitStream(UsbBtStandby.HANDOFF_WAIT_MILLIS)
                    ?: fail("常驻广播未在 ${UsbBtStandby.HANDOFF_WAIT_MILLIS / 1000}s 内交出 iPhone 链路（${UsbBtStandby.describe()}）")
            }
            return openDirect(context, name, onDiagnostic, LINK_TIMEOUT_MILLIS)
        }

        /**
         * 真正「自己开 dongle 并等 iPhone」的建链实现（[open] 与 [UsbBtStandby] 共用）。
         * [linkTimeoutMillis] 为初始化完成后等 iPhone 回连的总窗口：一次性连接用 [LINK_TIMEOUT_MILLIS]，
         * 常驻广播用长窗口（尽量少反复重开 dongle）。
         */
        internal fun openDirect(
            context: Context,
            name: String,
            onDiagnostic: (String) -> Unit = {},
            linkTimeoutMillis: Long = LINK_TIMEOUT_MILLIS,
        ): BlockingDuplexByteStream {
            // vc186：诊断出口——usbbt 过程日志此前只走 system logcat，而日志上云只带 LiteLog，
            // 导致 vc183~vc185 三份 t3 日志里 usbbt 过程 0 条、根因无法二分。
            // 这里统一经 diagSink 转发给调用方（CarPlayController.debugLog → uiListener.onDebugLog → LiteLog）。
            diagSink = { line ->
                Log.i(TAG, line)
                runCatching { onDiagnostic(line) }
            }
            // vc184：dongle 身份名称由设置页自定义（回落默认 "DiPlay CarPlay"）。
            val identity = UsbBtIdentity(
                name = name.ifBlank { "DiPlay CarPlay" },
                classOfDevice = 2098184,
                iap2RfcommChannel = 1,
                ioCapability = 1, // DisplayYesNo（vc212：弹码确认；MITM 由 host 侧 auth=5 携带）
            )
            val usb = UsbBtSession.manager(context) ?: fail("USB 服务不可用")
            val dongle = UsbBtSession.findDongle(usb)
                ?: fail("未检测到 USB 蓝牙 dongle（请确认已插入）")
            status("已检测到 dongle（${UsbBtSession.deviceLabel(dongle)}）")
            diagSink(AnchorLog.mark("dongle-detected"))
            if (!UsbBtSession.hasPermission(context, dongle)) {
                // vc184（t3 反馈）：原地等系统授权弹窗结果——用户点「允许」后本轮直接继续建链，
                // 不再要求「允许后重试连接」；ROM 不弹窗/被拒/超时则带明确状态失败。
                status("等待 USB 授权（请在系统弹窗中点允许）…")
                if (!UsbBtSession.awaitPermission(context, dongle, PERMISSION_WAIT_MILLIS)) {
                    fail("USB 授权未完成（弹窗未出现/被拒绝/超时），请重试连接并在弹窗中点允许")
                }
                status("USB 授权完成，继续建链")
            }
            // host 与 driver 互需引用：先开 driver（转发 listener 兜住早期事件），再建 host 回接
            val hostRef = arrayOfNulls<UsbBtHost>(1)
            val forward = object : UsbBtDriver.Listener {
                override fun onHciEvent(event: HciEvent) {
                    hostRef[0]?.onHciEvent(event)
                }

                override fun onAclPacket(packet: UsbBtDriver.AclPacket) {
                    hostRef[0]?.onAclPacket(packet)
                }

                override fun onTick() {
                    hostRef[0]?.onTick()
                }

                override fun onTransportFailure(reason: String) {
                    status("传输中断：$reason")
                    hostRef[0]?.onTransportFailure(reason)
                }
            }
            var host: UsbBtHost? = null
            var driver: UsbBtDriver? = null
            try {
                // 2026-11-03（K60 CSR8510）：初始化首轮超时（首命令无应答/整轮超时）时，
                // 一次性 portReset 兜底（每进程一次，见 UsbBtDriver.claimPortResetFallback）；
                // 复位后重开重试一轮，仍不应答维持超时失败。
                var retried = false
                while (true) {
                    val ready = java.util.concurrent.CountDownLatch(1)
                    driver = UsbBtDriver.open(context, dongle, forward, diagSink, portResetFirst = retried)
                        ?: fail("打开 dongle 失败：设备刚重枚举或打开被拒")
                    status(if (retried) "端口复位完成，重试初始化…" else "已打开 dongle（${UsbBtSession.deviceLabel(dongle)}）")
                    // vc190：Realtek/Broadcom ROM 模式 dongle（如 CM390 / RTL8761BUV）必须先灌固件，
                    // 否则后续 HCI 初始化只能读到全零 BD_ADDR。host 初始化前在此完成固件下载；
                    // CSR8510 等固件片内芯片走 NoFirmwareNeeded 空过，无回归。
                    // vc233（BT-232-01 姊妹项）：**只有 Failed 中止建链**——此前 Failed 只打日志继续跑，
                    // 与 FIRMWARE.md:41「上传失败会停止初始化」承诺不符，且会把「固件没灌进去」
                    // 伪装成「host 初始化超时」误导排查。Uploaded / Skipped(already patched) /
                    // NoFirmwareNeeded(CSR 片内 ROM) / Skipped(identity 不符) 都是正常跳过，不误杀。
                    val fw = driver!!.loadFirmware(context.assets)
                    if (fw is UsbBtFirmwareLoader.LoadResult.Failed) {
                        fail("固件加载失败：${fw.reason}，不继续初始化")
                    }
                    status("固件加载：$fw")
                    diagSink("usbbt: firmware load result=$fw")
                    host = UsbBtHost(identity, driver!!, { msg -> Log.i(TAG, msg); diagSink("host: $msg") }).also {
                        it.onStatus = { text -> status(text) }
                        it.onReady = { ready.countDown() }
                        hostRef[0] = it
                    }
                    host!!.start()
                    // vc227：host 每次重建（dongle USB 掉线重枚举 / 端口复位重试）都要把持久化
                    // link key 灌回内存。缺这步时实测：重建后 sharedLinkKeys 为空 → 主页回落
                    // 「待配对」、LinkKeyRequest 只能回 Negative → iPhone 侧表现为「连不上」。
                    UsbBtHost.restorePairing()
                    if (ready.await(INIT_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) break
                    if (retried || !UsbBtDriver.claimPortResetFallback()) {
                        fail(
                            if (retried) {
                                "dongle 初始化超时（dongle 不应答，已尝试端口复位）"
                            } else {
                                "dongle 初始化超时（${host?.statusText}）"
                            },
                        )
                    }
                    retried = true
                    diagSink("usbbt: 初始化首轮超时，执行端口复位兜底（每进程一次）后重试初始化")
                    try {
                        host?.stop()
                    } catch (_: Throwable) {
                    }
                    try {
                        driver?.close()
                    } catch (_: Throwable) {
                    }
                    host = null
                    driver = null
                }
                val readyHost = host ?: fail("dongle 初始化超时（host 缺失）")
                diagSink(AnchorLog.mark("init-done"))
                readyHost.localAddress()?.let { lastBdAddr = it.toString() }
                // 等 iPhone 回连 → Just-Works 配对 → 加密 → SDP/RFCOMM → DLC
                // vc192：状态里带上广播名 —— 外测的下一步动作是「去 iPhone 蓝牙列表里选这个名字」。
                status("广播中「${identity.name}」，等待 iPhone 配对（请在手机蓝牙设置中选择该名称）…")
                val deadline = System.currentTimeMillis() + linkTimeoutMillis
                while (true) {
                    when (readyHost.phase) {
                        UsbBtHost.Phase.FAILED -> fail("建链失败：${readyHost.statusText}")
                        UsbBtHost.Phase.STOPPED -> fail("dongle 已停止")
                        else -> Unit
                    }
                    val remain = deadline - System.currentTimeMillis()
                    if (remain <= 0) fail("等待 iPhone 连接超时（${readyHost.statusText}）")
                    val dlc = readyHost.awaitIap2Dlc(minOf(remain, TAKE_SLICE_MILLIS))
                    if (dlc != null) {
                        return UsbBtRfcommStream(dlc, readyHost)
                    }
                }
            } catch (e: Throwable) {
                try {
                    host?.stop()
                } catch (_: Throwable) {
                }
                driver?.let {
                    try {
                        it.close()
                    } catch (_: Throwable) {
                    }
                }
                throw e
            }
        }

        /** 供设置页状态行：优先显示真实探测进度，未探测过则退回枚举结果。 */
        fun describe(context: Context): String {
            lastStatusText.takeIf { it.isNotBlank() }?.let { return it }
            val usb = UsbBtSession.manager(context)
                ?: return "USB 服务不可用"
            val dongle = UsbBtSession.findDongle(usb)
                ?: return "未检测到 USB 蓝牙 dongle（请确认已插入）"
            return "已检测到 dongle（${UsbBtSession.deviceLabel(dongle)}），待连接时探活"
        }

        /**
         * vc191：设置页「外置 USB 蓝牙」实时状态块 —— 当前已插入 dongle 的 "vid:pid"
         * （如 CM390 = "2b89:6275"）；未检测到 / USB 服务不可用返回 null。
         * 与 [describe] 同走 [UsbBtSession.findDongle]，但只返回裸标识供 UI 自行排版。
         */
        fun dongleVidPid(context: Context): String? {
            val usb = UsbBtSession.manager(context) ?: return null
            val dongle = UsbBtSession.findDongle(usb) ?: return null
            return "%04x:%04x".format(dongle.vendorId, dongle.productId)
        }

        /** vc186：诊断/状态输出口（open 时按调用方注入；默认仅 system logcat）。 */
        @Volatile
        private var diagSink: (String) -> Unit = { line -> Log.i(TAG, line) }

        private fun status(text: String) {
            lastStatusText = text
            // vc186：状态行进 diagSink（→LiteLog 上云），不再只落 system logcat。
            diagSink(text)
        }

        private fun fail(reason: String): Nothing {
            status(reason)
            throw java.io.IOException(reason)
        }
    }
}
