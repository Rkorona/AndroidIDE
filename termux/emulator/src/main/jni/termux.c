#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TERMUX_UNUSED(x) x __attribute__((__unused__))
#ifdef __APPLE__
# define LACKS_PTSNAME_R
#endif

static int throw_runtime_exception(JNIEnv* env, char const* message)
{
    jclass exClass = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, exClass, message);
    return -1;
}

static int create_subprocess(JNIEnv* env,
        char const* cmd,
        char const* cwd,
        char* const argv[],
        char** envp,
        int* pProcessId,
        jint rows,
        jint columns)
{
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

#ifdef LACKS_PTSNAME_R
    char* devname;
#else
    char devname[64];
#endif
    if (grantpt(ptm) || unlockpt(ptm) ||
#ifdef LACKS_PTSNAME_R
            (devname = ptsname(ptm)) == NULL
#else
            ptsname_r(ptm, devname, sizeof(devname))
#endif
       ) {
        return throw_runtime_exception(env, "Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx");
    }

    // Enable UTF-8 mode and disable flow control to prevent Ctrl+S from locking up the display.
    struct termios tios;
    tcgetattr(ptm, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(ptm, TCSANOW, &tios);

    /** Set initial winsize. */
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns };
    ioctl(ptm, TIOCSWINSZ, &sz);

    pid_t pid = fork();
    if (pid < 0) {
        return throw_runtime_exception(env, "Fork failed");
    } else if (pid > 0) {
        *pProcessId = (int) pid;
        return ptm;
    } else {
        // Clear signals which the Android java process may have blocked:
        sigset_t signals_to_unblock;
        sigfillset(&signals_to_unblock);
        sigprocmask(SIG_UNBLOCK, &signals_to_unblock, 0);

        close(ptm);
        setsid();

        int pts = open(devname, O_RDWR);
        if (pts < 0) exit(-1);

        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);

        DIR* self_dir = opendir("/proc/self/fd");
        if (self_dir != NULL) {
            int self_dir_fd = dirfd(self_dir);
            struct dirent* entry;
            while ((entry = readdir(self_dir)) != NULL) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != self_dir_fd) close(fd);
            }
            closedir(self_dir);
        }

        clearenv();
        if (envp) for (; *envp; ++envp) putenv(*envp);

        // Android 16 W^X defense-in-depth: force-load the exec-wrapper shim from
        // LD_PRELOAD even if the dynamic linker would not honor LD_PRELOAD in its
        // direct-invocation mode (e.g. due to linker namespace isolation).
        // After this dlopen() the child process has our execve() override active,
        // so the execvp() call below goes through the shim which retries via linker64.
        {
            const char *preload = getenv("LD_PRELOAD");
            if (preload) {
                // LD_PRELOAD may be "path1:path2"; dlopen() only the first entry.
                char *colon = strchr(preload, ':');
                if (colon) {
                    // Copy just the first path to a stack buffer.
                    size_t len = (size_t)(colon - preload);
                    if (len < 512) {
                        char path[512];
                        memcpy(path, preload, len);
                        path[len] = '\0';
                        dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
                    }
                } else {
                    dlopen(preload, RTLD_LAZY | RTLD_GLOBAL);
                }
            }
        }

        if (chdir(cwd) != 0) {
            char* error_message;
            // No need to free asprintf()-allocated memory since doing execvp() or exit() below.
            if (asprintf(&error_message, "chdir(\"%s\")", cwd) == -1) error_message = "chdir()";
            perror(error_message);
            fflush(stderr);
        }
        execvp(cmd, argv);

        // execvp failed (likely Android 16+ SELinux/W^X blocking exec on app_data_file).
        // Fallback: invoke the system dynamic linker directly. The linker is a trusted system
        // binary whose exec is always permitted; it loads the target ELF via mmap (not execve),
        // bypassing the execve restriction on files in the app's private data directory.
        //
        // Special case: if cmd is a shell script (starts with '#!') rather than an ELF binary,
        // linker64 cannot load it directly — we must parse the shebang and pass the interpreter
        // as the ELF target instead.
        {
            // Detect file type: ELF (\x7fELF) vs script (#!).
            // For scripts, parse the interpreter path from the shebang line.
            const char* elf_target = cmd;  // The ELF binary to hand to linker64
            char interp_buf[512] = {0};    // Stack buffer for parsed interpreter path

            {
                int fd = open(cmd, O_RDONLY);
                if (fd >= 0) {
                    // Read only the first 2 bytes to detect '#!'.
                    // Keeping the read to exactly 2 bytes means the file offset
                    // is already at byte 2 — the first character of the interpreter
                    // path — so parsing below captures the full path without truncation.
                    unsigned char magic[2] = {0};
                    if (read(fd, magic, 2) == 2 && magic[0] == '#' && magic[1] == '!') {
                        // Script — file offset is now at byte 2, right after '#!'.
                        // Format: #![optional-space]<interp>[<space or newline>...]
                        int i = 0;
                        unsigned char c;
                        // Skip optional spaces directly after '#!'
                        while (read(fd, &c, 1) == 1 && c == ' ') {}
                        // c is now the first non-space character of the interpreter path.
                        if (c != '\n' && c != '\r' && c != '\0') {
                            interp_buf[i++] = (char) c;
                            while (i < (int)(sizeof(interp_buf) - 1)
                                   && read(fd, &c, 1) == 1
                                   && c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                                interp_buf[i++] = (char) c;
                            }
                        }
                        if (i > 0) {
                            // Use the interpreter as the ELF target for linker64.
                            elf_target = interp_buf;
                        }
                    }
                    close(fd);
                }
            }

            const char* linker_path = NULL;
            if (access("/system/bin/linker64", X_OK) == 0) {
                linker_path = "/system/bin/linker64";
            } else if (access("/system/bin/linker", X_OK) == 0) {
                linker_path = "/system/bin/linker";
            }
            if (linker_path != NULL) {
                int argc = 0;
                while (argv[argc]) argc++;

                char** new_argv;
                if (elf_target != cmd) {
                    // Script: new_argv = [linker_path, interp, script, argv[1..], NULL]
                    // linker64 loads the interpreter (ELF); the script path becomes its argument.
                    new_argv = (char**) malloc((argc + 3) * sizeof(char*));
                    if (new_argv) {
                        new_argv[0] = (char*) linker_path;
                        new_argv[1] = (char*) elf_target;
                        new_argv[2] = (char*) cmd;
                        for (int i = 1; i < argc; i++) new_argv[i + 2] = argv[i];
                        new_argv[argc + 2] = NULL;
                        execv(linker_path, new_argv);
                        free(new_argv);
                    }
                } else {
                    // ELF: new_argv = [linker_path, cmd, argv[1..], NULL]
                    new_argv = (char**) malloc((argc + 2) * sizeof(char*));
                    if (new_argv) {
                        new_argv[0] = (char*) linker_path;
                        new_argv[1] = (char*) cmd;
                        for (int i = 1; i < argc; i++) new_argv[i + 1] = argv[i];
                        new_argv[argc + 1] = NULL;
                        execv(linker_path, new_argv);
                        free(new_argv);
                    }
                }
            }
        }

        // Show terminal output about failing exec() call:
        char* error_message;
        if (asprintf(&error_message, "exec(\"%s\")", cmd) == -1) error_message = "exec()";
        perror(error_message);
        _exit(1);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jstring cmd,
        jstring cwd,
        jobjectArray args,
        jobjectArray envVars,
        jintArray processIdArray,
        jint rows,
        jint columns)
{
    jsize size = args ? (*env)->GetArrayLength(env, args) : 0;
    char** argv = NULL;
    if (size > 0) {
        argv = (char**) malloc((size + 1) * sizeof(char*));
        if (!argv) return throw_runtime_exception(env, "Couldn't allocate argv array");
        for (int i = 0; i < size; ++i) {
            jstring arg_java_string = (jstring) (*env)->GetObjectArrayElement(env, args, i);
            char const* arg_utf8 = (*env)->GetStringUTFChars(env, arg_java_string, NULL);
            if (!arg_utf8) return throw_runtime_exception(env, "GetStringUTFChars() failed for argv");
            argv[i] = strdup(arg_utf8);
            (*env)->ReleaseStringUTFChars(env, arg_java_string, arg_utf8);
        }
        argv[size] = NULL;
    }

    size = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    char** envp = NULL;
    if (size > 0) {
        envp = (char**) malloc((size + 1) * sizeof(char *));
        if (!envp) return throw_runtime_exception(env, "malloc() for envp array failed");
        for (int i = 0; i < size; ++i) {
            jstring env_java_string = (jstring) (*env)->GetObjectArrayElement(env, envVars, i);
            char const* env_utf8 = (*env)->GetStringUTFChars(env, env_java_string, 0);
            if (!env_utf8) return throw_runtime_exception(env, "GetStringUTFChars() failed for env");
            envp[i] = strdup(env_utf8);
            (*env)->ReleaseStringUTFChars(env, env_java_string, env_utf8);
        }
        envp[size] = NULL;
    }

    int procId = 0;
    char const* cmd_cwd = (*env)->GetStringUTFChars(env, cwd, NULL);
    char const* cmd_utf8 = (*env)->GetStringUTFChars(env, cmd, NULL);
    int ptm = create_subprocess(env, cmd_utf8, cmd_cwd, argv, envp, &procId, rows, columns);
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_cwd);

    if (argv) {
        for (char** tmp = argv; *tmp; ++tmp) free(*tmp);
        free(argv);
    }
    if (envp) {
        for (char** tmp = envp; *tmp; ++tmp) free(*tmp);
        free(envp);
    }

    int* pProcId = (int*) (*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);
    if (!pProcId) return throw_runtime_exception(env, "JNI call GetPrimitiveArrayCritical(processIdArray, &isCopy) failed");

    *pProcId = procId;
    (*env)->ReleasePrimitiveArrayCritical(env, processIdArray, pProcId, 0);

    return ptm;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyWindowSize(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd, jint rows, jint cols)
{
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyUTF8Mode(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd)
{
    struct termios tios;
    tcgetattr(fd, &tios);
    if ((tios.c_iflag & IUTF8) == 0) {
        tios.c_iflag |= IUTF8;
        tcsetattr(fd, TCSANOW, &tios);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_waitFor(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint pid)
{
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    } else {
        // Should never happen - waitpid(2) says "One of the first three macros will evaluate to a non-zero (true) value".
        return 0;
    }
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_close(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fileDescriptor)
{
    close(fileDescriptor);
}
