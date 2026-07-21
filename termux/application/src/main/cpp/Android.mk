LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
include $(BUILD_SHARED_LIBRARY)

# LD_PRELOAD shim: intercepts execve() and retries via system linker on Android 16+
include $(CLEAR_VARS)
LOCAL_MODULE    := libandroidide-exec-wrapper
LOCAL_SRC_FILES := exec-wrapper.c
LOCAL_LDLIBS    := -ldl
include $(BUILD_SHARED_LIBRARY)
