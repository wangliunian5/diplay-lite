package com.shilapi.xcertplay.root

import android.os.SystemClock

/**
 * vc199：**全项目唯一的 su 执行器**。
 *
 * ## 为什么必须有这一个类
 *
 * 历史上同一个功能写过三份，各写各的，结果两份对、一份错：
 *
 * | 位置 | 超时写法 | 结果 |
 * | --- | --- | --- |
 * | `Fe6VoiceProbe.suExec` | 轮询 `exitValue()` + `Thread.sleep(50)` | ✅ 实机 `uid=0(root)` |
 * | `WiredUsbForensics.suExec` | 轮询 `exitValue()` + `sleep(100)` | ✅ 实机抓到 root dmesg |
 * | `Fe6RootMicSource.suExec`（vc198 新写） | `Process.waitFor(long, TimeUnit)` | ❌ 28 次全灭 |
 *
 * 第三份踩的坑：`Process#waitFor(long, TimeUnit)` 是 Java 8 API，**Android 只有 API 26+ 才有**。
 * 在 api22（FE6 是 Android 5.1）上调用直接抛 `NoSuchMethodError`，而它被
 * `runCatching { ... }.getOrDefault(false)` 吞成 `false`，于是：
 *
 * ```
 * su -c id  exit=-1  21ms  out={}  err={(等待授权超时 20000ms)}
 * ```
 *
 * —— 21 毫秒就返回，却写着"等待授权超时 20000ms"。日志上看起来像"没授权"，实际是
 * 我们连 su 都没等，进程刚起来就被 `destroy()` 了。
 *
 * ## 本类的两条纪律
 *
 * 1. **超时一律用轮询 `exitValue()`**（api16 起可用），绝不用 `waitFor(long, TimeUnit)`，
 *    也不用 `waitFor()` 无参（它会无限阻塞，su 等授权弹窗时能挂死整条线程）。
 * 2. **异常一律记类名 + message 并带出去**，绝不 `getOrDefault` 静默成业务结论。
 *    `NoSuchMethodError` 是 `Error` 不是 `Exception`，`catch (e: Exception)` 也漏得掉，
 *    所以统一 `catch (e: Throwable)` 且把 `e.javaClass.simpleName` 写进 [Result.waitError]。
 */
object SuShell {

    /** 轮询间隔。30ms 对"等授权弹窗"这种秒级等待足够细，又不会烧 CPU。 */
    const val POLL_INTERVAL_MS = 30L

    /** 单条流最多留 8K 字符。su 的输出（id / ls -l / dmesg）远小于此，防的是误接大输出。 */
    private const val MAX_CAPTURE_CHARS = 8_000

    /**
     * 一次 su 调用的完整结果。
     *
     * 判读顺序很重要：
     * 1. [launchError] 非空 ⇒ 进程根本没起来（su 不在这个路径 / 不可执行）。**换下一个候选路径**。
     * 2. [waitError] 非空 ⇒ 等待期间抛异常 ⇒ **这是我们自己的代码 bug，不是授权问题**。
     * 3. [timedOut] ⇒ 真等满了 timeout 还没退出（su 在等授权弹窗、或被 SELinux 卡住）。
     * 4. 否则看 [exitCode] + [stdout]：`uid=0` / `0` 才是真 root。
     */
    class Result(
        val argv: List<String>,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val elapsedMs: Long,
        /** 进程启动失败（IOException/ENOENT/不可执行），值形如 `IOException: ...`。 */
        val launchError: String? = null,
        /** 等待/取退出码时抛的异常（**出现即代表本模块有 bug**，值形如 `NoSuchMethodError: ...`）。 */
        val waitError: String? = null,
        /** 真等满了 timeout。 */
        val timedOut: Boolean = false,
    ) {
        val launched: Boolean get() = launchError == null

        /** 进程跑完并拿到了退出码（不管退出码是几）。 */
        val finished: Boolean get() = launched && waitError == null && !timedOut

        /** 退出码 0。 */
        val ok: Boolean get() = finished && exitCode == 0

        /**
         * 授权成功。三种 su 实现的输出形态都认：
         * · 标准 `id` → `uid=0(root) gid=0(root) ...`
         * · 某些 su 只回数字 → 单独一行 `0`
         */
        val isRoot: Boolean
            get() = finished && (
                stdout.contains("uid=0") ||
                    stdout.lineSequence().any { it.trim() == "0" }
                )

        /** 稳定的单行摘要，直接进日志。 */
        fun summary(): String = buildString {
            append('`').append(argv.joinToString(" ")).append("` ")
            when {
                launchError != null -> append("启动失败 ").append(launchError)
                waitError != null -> append("调用异常 ").append(waitError)
                timedOut -> append("等待超时 ").append(elapsedMs).append("ms")
                else -> append("exit=").append(exitCode).append(' ').append(elapsedMs).append("ms")
            }
            append(" out={").append(stdout.trim().replace('\n', ' ').take(120)).append('}')
            if (stderr.isNotBlank()) {
                append(" err={").append(stderr.trim().replace('\n', ' ').take(120)).append('}')
            }
        }
    }

    /**
     * 跑一条命令并等它结束。
     *
     * @param argv 完整命令行（首元素是可执行文件）。**这里不做 `su -c` 拼接** ——
     *   `su` 的路径因 SuperSU/Magisk/systemless 而异，由调用方决定（见 [su]）。
     * @param timeoutMs 最长等待。到点 `destroy()` 并置 `timedOut=true`。
     * @param mergeError 是否把 stderr 并进 stdout。老 SuperSU 有时把错误写在 stderr、
     *   成功信息写在 stdout，需要一起看的场景（如 `ls -l` 验证）就传 true。
     */
    fun run(argv: List<String>, timeoutMs: Long, mergeError: Boolean = false): Result {
        val started = SystemClock.elapsedRealtime()
        val proc: Process = try {
            ProcessBuilder(argv).apply {
                if (mergeError) redirectErrorStream(true)
            }.start()
        } catch (error: Throwable) {
            return Result(
                argv = argv,
                exitCode = -1,
                stdout = "",
                stderr = "",
                elapsedMs = SystemClock.elapsedRealtime() - started,
                launchError = describe(error),
            )
        }

        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = Thread({
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { if (out.length < MAX_CAPTURE_CHARS) out.append(it).append('\n') }
                }
            }
        }, "sushell-out").apply { isDaemon = true; start() }

        // mergeError 时 stderr 已并进 stdout，不重复读（重复读会抢同一个 fd）。
        val errThread = if (mergeError) {
            null
        } else {
            Thread({
                runCatching {
                    proc.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { if (err.length < MAX_CAPTURE_CHARS) err.append(it).append('\n') }
                    }
                }
            }, "sushell-err").apply { isDaemon = true; start() }
        }

        // ── 轮询等退出（这是全类的核心：不用 waitFor(timeout, unit)）──
        var timedOut = true
        var waitError: String? = null
        var waited = 0L
        val limit = maxOf(timeoutMs, POLL_INTERVAL_MS)
        while (waited < limit) {
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                waitError = "轮询被中断"
                break
            }
            waited += POLL_INTERVAL_MS
            try {
                proc.exitValue()
                timedOut = false
                break
            } catch (_: IllegalThreadStateException) {
                // 还在跑，继续等 —— 这就是「正常路径」。
            } catch (error: Throwable) {
                // exitValue() 本身抛出意外异常（比如某些 ROM 的实现问题）：记下来，别再吞。
                waitError = describe(error)
                break
            }
        }
        if (timedOut) runCatching { proc.destroy() }
        runCatching { outThread.join(500L) }
        errThread?.let { runCatching { it.join(500L) } }

        val exit = if (timedOut) -1 else runCatching { proc.exitValue() }.getOrDefault(-1)
        val stderrText = buildString {
            append(err)
            if (timedOut) append("\n(等待超时 ${timeoutMs}ms：su 进程未退出，已 destroy)")
            if (waitError != null) append("\n(调用异常 $waitError)")
        }.trim()

        return Result(
            argv = argv,
            exitCode = exit,
            stdout = out.toString(),
            stderr = stderrText,
            elapsedMs = SystemClock.elapsedRealtime() - started,
            launchError = null,
            waitError = waitError,
            timedOut = timedOut,
        )
    }

    /**
     * `su -c "<command>"` 的便捷包装。
     *
     * 命令串必须**整串作为一个参数**传，否则老 su 只吃第一个词（T3 SuperSU 的已知行为）。
     * [binary] 可指定绝对路径；默认 `su`（走 PATH）。
     */
    fun su(
        command: String,
        timeoutMs: Long,
        mergeError: Boolean = true,
        binary: String = "su",
    ): Result = run(listOf(binary, "-c", command), timeoutMs, mergeError)

    /** 异常描述统一成 `类名: message`。**类名一定要留**，否则 `NoSuchMethodError` 这类看不出是代码 bug。 */
    private fun describe(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message}"
}
