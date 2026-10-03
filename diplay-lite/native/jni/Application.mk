# Lite（Android 4.1 / API16 分支）专用配置。
#
# APP_PLATFORM 必须与 minSdk 16 对齐：编 android-21 的 .so 会链接 API21 的 libc，
# 拿到 C8815（API16）上有 UnsatisfiedLinkError 风险，而本机没有 adb、拿不到崩溃栈，
# 那种失败最难排查。注意 NDK r25 起最低只支持 19、r26 起最低 21 ——
# 所以本工程用 r23b（备选 r21e）这类仍支持 android-16 的老 NDK 构建。
APP_PLATFORM := android-16

# 纯 C（libopus + 我们的 JNI 封装），不需要 C++ 运行库。
APP_STL := none

# C8815 = armeabi-v7a（MSM8625/Cortex-A5，无 NEON）；nova3e = arm64-v8a。
APP_ABI := armeabi-v7a arm64-v8a