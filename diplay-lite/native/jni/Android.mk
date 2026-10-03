# libopus 静态库 + JNI 封装（编码 + 解码）—— 产出 libxcertplay_opus.so。
#
# 这是**独立**的 ndk-build 工程，不挂进 Gradle 的 externalNativeBuild：
# 本工程路径含空格（…/TRAE SOLO CN/…），ndk-build 在含空格路径下会报
# "APP_BUILD_SCRIPT points to an unknown file"，一旦挂进 Gradle，Lite 的日常构建
# （在含空格路径下 .\gradlew.bat :app:assembleDebug）就会直接失败。
# 所以：源码留在这里，由 build-native.ps1 在**无空格**目录（C:\dpbuild 下）构建，
# 产物 .so 拷进 shared/src/main/jniLibs/<abi>/ 由 AGP 打包。
LOCAL_PATH := $(call my-dir)

# libopus 1.4 定点（见 opus/Android.mk），配套 JNI 封装。
include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_opus
LOCAL_SRC_FILES := opus_jni.c
LOCAL_STATIC_LIBRARIES := opus
LOCAL_C_INCLUDES := $(LOCAL_PATH)/opus/include
LOCAL_CFLAGS := -Wall -Wextra
include $(BUILD_SHARED_LIBRARY)

include $(LOCAL_PATH)/opus/Android.mk