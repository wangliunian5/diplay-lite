package com.shilapi.xcertplay.usbbt

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.IOException

/**
 * vc181（外置 USB 蓝牙 C/D 批）：iAP2 RFCOMM 流出口。
 * 包一层上游建好的 [RfcommDlc]，send/recv 语义对齐 BluetoothRfcommDuplexStream
 * （超时 null、对端结束空数组、send 失败抛 IOException）；
 * [close] 先关 DLC 再停 host（释放 dongle，幂等可重入）。
 */
internal class UsbBtRfcommStream(
    private val dlc: RfcommDlc,
    private val host: UsbBtHost,
) : BlockingDuplexByteStream {
    @Volatile
    private var closed = false

    override fun send(data: ByteArray) {
        if (closed) throw IOException("usbbt stream is closed")
        dlc.send(data)
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        if (closed) return ByteArray(0)
        return dlc.recv(maxBytes, timeoutMillis)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            dlc.close()
        } finally {
            host.stop()
        }
    }
}
