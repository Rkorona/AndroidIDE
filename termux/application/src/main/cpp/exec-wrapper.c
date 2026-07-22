/*
 * exec-wrapper.c
 *
 * LD_PRELOAD shim that intercepts execve() on Android 16+ (API 36).
 *
 * Android 16 blocks execve() on files in the app's private data directory
 * (SELinux context app_data_file) with EACCES.  When we detect that failure,
 * we try two fallback strategies depending on the file type:
 *
 *  ELF binary  →  execve("/system/bin/linker64", [linker64, binary, args…], envp)
 *                 The system linker loads the ELF via mmap — no execve restriction.
 *
 *  Shell script →  parse the shebang line (#!), then call our own execve() for
 *                 the interpreter (which will itself fall back to linker64 as needed).
 *
 * Because LD_PRELOAD is preserved in envp, every child process inherits the wrapper.
 */

#define _GNU_SOURCE
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

typedef int (*execve_fn_t)(const char *, char *const[], char *const[]);

static execve_fn_t get_real_execve(void) {
    static execve_fn_t fn = NULL;
    if (!fn) fn = (execve_fn_t) dlsym(RTLD_NEXT, "execve");
    return fn;
}

/* ------------------------------------------------------------------ */
/* ELF fallback: load binary via the system dynamic linker             */
/* ------------------------------------------------------------------ */
static int exec_via_linker(const char *pathname,
                            char *const argv[],
                            char *const envp[]) {
    const char *linker = "/system/bin/linker64";
    if (access(linker, X_OK) != 0) {
        linker = "/system/bin/linker";
        if (access(linker, X_OK) != 0) { errno = EACCES; return -1; }
    }

    int argc = 0;
    if (argv) while (argv[argc]) argc++;

    /* new_argv = [ linker, pathname, argv[1..argc-1], NULL ] */
    char **new_argv = (char **) malloc((argc + 2) * sizeof(char *));
    if (!new_argv) { errno = ENOMEM; return -1; }
    new_argv[0] = (char *) linker;
    new_argv[1] = (char *) pathname;
    for (int i = 1; i < argc; i++) new_argv[i + 1] = argv[i];
    new_argv[argc + 1] = NULL;

    static char *empty_env[] = { NULL };
    int ret = get_real_execve()(linker, new_argv, envp ? envp : empty_env);
    int saved = errno;
    free(new_argv);
    errno = saved;
    return ret;
}

/* ------------------------------------------------------------------ */
/* Script fallback: parse #! shebang and exec the interpreter          */
/* ------------------------------------------------------------------ */
/* Forward-declare our override so shebang handling can call it. */
int execve(const char *pathname, char *const argv[], char *const envp[]);

static int exec_via_shebang(const char *pathname,
                             char *const argv[],
                             char *const envp[]) {
    /* Read the shebang line (up to 512 bytes is more than enough). */
    int fd = open(pathname, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { errno = ENOEXEC; return -1; }

    char line[512];
    ssize_t n = read(fd, line, sizeof(line) - 1);
    close(fd);
    if (n < 2) { errno = ENOEXEC; return -1; }
    line[n] = '\0';

    /* Verify shebang; isolate first line. */
    if (line[0] != '#' || line[1] != '!') { errno = ENOEXEC; return -1; }
    char *nl = (char *) memchr(line + 2, '\n', n - 2);
    if (nl) *nl = '\0';

    /* Skip whitespace after #! */
    char *interp = line + 2;
    while (*interp == ' ' || *interp == '\t') interp++;
    if (*interp == '\0') { errno = ENOEXEC; return -1; }

    /* Optional single argument after interpreter path (e.g. "#!/usr/bin/env bash") */
    char *interp_arg = NULL;
    char *sp = interp;
    while (*sp && *sp != ' ' && *sp != '\t') sp++;
    if (*sp) {
        *sp = '\0';
        interp_arg = sp + 1;
        while (*interp_arg == ' ' || *interp_arg == '\t') interp_arg++;
        if (*interp_arg == '\0') interp_arg = NULL;
    }

    /*
     * Build new argv:
     *   [ interp, [interp_arg,] pathname, argv[1..], NULL ]
     *
     * argv[0] (original command name) is replaced by pathname per POSIX.
     */
    int argc = 0;
    if (argv) while (argv[argc]) argc++;

    int extra = interp_arg ? 3 : 2; /* interp + optional_arg + pathname */
    char **na = (char **) malloc((extra + argc) * sizeof(char *));
    if (!na) { errno = ENOMEM; return -1; }

    int idx = 0;
    na[idx++] = interp;
    if (interp_arg) na[idx++] = interp_arg;
    na[idx++] = (char *) pathname;
    for (int i = 1; i < argc; i++) na[idx++] = argv[i];
    na[idx] = NULL;

    /*
     * Call our execve() override for the interpreter.  If the interpreter
     * is also in the app data dir, it will fall back to linker64 (ELF path).
     * No infinite recursion: the interpreter is always an ELF, not a script.
     */
    int ret = execve(interp, na, envp);
    int saved = errno;
    free(na);
    errno = saved;
    return ret;
}

/* ------------------------------------------------------------------ */
/* Main entry point: override execve                                   */
/* ------------------------------------------------------------------ */
__attribute__((visibility("default")))
int execve(const char *pathname, char *const argv[], char *const envp[]) {
    int ret = get_real_execve()(pathname, argv, envp);
    /* Retry on EACCES (SELinux W^X exec denial) or EPERM (some Android 16 kernels). */
    if (ret != -1 || (errno != EACCES && errno != EPERM)) return ret;

    /*
     * EACCES: determine whether this is an ELF or a shell script so we
     * can choose the right fallback.
     */
    int fd = open(pathname, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { errno = EACCES; return -1; }

    unsigned char magic[2];
    ssize_t n = read(fd, magic, 2);
    close(fd);

    if (n == 2 && magic[0] == '#' && magic[1] == '!') {
        return exec_via_shebang(pathname, argv, envp);
    }

    /* Assume ELF (or let linker64 produce a diagnostic). */
    return exec_via_linker(pathname, argv, envp);
}
