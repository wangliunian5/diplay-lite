# DiPlay Lite 混淆规则（M0 保守起步，见 编译环境与难点拆分.md A6）

# BouncyCastle 通过反射加载算法，先整包保留，M 验证通过后逐步收紧
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# JmDNS
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**

# vc101（T3）：JNI native 方法名与 libusb_host_compat.so 符号一一对应，混淆即 NoSuchMethodError
-keepclasseswithmembernames class com.shilapi.xcertplay.transport.LegacyUsbHostNative {
    native <methods>;
}
-keep class com.shilapi.xcertplay.transport.LegacyUsbHostNative { *; }

# vc164：**所有**含 native 方法的类统一保留类名与方法名。
# JNI 按包名+类名+方法名注册（Java_com_shilapi_xcertplay_…_nativeXxx），R8 改名即
# NoSuchMethodError。已覆盖：NativeOpusDecoder/NativeOpusEncoder（API16 上音频
# 必经此路，K60 是 API34 走 MediaCodec 探测不到这个坑）。未来新增 JNI 类自动纳入。
-keepclasseswithmembernames class com.shilapi.xcertplay.** {
    native <methods>;
}
