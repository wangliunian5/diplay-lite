# Vendored libopus 1.4 (https://github.com/xiph/opus, tag v1.4).
# 定点编译（FIXED_POINT），走通用 C 路径（不启用 ARM/NEON intrinsics），
# 满足老设备（本分支 minSdk 16 / API16，如 C8815）的麦克风上行 OPUS 编码，
# 以及导航/通话/语音的 OPUS 解码（本机连 OMX.google.opus.decoder 都没有）。
# 定点配置对弱 CPU 友好：C8815 = MSM8625/Cortex-A5，无 NEON，正好走通用 C 路径。
# 许可：BSD-3-Clause，见本目录 COPYING。

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := opus
LOCAL_CFLAGS := \
    -DFIXED_POINT \
    -DOPUS_BUILD \
    -DVAR_ARRAYS \
    -DHAVE_LRINT \
    -DHAVE_LRINTF \
    '-DEXPORT=__attribute__((visibility("default")))' \
    -O3
LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/include \
    $(LOCAL_PATH)/celt \
    $(LOCAL_PATH)/silk \
    $(LOCAL_PATH)/silk/fixed \
    $(LOCAL_PATH)/src
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_SRC_FILES := $(subst $(LOCAL_PATH)/,,$(wildcard $(LOCAL_PATH)/celt/*.c))
LOCAL_SRC_FILES += $(subst $(LOCAL_PATH)/,,$(wildcard $(LOCAL_PATH)/silk/*.c))
LOCAL_SRC_FILES += $(subst $(LOCAL_PATH)/,,$(wildcard $(LOCAL_PATH)/silk/fixed/*.c))
LOCAL_SRC_FILES += $(subst $(LOCAL_PATH)/,,$(wildcard $(LOCAL_PATH)/src/*.c))
LOCAL_SRC_FILES := $(filter-out celt/opus_custom_demo.c,$(LOCAL_SRC_FILES))
include $(BUILD_STATIC_LIBRARY)
