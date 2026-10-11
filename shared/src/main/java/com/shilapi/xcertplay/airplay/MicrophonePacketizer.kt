package com.shilapi.xcertplay.airplay

import java.net.InetAddress

/** Everything one captured microphone stream needs to send samples to the phone. */
data class MicrophoneConfig(
    val audioType: String,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val frameMillis: Int,
    val host: InetAddress,
    val port: Int,
    val key: ByteArray,
    val codec: AudioCodecKind = AudioCodecKind.LPCM,
    val bitrate: Int? = null,
    /** 1.0.12 验证改动：上行 socket 显式绑定会话源地址，避免多网卡时 mic 包走错接口。 */
    val bindAddress: InetAddress? = null,
) {
    val samplesPerPacket: Int
        get() = if (codec == AudioCodecKind.OPUS) {
            OPUS_SAMPLES_PER_PACKET
        } else {
            maxOf(1, sampleRate * frameMillis / 1000)
        }

    val frameBytes: Int
        get() = samplesPerPacket * channels * 2

    private companion object {
        const val OPUS_SAMPLES_PER_PACKET = 960
    }
}

/** Mutable RTP/ChaCha counters for one microphone uplink. */
class MicrophoneCounters(
    var sequence: Int = 0,
    var timestamp: Int = 0,
    var nonce: Long = 0,
    /** RTP SSRC。1.0.12 验证改动：RTP 规范禁止 0（0=未设置），此前恒 0 可能被 iPhone 整流丢弃。 */
    var ssrc: Int = 0,
)

/**
 * Seals one PCM microphone frame in the wired CarPlay RTP layout.
 *
 * The 12-byte RTP header is clear; its timestamp and SSRC are authenticated as AAD. The packet
 * tail repeats the eight-byte little-endian nonce counter after the 16-byte Poly1305 tag.
 */
object MicrophonePacketizer {
    const val RTP_HEADER_LEN = 12
    const val TAG_LEN = 16
    const val NONCE_LEN = 8

    /**
     * 1.0.19 静态定罪：下行 LPCM 解密后必须 `byteSwapS16` 再喂 AudioTrack 才出声
     * （AndroidMediaSink.handle），证明 wired CarPlay LPCM 线格式为**大端**，mic 方向同理。
     * 1.0.13 的小端实验（false）与该证据矛盾，作废回滚，不再等实测。
     */
    const val PCM_WIRE_BIG_ENDIAN = true

    /** Captured Android PCM is little-endian; CarPlay's wired microphone payload is big-endian. */
    fun toWirePcm(pcmLittleEndian: ByteArray): ByteArray {
        if (!PCM_WIRE_BIG_ENDIAN) return pcmLittleEndian
        val output = pcmLittleEndian.copyOf()
        var index = 0
        while (index + 1 < output.size) {
            val first = output[index]
            output[index] = output[index + 1]
            output[index + 1] = first
            index += 2
        }
        return output
    }

    fun sealPacket(
        key: ByteArray,
        payloadType: Int,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte()
        header[1] = (payloadType and 0x7f).toByte()
        putU16Be(header, 2, counters.sequence)
        putU32Be(header, 4, counters.timestamp)
        putU32Be(header, 8, counters.ssrc)

        val nonce = ByteArray(12)
        putU64Le(nonce, 4, counters.nonce)
        val sealed = AirPlayCrypto.chachaSeal(
            key = key,
            nonce = nonce,
            plaintext = body,
            aad = header.copyOfRange(4, RTP_HEADER_LEN),
        )

        val packet = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_LEN)
        header.copyInto(packet, 0)
        sealed.copyInto(packet, RTP_HEADER_LEN)
        putU64Le(packet, RTP_HEADER_LEN + sealed.size, counters.nonce)

        counters.sequence = (counters.sequence + 1) and 0xffff
        counters.timestamp += samples
        counters.nonce++
        return packet
    }

    private fun putU16Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun putU32Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun putU64Le(target: ByteArray, offset: Int, value: Long) {
        var remaining = value
        for (index in 0 until 8) {
            target[offset + index] = remaining.toByte()
            remaining = remaining ushr 8
        }
    }
}
