LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := usb_host_compat
LOCAL_SRC_FILES := usb_host_compat.c
include $(BUILD_SHARED_LIBRARY)
