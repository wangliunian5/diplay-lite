# DiPlay Lite 混淆规则（M0 保守起步，见 编译环境与难点拆分.md A6）

# BouncyCastle 通过反射加载算法，先整包保留，M 验证通过后逐步收紧
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# JmDNS
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**
