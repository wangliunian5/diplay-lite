// DiPlay Lite 超低版本工具链（见 超低版本-编译环境与难点拆分.md §1.2）
// Gradle 7.6.4 / AGP 7.4.2 / Kotlin 1.8.22 / minSdk 16
// 注：Kotlin 固定 1.8.x —— R8 4.0.52（AGP 7.4.2 内置）无法 dex 化 Kotlin 1.9+ 元数据
// 注：BC 固定 jdk15on 1.70 —— bcprov 1.79（2024 年 class 文件）R8 4.0.52 解析崩溃
plugins {
    id("com.android.application") version "7.4.2" apply false
    id("com.android.library") version "7.4.2" apply false
    id("org.jetbrains.kotlin.android") version "1.8.22" apply false
}
