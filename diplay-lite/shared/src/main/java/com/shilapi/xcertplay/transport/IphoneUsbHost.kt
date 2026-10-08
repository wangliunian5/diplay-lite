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
                val response = ByteArray(VENDOR_RESPONSE_LENGTH)
                val transferred = connection.controlTransfer(
                    USB_VENDOR_DEVICE_IN,
                    CARPLAY_CONFIGURATION_REQUEST,
                    0,
                    CARPLAY_CONFIGURATION_INDEX,
                    response,
                    response.size,
                    CONTROL_TRANSFER_TIMEOUT_MILLIS,
                )
                if (transferred != response.size) {
                    throw IphoneUsbException.Protocol(
                        "CarPlay configuration request transferred $transferred of ${response.size} bytes",
                    )
                }
                TransitionResult.ReenumerationRequested(response[0].toInt() and 0xff)
            })
        }
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
    ) {
        executor.execute {
            val result = try {
                Iap2SessionResult.Connected(openIap2UsbSession(device))
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

    private fun openIap2UsbSession(device: UsbDevice): Iap2UsbSession {
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
                return openIap2UsbSessionOnce(device, attempt)
            } catch (error: IphoneUsbException) {
                // 配置缺失类错误重试也没用（iPhone 没切模式），直接抛可行动文案。
                if (error.message?.contains("no CarPlay USB configuration") == true) throw error
                lastError = error
            }
        }
        throw lastError ?: IphoneUsbException.DeviceUnavailable("could not open iPhone USB data paths")
    }

    private fun openIap2UsbSessionOnce(device: UsbDevice, attempt: Int): Iap2UsbSession =
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
                report("attempt#$attempt target CarPlay configuration id=${configuration.id}")
                val switched = connection.setConfiguration(configuration)
                report("attempt#$attempt setConfiguration(${configuration.id}) success=$switched")
                if (!switched) {
                    // 1.0.21（3e 实测 195341 日志）：设备已在目标配置时 EMUI setConfiguration 返回
                    // false（0x4301 重启代际场景：iPhone 已处于 CarPlay 配置，无重枚举）。
                    // 不再立即失败——继续走接口发现/claim，接口真不在再由 claim 路径报错。
                    report("attempt#$attempt switch returned false; assuming already in CarPlay configuration and continuing")
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
            if (!connection.claimInterface(usbMux, true)) {
                throw IphoneUsbException.DeviceUnavailable(
                    "Android could not claim USBMUX interface ${usbMux.id} (attempt#$attempt)",
                )
            }
            report("attempt#$attempt usbmux interface claimed")
            // claim 生命周期由 Iap2UsbSession（close 释放接口+关连接）接管。
            Iap2UsbSession(connection, endpoints.first, endpoints.second, debugSink)
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
        private const val CONTROL_TRANSFER_TIMEOUT_MILLIS = 1_000

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
    private val debugSink: ((String) -> Unit)? = null,
) : Closeable {
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false

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
