LOCAL_PATH := $(call my-dir)

# Terminal emulator JNI library.
include $(CLEAR_VARS)
LOCAL_MODULE    := libtermux
LOCAL_SRC_FILES := termux.c
include $(BUILD_SHARED_LIBRARY)

# LD_PRELOAD shim for Android 16+ exec restriction (SELinux/W^X).
# Loaded into the terminal shell environment so that every execve() call from
# bash and its children retries EACCES failures through the system linker.
include $(CLEAR_VARS)
LOCAL_MODULE    := libandroidide-exec-wrapper
LOCAL_SRC_FILES := exec_wrap.c
LOCAL_LDLIBS    := -ldl
include $(BUILD_SHARED_LIBRARY)
