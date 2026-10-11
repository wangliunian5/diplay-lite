package com.shilapi.xcertplay.mfi

/**
 * MFi 认证契约（Lite：仅保留接口与异常，I2C 协处理器实现随有线路径裁剪）。
 *
 * 改编思路：原 class MfiAuthenticationClient（LIVI I2C 寄存器时序）只服务于
 * CH341/Linux I2C MFi 后端，Lite 仅用 LOCAL 离线证书，实现类整块删除。
 */
enum class MfiCertificateType {
    MFI,
    BAA,
}

class BaaCertificatePair(leaf: ByteArray, intermediate: ByteArray) {
    val leaf: ByteArray = leaf.copyOf()
    val intermediate: ByteArray = intermediate.copyOf()

    init {
        require(this.leaf.isNotEmpty()) { "BAA leaf certificate must not be empty" }
        require(this.intermediate.isNotEmpty()) { "BAA intermediate certificate must not be empty" }
    }
}

/** Common certificate/signing contract implemented by local coprocessors and remote services. */
interface MfiAuthenticator {
    val certificateType: MfiCertificateType
        get() = MfiCertificateType.MFI

    fun protocolMajor(): Int

    fun readCertificate(
        maximumOutputLength: Int = DEFAULT_MAXIMUM_CERTIFICATE_OUTPUT_LENGTH,
    ): ByteArray

    fun signChallenge(challenge: ByteArray): ByteArray

    fun baaCertificates(): BaaCertificatePair =
        throw MfiInvalidDataException("Authenticator does not provide BAA certificates")

    companion object {
        const val DEFAULT_MAXIMUM_CERTIFICATE_OUTPUT_LENGTH = 65_525
    }
}

/** MFi-layer failures distinct from transport errors. */
sealed class MfiException(message: String, cause: Throwable? = null) : Exception(message, cause)

class MfiInvalidDataException(message: String, cause: Throwable? = null) : MfiException(message, cause)

/** The authenticator did not complete an authentication request; [errorCode] is best-effort only. */
class MfiAuthenticationFailedException(val errorCode: Int?, cause: Throwable? = null) :
    MfiException(
        if (errorCode == null) "MFi authentication failed (error code unavailable)"
        else "MFi authentication failed with error 0x${errorCode.toString(16)}",
        cause,
    )
