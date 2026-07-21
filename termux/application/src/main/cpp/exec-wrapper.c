/*
 * exec-wrapper.c
 *
 * LD_PRELOAD shim that intercepts execve() on Android 16+ (API 36).
 *
 * Android 16 blocks execve() on files in the app's private data directory
 * (SELinux context app_data_file) with EACCES.  When we detect that failure,
 * we retry by invoking the system dynamic linker directly:
 *
 *   execve("/system/bin/linker64", [linker64, <binary>, args…], envp)
 *
 * The linker is a trusted system binary whose exec is always permitted; it
 * loads the target ELF via mmap (not a second execve), bypassing the SELinux
 * restriction.  Because LD_PRELOAD is preserved in envp, every child process
 * spawned by the shell also gets this wrapper automatically.
 */

#define _GNU_SOURCE
#include <unistd.h>
#include <errno.h>
#include <stdlib.h>
#include <dlfcn.h>

typedef int (*execve_fn_t)(const char *, char *const[], char *const[]);

static execve_fn_t get_real_execve(void) {
    static execve_fn_t fn = NULL;
    if (!fn) fn = (execve_fn_t) dlsym(RTLD_NEXT, "execve");
    return fn;
}

/* Retry exec via the system dynamic linker. */
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
    int saved_errno = errno;
    free(new_argv);
    errno = saved_errno;
    return ret;
}

/*
 * Override execve.  All exec-family functions in Bionic ultimately call this,
 * so intercepting it is sufficient.
 */
int execve(const char *pathname, char *const argv[], char *const envp[]) {
    int ret = get_real_execve()(pathname, argv, envp);
    if (ret == -1 && errno == EACCES) {
        ret = exec_via_linker(pathname, argv, envp);
    }
    return ret;
}
