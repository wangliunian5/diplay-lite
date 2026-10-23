package com.shilapi.xcertplay.usbbt

/**
 * vc181（外置 USB 蓝牙 C/D 批）：iAP2 通道接入口。
 * 移植自上游参考实现 usbbt.UsbBtIap2Link.java（89 行）。
 *
 * host 侧每建好一条 RFCOMM DLC 就 [offer] 进来；D2 侧 [take] 阻塞等到
 * 第一条可用 DLC（带超时）。current 已有活通道时新 offer 一律关闭丢弃；
 * [reset] 全关并唤醒等待者（reason = usb bluetooth stopped）。
 */
internal class UsbBtIap2Link {
    private val gate = Object()

    private var current: RfcommDlc? = null

    private var waiting: RfcommDlc? = null

    /** 当前是否有活通道。 */
    fun isOpen(): Boolean = synchronized(gate) { current?.isOpen() == true }

    /** host 交出一条新 DLC：占用者已活着则关掉新的，否则接管并唤醒 take。 */
    fun offer(dlc: RfcommDlc) {
        synchronized(gate) {
            val existing = current
            if (existing != null && existing.isOpen()) {
                dlc.close()
                return
            }
            waiting = dlc
            current = dlc
            gate.notifyAll()
        }
    }

    /** 阻塞等一条可用 DLC；超时返回 null。 */
    fun take(timeoutMillis: Long): RfcommDlc? {
        synchronized(gate) {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (true) {
                val candidate = waiting
                if (candidate != null && candidate.isOpen()) {
                    waiting = null
                    return candidate
                }
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) {
                    return null
                }
                gate.wait(remain)
            }
        }
    }

    /** 全部关闭并唤醒等待者（host 停止/失败路径）。 */
    fun reset() {
        synchronized(gate) {
            waiting = null
            current?.close()
            current = null
            gate.notifyAll()
        }
    }
}
