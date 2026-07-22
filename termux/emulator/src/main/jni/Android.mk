LOCAL_PATH := $(call my-dir)

# Terminal emulator JNI library.
# exec_wrap.c overrides execve() so that every fork()+exec() from the app
# process retries via linker64 on EACCES/EPERM — this fixes Android 16 W^X
# blocking exec on app_data_file binaries without relying on LD_PRELOAD being
# honoured by linker64 in its direct-invocation mode.
include $(CLEAR_VARS)
LOCAL_MODULE    := libtermux
LOCAL_SRC_FILES := termux.c exec_wrap.c
LOCAL_LDLIBS    := -ldl
include $(BUILD_SHARED_LIBRARY)
