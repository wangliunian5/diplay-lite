package com.shilapi.xcertplay.airplay

enum class AirPlayPhysicalSizeBasis {
    WIDTH,
    HEIGHT,
}

data class AirPlayPhysicalSizeMm(
    val widthMm: Int,
    val heightMm: Int,
)

/** User-adjustable display values with the same stepped ranges used by the settings UI. */
object AirPlayDisplaySettings {
    // vc157：下限 30→25（弱机自救档——降负载降码率，8815 老硬解的绿线噪点可借此进一步减轻）。
    // STEP=5 不变，网格 25/30/35/40/45/50/55/60；UI 只露出 25/30/40/60 四档，中间值
    // 仅作为 snap 边界存在（prefs 唯一写入口是设置页四档，不会产生中间值）。
    const val MIN_FPS = 25
    const val MAX_FPS = 60
    const val FPS_STEP = 5
    const val DEFAULT_FPS = MAX_FPS

    const val MIN_WIDTH_PHYSICAL_MM = 100
    const val MAX_WIDTH_PHYSICAL_MM = 400
    const val WIDTH_PHYSICAL_MM_STEP = 50
    const val DEFAULT_WIDTH_PHYSICAL_MM = 200
    val DEFAULT_PHYSICAL_SIZE_BASIS = AirPlayPhysicalSizeBasis.WIDTH
    const val MIN_REPORTED_PHYSICAL_MM = 1
    const val MAX_REPORTED_PHYSICAL_MM = 2_000

    fun sanitizeFps(value: Int): Int {
        // vc157：删除原 API<21 压 30 分支（8815 运动噪块修复②）——按 API 一刀切把
        // 同 API 下性能更好的老车机也锁死在 30。档位选择本身就是「用户自调负载」机制：
        // 弱机（8815 Adreno 203 档，60fps+高码率会静默输出纠错噪块）建议选 25/30；
        // 40/60 档在任何机型上真实生效，噪块风险由用户知情选择、自行降档兜底。
        return snapToStep(value, MIN_FPS, MAX_FPS, FPS_STEP)
    }

    fun sanitizeWidthPhysicalMm(value: Int): Int =
        snapToStep(
            value,
            MIN_WIDTH_PHYSICAL_MM,
            MAX_WIDTH_PHYSICAL_MM,
            WIDTH_PHYSICAL_MM_STEP,
        )

    fun sanitizeReportedPhysicalMm(value: Int): Int =
        value.coerceIn(MIN_REPORTED_PHYSICAL_MM, MAX_REPORTED_PHYSICAL_MM)

    fun resolvePhysicalSizeMm(
        currentWidthPixels: Int,
        currentHeightPixels: Int,
        maximumWidthPixels: Int,
        maximumHeightPixels: Int,
        referenceMillimeters: Int,
        basis: AirPlayPhysicalSizeBasis,
    ): AirPlayPhysicalSizeMm {
        require(currentWidthPixels > 0 && currentHeightPixels > 0) {
            "Current display dimensions must be positive"
        }
        require(maximumWidthPixels > 0 && maximumHeightPixels > 0) {
            "Maximum display dimensions must be positive"
        }
        val referencePixels = when (basis) {
            AirPlayPhysicalSizeBasis.WIDTH ->
                currentWidthPixels.toDouble() / maximumWidthPixels
            AirPlayPhysicalSizeBasis.HEIGHT ->
                currentHeightPixels.toDouble() / maximumHeightPixels
        }
        val referenceMm = referenceMillimeters.coerceAtLeast(1) * referencePixels
        val widthMm: Double
        val heightMm: Double
        when (basis) {
            AirPlayPhysicalSizeBasis.WIDTH -> {
                widthMm = referenceMm
                heightMm = widthMm * currentHeightPixels / currentWidthPixels
            }
            AirPlayPhysicalSizeBasis.HEIGHT -> {
                heightMm = referenceMm
                widthMm = heightMm * currentWidthPixels / currentHeightPixels
            }
        }
        return AirPlayPhysicalSizeMm(
            widthMm = sanitizeReportedPhysicalMm(Math.round(widthMm).toInt()),
            heightMm = sanitizeReportedPhysicalMm(Math.round(heightMm).toInt()),
        )
    }

    fun fpsProgress(value: Int): Int =
        (sanitizeFps(value) - MIN_FPS) / FPS_STEP

    fun widthPhysicalMmProgress(value: Int): Int =
        (sanitizeWidthPhysicalMm(value) - MIN_WIDTH_PHYSICAL_MM) / WIDTH_PHYSICAL_MM_STEP

    private fun snapToStep(value: Int, minimum: Int, maximum: Int, step: Int): Int {
        val clamped = value.coerceIn(minimum, maximum)
        return (((clamped - minimum) + step / 2) / step) * step + minimum
    }
}
