/*
 * libandroidide-exec-wrapper.so
 *
 * LD_PRELOAD shim for Android 16+ (API 36+).
 *
 * Android 16 introduced a tightened SELinux policy that blocks execve() on
 * binaries stored in the app's private data directory (app_data_file domain).
 * This affects every Termux-packaged binary under $PREFIX/bin/ that bash or any
 * child process tries to exec after the shell is already running.
 *
 * This library is preloaded into the terminal shell process via LD_PRELOAD so
 * that every execve() call — from bash itself, from scripts it runs, and from
 * every child process in the session — goes through our interceptor.
 *
 * When execve() fails with EACCES:
 *   1. We inspect the target file. If it starts with '#!' (shebang) we parse the
 *      interpreter path from the first line.
 *   2. We retry the exec through the system dynamic linker (/system/bin/linker64
 *      or /system/bin/linker).  The linker is a trusted system binary whose exec
 *      is always permitted; it loads the target ELF via mmap rather than execve,
 *      bypassing the SELinux restriction.
 *
 * For scripts the retry builds:
 *   [linker, interpreter, script, argv[1..], NULL]
 * For ELF binaries:
 *   [linker, path, argv[1..], NULL]
 *
 * The intercepted execve() is the one in bionic libc.  All exec-family wrappers
 * (execvp, execv, execlp, ...) ultimately call execve, so a single override here
 * is sufficient.  LD_PRELOAD is inherited by child processes, so the wrapper
 * remains active for every process spawned during the terminal session.
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Prototype of the real execve in bionic. */
typedef int (*execve_fn_t)(const char *, char * const [], char * const []);

/* Cache the real execve pointer (resolved once via RTLD_NEXT). */
static execve_fn_t get_real_execve(void) {
    static execve_fn_t fn = NULL;
    if (!fn) {
        fn = (execve_fn_t) dlsym(RTLD_NEXT, "execve");
    }
    return fn;
}

/* Return the path of the system dynamic linker, or NULL if none is found. */
static const char *find_linker(void) {
    if (access("/system/bin/linker64", X_OK) == 0) return "/system/bin/linker64";
    if (access("/system/bin/linker",   X_OK) == 0) return "/system/bin/linker";
    return NULL;
}

/*
 * If 'path' is a '#!' script, read the interpreter path into 'buf' (size buflen)
 * and return buf.  Returns NULL if the file is not a script or cannot be read.
 *
 * The read is intentionally limited to exactly 2 bytes for the magic check so
 * that the file offset lands at byte 2 — the first character of the interpreter
 * path — letting the subsequent character loop capture the full path.
 */
static const char *parse_shebang(const char *path, char *buf, int buflen) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;

    unsigned char magic[2] = {0, 0};
    if (read(fd, magic, 2) != 2 || magic[0] != '#' || magic[1] != '!') {
        close(fd);
        return NULL;
    }

    /* File offset is now at byte 2: first character after '#!'. */
    int i = 0;
    unsigned char c;

    /* Skip optional spaces immediately after '#!'. */
    while (read(fd, &c, 1) == 1 && c == ' ') {}

    /* 'c' is now the first non-space character of the interpreter path. */
    if (c != '\n' && c != '\r' && c != '\0' && i < buflen - 1) {
        buf[i++] = (char) c;
        while (i < buflen - 1
               && read(fd, &c, 1) == 1
               && c != ' ' && c != '\t' && c != '\n' && c != '\r') {
            buf[i++] = (char) c;
        }
    }
    close(fd);

    if (i > 0) {
        buf[i] = '\0';
        return buf;
    }
    return NULL;
}

/*
 * Overrides bionic's execve().
 *
 * On success (or on any failure other than EACCES) we behave exactly like the
 * real execve.  On EACCES we attempt the linker64 fallback described above.
 */
int execve(const char *path, char * const argv[], char * const envp[]) {
    execve_fn_t real = get_real_execve();
    if (!real) {
        errno = ENOSYS;
        return -1;
    }

    int ret = real(path, argv, envp);
    /* Retry on EACCES (SELinux W^X exec denial) or EPERM (some Android 16 kernels). */
    if (ret != -1 || (errno != EACCES && errno != EPERM)) {
        return ret;  /* success, or an error we cannot fix */
    }

    /* execve failed with EACCES — try the linker fallback. */
    const char *linker = find_linker();
    if (!linker) {
        return ret;  /* no system linker found; preserve EACCES */
    }

    /* Detect whether the target is a shell script (shebang) or an ELF binary. */
    char interp_buf[512];
    const char *elf_target = path;

    const char *interp = parse_shebang(path, interp_buf, (int) sizeof(interp_buf));
    if (interp) {
        /* Script: the linker must load the interpreter, not the script itself. */
        elf_target = interp;
    }

    /* Count the original argument vector. */
    int argc = 0;
    while (argv[argc]) argc++;

    char **new_argv = NULL;
    if (elf_target != path) {
        /* Script path: [linker, interpreter, script, argv[1..], NULL] */
        new_argv = (char **) malloc((size_t)(argc + 3) * sizeof(char *));
        if (!new_argv) return ret;
        new_argv[0] = (char *) linker;
        new_argv[1] = (char *) elf_target;   /* interpreter ELF */
        new_argv[2] = (char *) path;          /* script passed as argument */
        for (int i = 1; i < argc; i++) new_argv[i + 2] = argv[i];
        new_argv[argc + 2] = NULL;
    } else {
        /* ELF binary: [linker, path, argv[1..], NULL] */
        new_argv = (char **) malloc((size_t)(argc + 2) * sizeof(char *));
        if (!new_argv) return ret;
        new_argv[0] = (char *) linker;
        new_argv[1] = (char *) path;
        for (int i = 1; i < argc; i++) new_argv[i + 1] = argv[i];
        new_argv[argc + 1] = NULL;
    }

    ret = real(linker, new_argv, envp);
    int saved_errno = errno;
    free(new_argv);
    errno = saved_errno;
    return ret;
}
