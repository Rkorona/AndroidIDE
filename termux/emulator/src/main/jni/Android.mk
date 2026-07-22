LOCAL_PATH := $(call my-dir)

# Terminal emulator JNI library.
include $(CLEAR_VARS)
LOCAL_MODULE    := libtermux
LOCAL_SRC_FILES := termux.c
include $(BUILD_SHARED_LIBRARY)
