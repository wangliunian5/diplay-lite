package com.shilapi.xcertplay.vehicle

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.util.Log
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration

/**
 * vc97：已配对蓝牙设备多源枚举——ECARX 平台 getBondedDevices() 失灵族第三个症状
 * （isEnabled 失灵、state=11 之后），FE-6 20261017 实测：iPhone 已与车机配对，
 * adapter.bondedDevices 返回空 → 无线自动匹配「检测不到」+ 选择弹窗只剩手动输入。
 *
 * 对齐「多证据源交叉验证，不做机型识别分支」定案：
 *   源A adapter.bondedDevices——标准路径，绝大多数平台直接命中；
 *   源B root 读 /data/misc/bluedroid/bt_config.conf（含 .bak）——bluedroid 本机
 *      配置持久化，5.x 格式为 [Remote] 下 [MAC] 节 + Name= 键。FE-6 有 root
 *      （usbfx 探针实测 su 可用），此源在其上必活。
 * 两源合并按 MAC 去重；源 B 解析出的地址经 adapter.getRemoteDevice 还原成
 * BluetoothDevice（无需已配对即可构造，RFCOMM 建链照常）。
 *
 * su 执行纪律对齐 vc94 A8：pump 线程收流 + exitValue 轮询（waitFor(timeout)
 * 需 API24+），5s 无果 destroy 按失败处理。
 */
object BondedDevicesResolver {
    private const val TAG = IphoneCarPlayConfiguration.TAG

    data class Entry(val address: String, val name: String?, val source: String)

    /** 标准 + root 兜底合并；每源产出记一行日志便于远端取证。 */
    fun resolve(adapter: BluetoothAdapter?, log: (String) -> Unit = {}): List<Entry> {
        val merged = LinkedHashMap<String, Entry>()
        // vc151（targetSdk 34）：API 31+ 无 BLUETOOTH_CONNECT 运行时权限时 bondedDevices
        // 抛 SecurityException（K60 实测闪退）——拒绝授权时降级为空，走源 B root 路径。
        val adapterEntries = runCatching {
            adapter?.bondedDevices.orEmpty().map {
                Entry(it.address.uppercase(), it.name, "adapter")
            }
        }.getOrElse {
            log("bonded resolve: adapter source blocked (${it.javaClass.simpleName})")
            emptyList()
        }
        adapterEntries.forEach { merged[it.address] = it }
        log("bonded resolve: adapter=${adapterEntries.size} devices")

        if (adapterEntries.isEmpty()) {
            val rootEntries = resolveViaRootConfig(log)
            rootEntries.forEach { merged.putIfAbsent(it.address, it) }
            log("bonded resolve: root-config=${rootEntries.size} devices (merged=${merged.size})")
        }
        return merged.values.toList()
    }

    /** 还原成 BluetoothDevice（getRemoteDevice 对非配对地址也可用）。 */
    fun resolveDevices(adapter: BluetoothAdapter?, log: (String) -> Unit = {}): List<BluetoothDevice> {
        val devices = LinkedHashSet<BluetoothDevice>()
        adapter?.bondedDevices?.let { devices.addAll(it) }
        if (devices.isEmpty()) {
            resolve(adapter, log).forEach { entry ->
                runCatching { adapter?.getRemoteDevice(entry.address) }
                    .getOrNull()
                    ?.let { devices.add(it) }
                    ?: log("bonded resolve: getRemoteDevice failed for ${entry.address}")
            }
        }
        return devices.toList()
    }

    private fun resolveViaRootConfig(log: (String) -> Unit): List<Entry> {
        val entries = mutableListOf<Entry>()
        for (path in listOf("/data/misc/bluedroid/bt_config.conf", "/data/misc/bluedroid/bt_config.bak")) {
            val output = readRootFile(path, log)
            if (output.isNullOrBlank()) continue
            val parsed = parseBluedroidConfig(output, path)
            log("bonded resolve: $path parsed=${parsed.size} devices")
            if (parsed.isNotEmpty()) {
                entries.addAll(parsed)
                break
            }
        }
        return entries
    }

    private fun readRootFile(path: String, log: (String) -> Unit): String? {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", "cat '$path'").start()
            // 文件 <16KB 单捕获线程即可；vc94 A8 纪律：exitValue 轮询防 API16 无 waitFor(timeout)
            var result: String? = null
            val capture = Thread({
                result = try {
                    process.inputStream.bufferedReader().readText()
                } catch (_: Exception) {
                    null
                }
            }, "bonded-resolve-su").apply { start() }
            var exit: Int? = null
            var waited = 0L
            while (exit == null && waited < SU_TIMEOUT_MILLIS) {
                Thread.sleep(100)
                waited += 100
                exit = try {
                    process.exitValue()
                } catch (_: IllegalThreadStateException) {
                    null
                }
            }
            if (exit == null) {
                process.destroy()
                log("bonded resolve: su cat timed out (${SU_TIMEOUT_MILLIS}ms)")
                return null
            }
            capture.join(500)
            result
        } catch (error: Exception) {
            log("bonded resolve: su cat failed (${error.javaClass.simpleName}: ${error.message})")
            null
        } finally {
            runCatching { process?.destroy() }
        }
    }

    /** [Remote] 节下 [MAC] 子节 + Name= 键；MAC 正则宽容匹配节头。 */
    private fun parseBluedroidConfig(text: String, source: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        var currentAddress: String? = null
        var currentName: String? = null
        val macRegex = Regex("^\\[([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})]\\s*$")
        val nameRegex = Regex("^Name\\s*=\\s*(.+?)\\s*$")
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val macMatch = macRegex.find(line)
            if (macMatch != null) {
                currentAddress?.let { entries.add(Entry(it.uppercase(), currentName, source)) }
                currentAddress = macMatch.groupValues[1]
                currentName = null
                continue
            }
            if (line == "[Local]" || line == "[AdapterOptions]") {
                currentAddress?.let { entries.add(Entry(it.uppercase(), currentName, source)) }
                currentAddress = null
                currentName = null
                continue
            }
            val nameMatch = nameRegex.find(line)
            if (nameMatch != null && currentAddress != null && currentName.isNullOrBlank()) {
                currentName = nameMatch.groupValues[1]
            }
        }
        currentAddress?.let { entries.add(Entry(it.uppercase(), currentName, source)) }
        return entries.filter { !it.address.equals("00:00:00:00:00:00", true) }
    }

    private const val SU_TIMEOUT_MILLIS = 5_000L
}
