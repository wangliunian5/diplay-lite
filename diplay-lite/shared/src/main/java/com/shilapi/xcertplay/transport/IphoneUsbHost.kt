package com.shilapi.xcertplay.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Exact Apple USB identities allowed by the deployment configuration. */
class IphoneUsbMatcher private constructor(
    private val allowedDevices: Set<UsbDeviceId>?,
    private val allowAnyAppleProduct: Boolean,
) {
    constructor(allowedDevices: Collection<UsbDeviceId>) : this(
        allowedDevices.toSet(),
        allowAnyAppleProduct = false,
    )

    init {
        require(allowedDevices == null || allowedDevices.isNotEmpty()) {
            "At least one iPhone USB identity is required when not using Apple-vendor discovery"
        }
        require(allowedDevices == null || allowedDevices.all { it.vendorId == APPLE_VENDOR_ID }) {
            "iPhone USB identities must use Apple vendor ID 0x${APPLE_VENDOR_ID.toString(16)}"
        }
    }

    fun matches(vendorId: Int, productId: Int): Boolean =
        if (allowAnyAppleProduct) vendorId == APPLE_VENDOR_ID
        else UsbDeviceId(vendorId, productId) in allowedDevices.orEmpty()

    companion object {
        /** Apple VID used by LIVI commit 0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3. */
        const val APPLE_VENDOR_ID = 0x05ac

        /** Discovers every Apple device, matching only the vendor ID confirmed by LIVI. */
        fun appleVendor(): IphoneUsbMatcher = IphoneUsbMatcher(null, allowAnyAppleProduct = true)
    }
}

/**
 * Android USB Host bring-up boundary for a configured iPhone identity.
 *
 * LIVI's fixed commit uses Apple vendor request `0x52`, value `0`, index `4`, and then selects
 * configuration `6`. The vendor request can make the iPhone re-enumerate. Android does not offer
 * Linux sysfs configuration control or a synchronous re-enumeration primitive, so this class
 * closes the first connection and requires the caller to receive, re-authorize, and pass the new
 * [UsbDevice] to [selectCarPlayConfigurationAsync]. All opens run on the supplied executor.
 */
class IphoneUsbHost(
    context: Context,
    private val usbManager: UsbManager,
    private val matcher: IphoneUsbMatcher,
    private val permissionAction: String = "${context.packageName}.IPHONE_USB_PERMISSION",
) {
    private val appContext = context.applicationContext

    /**
     * 1.0.2：诊断输出钩子。CarPlayController 把 debugLog（落盘文件日志）挂进来，
     * setConfiguration/claim 等关键节拍不再只进 logcat——MIUI 上那台车机的
     * "claim 失败但文件日志看不到 setConfiguration 结果"就是漏网教训。
     */
    @Volatile var debugSink: ((String) -> Unit)? = null

    private fun report(message: String) {
        Log.i("IphoneUsbHost", message)
        debugSink?.invoke(message)
    }

    sealed class PermissionRequest {
        data class AlreadyGranted(val device: UsbDevice) : PermissionRequest()
        data class Requested(val device: UsbDevice) : PermissionRequest()
    }

    sealed class PermissionResult {
        data class Granted(val device: UsbDevice) : PermissionResult()
        data class Denied(val device: UsbDevice) : PermissionResult()
    }

    sealed class TransitionResult {
        /** The connection was closed; wait for a new matching attached device before continuing. */
        data class ReenumerationRequested(val vendorResponseByte: Int) : TransitionResult()

        data class Failed(val error: IphoneUsbException) : TransitionResult()
    }

    sealed class Iap2SessionResult {
        data class Connected(val session: Iap2UsbSession) : Iap2SessionResult()
        data class Failed(val error: IphoneUsbException) : Iap2SessionResult()
    }

    fun discover(): List<UsbDevice> =
        usbManager.deviceList.values.filter { matcher.matches(it.vendorId, it.productId) }

    @Throws(IphoneUsbException::class)
    fun requestPermission(device: UsbDevice): PermissionRequest {
        requireConfiguredDevice(device)
        if (usbManager.hasPermission(device)) return PermissionRequest.AlreadyGranted(device)

        usbManager.requestPermission(device, permissionPendingIntent())
        return PermissionRequest.Requested(device)
    }

    /** Returns null for unrelated broadcasts, malformed results, or non-configured devices. */
    fun parsePermissionResult(intent: Intent): PermissionResult? {
        if (intent.action != permissionAction) return null
        val device = intent.usbDevice() ?: return null
        if (!matcher.matches(device.vendorId, device.productId)) return null
        return if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            PermissionResult.Granted(device)
        } else {
            PermissionResult.Denied(device)
        }
    }

    /** Returns the new matching device after the vendor request caused Android USB re-enumeration. */
    fun parseAttachedDevice(intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        val device = intent.usbDevice() ?: return null
        return device.takeIf { matcher.matches(it.vendorId, it.productId) }
    }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerPermissionReceiver(onResult: (PermissionResult) -> Unit): Closeable =
        registerReceiver(IntentFilter(permissionAction)) { parsePermissionResult(it)?.let(onResult) }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerAttachReceiver(onAttached: (UsbDevice) -> Unit): Closeable =
        registerReceiver(IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            parseAttachedDevice(it)?.let(onAttached)
        }

    /**
     * Sends the LIVI-evidenced vendor request then closes the connection before re-enumeration.
     * The callback is invoked from [executor].
     */
    fun requestCarPlayReenumerationAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (TransitionResult) -> Unit,
    ) {
        executor.execute {
            callback(runTransition(device) { connection ->
                // vc188 探测①：发 0x52 前先 dump USB 设备描述符要素（含 bcdDevice）。
                // 关键价值：若会话随后死在 usb/config（早于 iAP2 机型交换，model=/ios= 全缺失），
                // 仍可凭此反推 iPhone 机型与 iOS 版本 → 才能验证「是否低 iOS 版本差异导致」。
                report("wired probe usb: " + describeRawUsb(connection))
                var lastTransferred = -1
                var lastErrno = 0
                var responseByte = 0
                // vc189：优先走 native usbfs（直接 ioctl USBDEVFS_CONTROL）下发 0x52，失败返回
                // -errno，可区分 ENODEV(陈旧实例)/EPIPE(设备拒绝)/ETIMEDOUT(线缆)/EBUSY(claim)——
                // 框架 controlTransfer 把 errno 吞成不透明 -1，正是 222631/070240 卡死点。
                val useNative = LegacyUsbHostNative.available
                val fd = if (useNative) runCatching { connection.fileDescriptor }.getOrDefault(-1) else -1
                for (attempt in 0 until CARPLAY_CONFIG_MAX_ATTEMPTS) {
                    val response = ByteArray(VENDOR_RESPONSE_LENGTH)
                    val nativeR = if (useNative && fd >= 0) {
                        runCatching {
                            LegacyUsbHostNative.controlTransferErrno(
                                fd, USB_VENDOR_DEVICE_IN, CARPLAY_CONFIGURATION_REQUEST,
                                0, CARPLAY_CONFIGURATION_INDEX, response, response.size,
                                CONTROL_TRANSFER_TIMEOUT_MILLIS,
                            )
                        }.getOrElse { -1 }
                    } else {
                        connection.controlTransfer(
                            USB_VENDOR_DEVICE_IN, CARPLAY_CONFIGURATION_REQUEST,
                            0, CARPLAY_CONFIGURATION_INDEX, response, response.size,
                            CONTROL_TRANSFER_TIMEOUT_MILLIS,
                        )
                    }
                    lastTransferred = if (nativeR >= 0) nativeR else -1
                    lastErrno = if (nativeR < 0) -nativeR else 0
                    if (lastTransferred == response.size) {
                        responseByte = response[0].toInt() and 0xff
                        // vc188 错误分级：区分「已配置拒绝」(0x6) 与「接受切换」(0x0)。
                        report(
                            "wired CarPlay vendor request 0x52 response=0x" +
                                responseByte.toString(16) +
                                " (0x6=already configured; 0x0=accepted)" +
                                if (useNative && fd >= 0) " [native usbfs]" else " [framework]"
                        )
                        break
                    }
                    report(
                        "wired 0x52 attempt ${attempt + 1}/$CARPLAY_CONFIG_MAX_ATTEMPTS " +
                            "transferred=$lastTransferred" +
                            if (lastErrno != 0) " errno=${lastErrno}(${errnoName(lastErrno)})" else "" +
                            " (timeout=${CONTROL_TRANSFER_TIMEOUT_MILLIS}ms)" +
                            if (useNative && fd >= 0) " [native usbfs]" else " [framework]"
                    )
                    if (attempt < CARPLAY_CONFIG_MAX_ATTEMPTS - 1) {
                        Thread.sleep(CARPLAY_CONFIG_RETRY_DELAY_MILLIS.toLong())
                    }
                }
                if (lastTransferred != VENDOR_RESPONSE_LENGTH) {
                    throw IphoneUsbException.Protocol(
                        "CarPlay configuration request transferred $lastTransferred of " +
                            "$VENDOR_RESPONSE_LENGTH bytes" +
                            if (lastErrno != 0) " (errno=${lastErrno} ${errnoName(lastErrno)})" else ""
                    )
                }
                TransitionResult.ReenumerationRequested(responseByte)
            })
        }
    }

    /**
     * vc188 探测①：把 iPhone 的 USB 设备描述符要素打成一行日志。
     *
     * 为什么需要：222631 的失败发生在 `STEP usb/config`，**早于** iAP2 identification，
     * 所以日志里 `model=` / `ios=` 全是空的，无法判断是不是 SE/iOS15 特有。
     * USB 设备描述符在 discovered 阶段就能读到，其中 **bcdDevice 通常编码 iOS 版本**
     * （高字节≈主版本，如 0x0F=15 / 0x10=16），可在不建链的情况下反推 iOS 版本。
     *
     * ⚠️ bcdDevice→iOS 版本是**经验映射，非 Apple 公开承诺**；本次先落原始值，
     * 后续拿一台已知 iOS 版本的 iPhone 建链成功后再校准映射关系。
     */
    private fun describeRawUsb(connection: UsbDeviceConnection): String {
        val raw = runCatching { connection.rawDescriptors }.getOrNull()
        if (raw == null || raw.size < 18) {
            return "rawDescriptors=unavailable(len=${raw?.size ?: -1})"
        }
        val vid = ((raw[9].toInt() and 0xff) shl 8) or (raw[8].toInt() and 0xff)
        val pid = ((raw[11].toInt() and 0xff) shl 8) or (raw[10].toInt() and 0xff)
        val bcdDevice = ((raw[13].toInt() and 0xff) shl 8) or (raw[12].toInt() and 0xff)
        // vc188：getSerial() 是 API21+ 方法。API≤19（t3=19 / at8317=17 / C8815=16）上直接调用
        // 会抛 NoSuchMethodError —— runCatching 虽能兜住不至于崩溃，但每次都会生成一条
        // Throwable（污染日志、干扰外测判读），且 serial 恒为 "-" 使探测失去意义，故显式守卫。
        // 老设备改由 bcdDevice 反推 iOS 版本，serial 留空即可。
        val serial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching { connection.serial }.getOrNull()
        } else {
            null
        }
        return "vid=0x${vid.toString(16)} pid=0x${pid.toString(16)} " +
            "bcdDevice=0x${"%04x".format(bcdDevice)} " +
            "(guess iosMajor=0x${((bcdDevice shr 8) and 0xff).toString(16)} " +
            "iosMinor=0x${(bcdDevice and 0xff).toString(16)}) " +
            "serial=${serial ?: "-"}"
    }

    /** vc189：把 Linux errno 数字映射为可读名（标准 Linux 值，arm/x86 一致），
     *  便于外测日志二分 0x52 失败根因。 */
    private fun errnoName(errno: Int): String = when (errno) {
        19 -> "ENODEV"      // fd 指向内核已拆设备=陈旧实例/死 fd（KK 重枚举竞态）
        32 -> "EPIPE"       // 设备在线但 STALL 拒绝=USB 态卡死
        110 -> "ETIMEDOUT"  // 设备不 ACK=线缆/供电不足
        16 -> "EBUSY"       // 接口被占用=claim 时序问题
        22 -> "EINVAL"      // 参数非法
        5 -> "EIO"          // 内核 I/O 错误
        12 -> "ENOMEM"      // 内存不足
        else -> "errno($errno)"
    }

    /**
     * Opens LIVI's USBMUX bulk pipe on the re-enumerated iPhone.
     *
     * This repeats CarPlay configuration selection on the newly opened Android connection and
     * claims the Apple USB Multiplexor interface, preferring the LIVI bulk pair 0x04/0x85.
     * After a successful callback, it owns the returned session and must close it. If the callback
     * throws, this method closes the session before propagating the callback failure.
     */
    fun openIap2UsbSessionAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (Iap2SessionResult) -> Unit,
        assumeAlreadyConfigured: Boolean = false,
    ) {
        executor.execute {
            val result = try {
                Iap2SessionResult.Connected(openIap2UsbSession(device, assumeAlreadyConfigured))
            } catch (error: IphoneUsbException) {
                Iap2SessionResult.Failed(error)
            } catch (error: SecurityException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.PermissionDenied("USB permission was denied", error),
                )
            } catch (error: RuntimeException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.DeviceUnavailable("iPhone USBMUX operation failed", error),
                )
            }
            try {
                callback(result)
            } catch (error: Throwable) {
                if (result is Iap2SessionResult.Connected) {
                    try {
                        result.session.close()
                    } catch (closeError: Throwable) {
                        // API16 适配：Throwable.addSuppressed 是 API 19+。
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            error.addSuppressed(closeError)
                        }
                    }
                }
                throw error
            }
        }
    }

    private fun runTransition(
        device: UsbDevice,
        operation: (UsbDeviceConnection) -> TransitionResult,
    ): TransitionResult = try {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = openConnection(device)
        try {
            operation(connection)
        } finally {
            connection.close()
        }
    } catch (error: IphoneUsbException) {
        TransitionResult.Failed(error)
    } catch (error: SecurityException) {
        TransitionResult.Failed(IphoneUsbException.PermissionDenied("USB permission was denied", error))
    } catch (error: RuntimeException) {
        TransitionResult.Failed(IphoneUsbException.DeviceUnavailable("iPhone USB operation failed", error))
    }

    private fun openIap2UsbSession(
        device: UsbDevice,
        assumeAlreadyConfigured: Boolean = false,
    ): Iap2UsbSession {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        // 1.0.2：重枚举 attach 后给 iPhone 组态切换一个落定窗口（MIUI 实测立即
        // setConfiguration/claim 会失败），再尝试；claim 仍失败则整连接重开重试一次。
        var lastError: IphoneUsbException? = null
        repeat(OPEN_IAP2_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                report("usbmux open retry #$attempt after claim failure")
                Thread.sleep(RETRY_DELAY_MILLIS)
            } else {
                Thread.sleep(CONFIG_SETTLE_DELAY_MILLIS)
            }
            try {
                return openIap2UsbSessionOnce(device, attempt, assumeAlreadyConfigured)
            } catch (error: IphoneUsbException) {
                // 配置缺失类错误重试也没用（iPhone 没切模式），直接抛可行动文案。
                if (error.message?.contains("no CarPlay USB configuration") == true) throw error
                lastError = error
            }
        }
        throw lastError ?: IphoneUsbException.DeviceUnavailable("could not open iPhone USB data paths")
    }

    private fun openIap2UsbSessionOnce(
        device: UsbDevice,
        attempt: Int,
        assumeAlreadyConfigured: Boolean = false,
    ): Iap2UsbSession =
        runOwnedConnection(device) { connection ->
            // API16 适配：UsbConfiguration/UsbDevice.getConfiguration/setConfiguration 都是
            // API 21+；< 21 只能扫默认配置的 device.getInterface()，找不到就明确报错。
            val usbMux = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val configuration = IphoneCarPlayConfiguration.find(device)
                if (configuration == null) {
                    // 1.0.2：错误拆分——组态缺失说明 iPhone 没应用 0x52 模式切换，重试无意义。
                    report("attempt#$attempt topology has no CarPlay configuration: " +
                        IphoneCarPlayConfiguration.describeDevice(device))
                    throw IphoneUsbException.Protocol(
                        "Re-enumerated iPhone exposes no CarPlay USB configuration",
                    )
                }
                if (attempt == 0 && assumeAlreadyConfigured) {
                    // FE-6（ECARX API22）楔死自愈实测：同一次插线内 iPhone 从未离开 CarPlay
                    // 配置，此时再 setConfiguration 会触发一次多余的 re-enumerate，把刚拿到的
                    // USB 权限作废（grant 绑定 UsbDevice 实例）→ "permission was denied" 误报
                    // 终态。自愈重试的首跳直接跳过选配，claim 失败再由下一跳走完整路径兜底。
                    report(
                        "attempt#$attempt skipping setConfiguration: iPhone already in " +
                            "CarPlay configuration (self-heal retry, config id=${configuration.id})",
                    )
                } else {
                    report("attempt#$attempt target CarPlay configuration id=${configuration.id}")
                    val switched = connection.setConfiguration(configuration)
                    report("attempt#$attempt setConfiguration(${configuration.id}) success=$switched")
                    if (!switched) {
                        // 1.0.21（3e 实测 195341 日志）：设备已在目标配置时 EMUI setConfiguration 返回
                        // false（0x4301 重启代际场景：iPhone 已处于 CarPlay 配置，无重枚举）。
                        // 不再立即失败——继续走接口发现/claim，接口真不在再由 claim 路径报错。
                        report("attempt#$attempt switch returned false; assuming already in CarPlay configuration and continuing")
                    }
                }
                IphoneCarPlayConfiguration.usbMuxInterface(configuration)
                    ?: throw IphoneUsbException.Protocol("CarPlay configuration exposes no USBMUX interface")
            } else {
                IphoneCarPlayConfiguration.usbMuxInterfaceLegacy(device)
                    ?: throw IphoneUsbException.Protocol(
                        "CarPlay configuration exposes no USBMUX interface on this Android version",
                    )
            }
            val endpoints = IphoneCarPlayConfiguration.usbMuxEndpoints(usbMux)
                ?: throw IphoneUsbException.Protocol("USBMUX interface exposes no bulk endpoint pair")
            report(
                "attempt#$attempt usbmux iface=${usbMux.id} " +
                    // getAlternateSetting 是 API 24+，低版本车机（at8317 实测 API17
                    // NoSuchMethodError）固定打 -；诊断用途不影响功能。
                    "alt=${if (Build.VERSION.SDK_INT >= 24) usbMux.alternateSetting else -1} " +
                    "out=0x${endpoints.first.address.toString(16)} in=0x${endpoints.second.address.toString(16)}",
            )
            // vc106（AC822X 175935 实测定案）：vc103r 共享 fd 后 usbmux claim 先于 NCM
            // 配置对齐执行，内核按"本 fd 已有 claim"判 EBUSY（suspend release 还失败
            // ok=false），配置 6 从未切过去（kernelAligned=false）→ NCM 数据面死 →
            // AirPlay attach 无网。对齐 Legacy 顺序：在 usbmux claim 之前的全新 fd 上
            // （零 claim 可挡）直接 native SETCONFIGURATION(6)（用户定案硬编码 6）+
            // GET_CONFIGURATION 回读；之后 NCM 发现层按内核活动配置对齐（vc103o 护栏
            // 自然跳过重复切配置），EBUSY 分支整体不再走到。
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT && LegacyUsbHostNative.available) {
                val preFd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
                if (preFd >= 0) {
                    // vc111（T3 两次整机死锁定案）：KK sunxi 内核在重枚举未收敛时收到
                    // SETCONFIGURATION ioctl 会死锁（卡死点=ioctl 内部，与 legacy 同款，
                    // 随机竞速）。发 ioctl 前先等枚举收敛：GET_CONFIGURATION 是内核态纯读
                    // （usbfs 缓存，不发总线事务）零风险，连续两次读数一致 + 至少
                    // CONVERGE_SETTLE_MILLIS 落定窗才算收敛；已收敛且活动配置=目标值则
                    // 完全跳过 ioctl（vc109 定稿方案）。
                    val preReadback0 = NcmUsbBridge.kernelActiveConfiguration(connection)
                    var converged = false
                    var settleStart = 0L
                    var lastRead = preReadback0
                    val convergeDeadline = System.currentTimeMillis() + CONVERGE_TIMEOUT_MILLIS
                    while (System.currentTimeMillis() < convergeDeadline) {
                        Thread.sleep(CONVERGE_POLL_MILLIS)
                        val now = NcmUsbBridge.kernelActiveConfiguration(connection)
                        if (now != null && now == lastRead && now > 0) {
                            if (settleStart == 0L) settleStart = System.currentTimeMillis()
                            if (System.currentTimeMillis() - settleStart >= CONVERGE_SETTLE_MILLIS) {
                                converged = true
                                break
                            }
                        } else {
                            settleStart = 0L
                        }
                        lastRead = now
                    }
                    NcmClaimTrace.trace(
                        "vc111 converge wait: initial=$preReadback0 settled=$lastRead converged=$converged",
                    )
                    report(
                        "vc111 converge wait: initial=$preReadback0 settled=$lastRead converged=$converged",
                    )
                    if (converged && lastRead == CARPLAY_USB_CONFIG_VALUE) {
                        NcmClaimTrace.trace(
                            "vc111 pre-usbmux-claim skip setConfiguration: readback=$lastRead==target",
                        )
                        report(
                            "vc111 pre-usbmux-claim skip setConfiguration: readback=$lastRead==target",
                        )
                    } else {
                        val preErrno = try {
                            LegacyUsbHostNative.setConfiguration(preFd, CARPLAY_USB_CONFIG_VALUE)
                        } catch (_: LinkageError) {
                            -1
                        }
                        val preReadback = NcmUsbBridge.kernelActiveConfiguration(connection)
                        NcmClaimTrace.trace(
                            "vc111 pre-usbmux-claim setConfiguration($CARPLAY_USB_CONFIG_VALUE)" +
                                " errno=$preErrno readback=$preReadback",
                        )
                        report(
                            "vc111 pre-usbmux-claim setConfiguration($CARPLAY_USB_CONFIG_VALUE)" +
                                " errno=$preErrno readback=$preReadback",
                        )
                    }
                }
            }
            if (!connection.claimInterface(usbMux, true)) {
                throw IphoneUsbException.DeviceUnavailable(
                    "Android could not claim USBMUX interface ${usbMux.id} (attempt#$attempt)",
                )
            }
            report("attempt#$attempt usbmux interface claimed")
            // claim 生命周期由 Iap2UsbSession（close 释放接口+关连接）接管。
            // vc103q：传入接口对象供 suspend/resume claim（NCM 强切配置时临时让路）。
            Iap2UsbSession(connection, endpoints.first, endpoints.second, usbMux, debugSink)
        }

    /**
     * 1.0.2：连接所有权辅助——lambda 内抛错时关闭连接并向外抛；
     * 成功时连接的所有权移交返回的 Iap2UsbSession（其 close 负责关连接）。
     */
    private inline fun <T> runOwnedConnection(device: UsbDevice, block: (UsbDeviceConnection) -> T): T {
        val connection = openConnection(device)
        try {
            return block(connection)
        } catch (error: Throwable) {
            runCatching { connection.close() }
            throw error
        }
    }

    private fun requireConfiguredDevice(device: UsbDevice) {
        if (!matcher.matches(device.vendorId, device.productId)) {
            throw IphoneUsbException.DeviceUnavailable("USB device is not a configured iPhone identity")
        }
    }

    /**
     * 打开 iPhone 连接。openDevice 返回 null 的常见原因是传入的 UsbDevice 对象已过期
     * （iPhone 重新枚举后系统列表里已是新实例，EMUI 上权限广播到达时尤甚）——
     * 此时按 VID/PID 从当前设备列表重新解析并重试一次；仍失败则带诊断细节抛错。
     */
    private fun openConnection(device: UsbDevice): UsbDeviceConnection {
        usbManager.openDevice(device)?.let { return it }
        val fresh = usbManager.deviceList.values.firstOrNull {
            it.vendorId == device.vendorId && it.productId == device.productId
        }
        Log.w(
            "IphoneUsbHost",
            "openDevice(stale?) returned null for ${device.deviceName} pid=0x" +
                device.productId.toString(16) +
                "; fresh=${fresh?.deviceName ?: "absent"} list=" +
                usbManager.deviceList.values.joinToString(",") { it.deviceName + ":0x" + it.productId.toString(16) },
        )
        if (fresh != null) {
            usbManager.openDevice(fresh)?.let { return it }
        }
        throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
    }

    private fun permissionPendingIntent(): PendingIntent {
        val intent = Intent(permissionAction).setPackage(appContext.packageName)
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )
    }

    private fun registerReceiver(filter: IntentFilter, onReceive: (Intent) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onReceive(intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        val registered = AtomicBoolean(true)
        return Closeable {
            if (registered.compareAndSet(true, false)) appContext.unregisterReceiver(receiver)
        }
    }

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    companion object {
        private const val USB_VENDOR_DEVICE_IN = 0xc0
        private const val CARPLAY_CONFIGURATION_REQUEST = 0x52
        private const val CARPLAY_CONFIGURATION_INDEX = 0x0004
        private const val VENDOR_RESPONSE_LENGTH = 1
        // vc106：CarPlay 配置值（用户定案硬编码，T3/AC822X 实测该值承载 NCM 功能）
        private const val CARPLAY_USB_CONFIG_VALUE = 6
        // vc111（T3 死锁防护）：重枚举收敛等待参数。
        private const val CONVERGE_POLL_MILLIS = 100L
        private const val CONVERGE_SETTLE_MILLIS = 300L
        private const val CONVERGE_TIMEOUT_MILLIS = 5000L
        private const val CONTROL_TRANSFER_TIMEOUT_MILLIS = 1_000

        /**
         * vc188：0x52（CarPlay 模式切换）厂商请求在本 attempt 内的尝试次数与退避。
         *
         * 依据：222631 六轮连接的 0x52 都在 **~4ms** 内返回 -1（远小于 1s 超时），
         * 说明不是「iPhone 响应慢/超时」，而是**立即失败**——典型是连到了
         * deviceList 里残留的**陈旧实例**（KK 上旧实例会残留 1~3s）导致句柄不可用
         * （ENODEV/EPIPE）。加短 settle 重试可覆盖首次 open 与重枚举的竞态。
         *
         * ⚠️ 这是**防御性增强，不是确诊修复**：若 iPhone 侧 USB 态真的卡死，重试无效，
         * 仍需外测换线 / 重启 iPhone。日志会区分「全败」与「重试后成功」以便二分。
         */
        private const val CARPLAY_CONFIG_MAX_ATTEMPTS = 3
        private const val CARPLAY_CONFIG_RETRY_DELAY_MILLIS = 400

        // 1.0.2：USBMUX 会话打开的落定等待与重试——重枚举 attach 后立即
        // setConfiguration/claim 在 MIUI 上会失败（claim 返回 false）。
        private const val OPEN_IAP2_ATTEMPTS = 2
        private const val CONFIG_SETTLE_DELAY_MILLIS = 800L
        private const val RETRY_DELAY_MILLIS = 300L
    }

}

/**
 * A blocking, full-duplex USBMUX pipe. A null [read] result means only that its timeout elapsed.
 *
 * All operations must run off the Android main thread. The session does not parse iAP2 frames.
 */
class Iap2UsbSession internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val usbMuxInterface: UsbInterface?,
    private val debugSink: ((String) -> Unit)? = null,
) : Closeable {
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false

    /**
     * vc103q（T3 终审）：NCM 打开前强切 CarPlay 配置时，usbfs 全设备级 EBUSY 由本会话的
     * usbmux claim 挡路（跨 fd 无法释放，只能本 fd 自己放）。挂起=临时 releaseInterface，
     * 恢复=重新 claim（force=true）+ SET_INTERFACE(1,2)（配置 6 下 usbmux 0x04/0x85 端点
     * 在接口 1 的 alt-2，重 claim 后必须显式切 alt 才能继续收发）。
     * 挂起期间读写会失败——仅在 NCM 打开的同步窗口内使用，窗口外禁止调用。
     */
    fun suspendUsbmuxClaim(): Boolean {
        val usbInterface = usbMuxInterface ?: return false
        return try {
            connection.releaseInterface(usbInterface).also { ok ->
                debugSink?.invoke("vc103q suspendUsbmuxClaim releaseInterface(${usbInterface.id}) ok=$ok")
            }
        } catch (error: Throwable) {
            debugSink?.invoke("vc103q suspendUsbmuxClaim error=${error.message}")
            false
        }
    }

    /**
     * vc103r（Legacy 对齐）：NCM 复用本会话的同一 USB 连接 fd——Legacy 要求 usbmux 与
     * NCM 共享 fd（内核 claim 位图按 fd 记账，双 fd 状态割裂；KK 第二 fd claim 恒 ENOENT）。
     * 仅供 transport/orchestration 内部使用；连接生命周期仍归本会话（close 时统一关闭）。
     */
    internal fun sharedConnection(): UsbDeviceConnection = connection

    fun resumeUsbmuxClaim(): Boolean {
        val usbInterface = usbMuxInterface ?: return false
        return try {
            val claimed = connection.claimInterface(usbInterface, true)
            if (claimed && LegacyUsbHostNative.available) {
                val fd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
                if (fd >= 0) {
                    val errno = LegacyUsbHostNative.setInterface(fd, usbInterface.id, 2)
                    debugSink?.invoke("vc103q resumeUsbmuxClaim SET_INTERFACE(${usbInterface.id},2) errno=$errno")
                }
            }
            debugSink?.invoke("vc103q resumeUsbmuxClaim claimInterface(${usbInterface.id}) ok=$claimed")
            claimed
        } catch (error: Throwable) {
            debugSink?.invoke("vc103q resumeUsbmuxClaim error=${error.message}")
            false
        }
    }

    /** 1.0.5：EMUI 等 ROM 的异步读（requestWait(timeout)）不可用时置位，读全部走同步。 */
    @Volatile private var asyncReadBroken = false
    private var failure: IphoneUsbException? = null
    private var pendingRead: UsbRequest? = null

    fun write(data: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (data.isEmpty()) return@synchronized
        val transferred = connection.bulkTransfer(outEndpoint, data, data.size, timeoutMillis)
        if (transferred != data.size) {
            throw IphoneUsbException.DeviceUnavailable(
                "USBMUX write transferred $transferred of ${data.size} bytes",
            )
        }
    }

    /** Returns null only when no completed USB request arrives before [timeoutMillis]. */
    fun read(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        // API16 适配：requestWait(timeout)/UsbRequest.queue(buffer)/UsbRequest.close 都是
        // API 26+。>= 26 走上游异步路径；< 26 退化为同步 bulkTransfer（USBMUX 控制面
        // 速率低，可接受），超时/失败统一按"未收到数据"返回 null。
        // 1.0.5：EMUI9 实测异步路径（requestWait(timeout)）抛 RuntimeException 导致
        // "USBMUX read failed"——捕获后标记降级，本次与后续读都走同步 bulkTransfer。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !asyncReadBroken) {
            try {
                return readAsync(timeoutMillis)
            } catch (error: IphoneUsbException) {
                if (closed) throw error
                asyncReadBroken = true
                debugSink?.invoke(
                    "USBMUX async read unavailable (${error.message}); falling back to sync bulkTransfer",
                )
            }
        }
        return readSync(timeoutMillis)
    }

    private fun readSync(timeoutMillis: Long): ByteArray? {
        val buffer = ByteArray(USBMUX_READ_CHUNK_BYTES)
        val transferred = try {
            connection.bulkTransfer(inEndpoint, buffer, buffer.size, timeoutMillis.coerceAtLeast(1).toInt())
        } catch (error: RuntimeException) {
            throw failSession("USBMUX read failed", error)
        }
        if (transferred <= 0) return null
        return buffer.copyOf(transferred)
    }

    private fun readAsync(timeoutMillis: Long): ByteArray? {
        val request = UsbRequest()
        var initialized = false
        try {
            if (!request.initialize(connection, inEndpoint)) {
                throw IphoneUsbException.DeviceUnavailable("Android could not initialize USBMUX read request")
            }
            initialized = true
            synchronized(stateLock) {
                checkOpenLocked()
                pendingRead = request
            }
            val buffer = ByteBuffer.allocateDirect(USBMUX_READ_CHUNK_BYTES)
            if (!request.queue(buffer)) {
                throw IphoneUsbException.DeviceUnavailable("Android could not queue USBMUX read request")
            }
            val completed = try {
                connection.requestWait(timeoutMillis)
            } catch (_: TimeoutException) {
                drainCancelledRead(request)
                return null
            }
            if (completed == null) {
                // 1.0.5：不再 failSession——交由 read() 降级同步路径处理。
                throw IphoneUsbException.DeviceUnavailable("Android returned no USBMUX read request")
            }
            if (completed !== request) {
                throw IphoneUsbException.DeviceUnavailable("Android completed an unexpected USB request")
            }
            return ByteArray(buffer.position()).also {
                buffer.flip()
                buffer.get(it)
            }
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            // 1.0.5：EMUI9 的 requestWait(timeout) 兼容层实测抛 RuntimeException——
            // 包装后交由 read() 降级同步 bulkTransfer，不再直接判死会话。
            throw IphoneUsbException.DeviceUnavailable(
                "USBMUX async read error: ${error.javaClass.simpleName}: ${error.message}",
                error,
            )
        } finally {
            synchronized(stateLock) {
                if (pendingRead === request) pendingRead = null
            }
            if (initialized) request.cancel()
            request.close()
        }
    }

    override fun close() {
        val requestToCancel = synchronized(stateLock) {
            if (closed) return
            closed = true
            pendingRead
        }
        requestToCancel?.cancel()
        connection.close()
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX session is closed")
    }

    private fun drainCancelledRead(request: UsbRequest) {
        if (!request.cancel()) {
            throw failSession("Android could not cancel timed out USBMUX read request")
        }
        val completed = try {
            connection.requestWait(CANCEL_DRAIN_TIMEOUT_MILLIS)
        } catch (_: TimeoutException) {
            throw failSession("Timed out draining cancelled USBMUX read request")
        }
        if (completed !== request) {
            throw failSession("Android did not drain the cancelled USBMUX read request")
        }
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private companion object {
        // 1.0.6：usbfs 单次 bulk 请求上限 16384 字节（实测 Android9/EMUI queue 与
        // bulkTransfer 超限即抛/返回负），读缓冲不得超过该值。
        const val USBMUX_READ_CHUNK_BYTES = 16_384
        const val CANCEL_DRAIN_TIMEOUT_MILLIS = 1_000L
    }
}
