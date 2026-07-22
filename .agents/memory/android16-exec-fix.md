---
name: Android 16 exec fix (terminal + tooling server)
description: Linker64 trick must be applied anywhere app-private ELF binaries are exec'd on Android 16 (API 36), including Java ProcessBuilder calls
---

# Android 16 exec fix

## The Problem
Android 16 (API 36) enforces W^X + SELinux policy that blocks execve() on files in
the app's private data directory (SELinux context: app_data_file). Both path-based
chmod and fchmod-on-write-fd are also blocked when adding execute bits to writable files.

## Root Cause Chain
1. TermuxInstaller extracts bootstrap zip → files get mode 0644 (no execute bit)
2. Os.chmod(path, 0700) → blocked by Android 16 W^X policy
3. Os.fchmod(write-fd, 0700) → also blocked (file still writable = W^X violation)
4. execvp("bash") → EACCES

## Three-Layer Fix

### Layer 1 — fchmod on read-only fd (TermuxInstaller.java ~line 189)
Close write fd first, reopen read-only, fchmod(ro_fd, 0500).
File is not writable at time of fchmod → no W^X violation.

### Layer 2 — linker64 fallback for initial shell (termux.c ~line 106)
After execvp(cmd) fails, retry:
  execv("/system/bin/linker64", [linker64, cmd, argv[1..]])
System binary exec is always allowed; linker loads target ELF via mmap (not execve).
**Confirmed working** — bash started and ran the setup script (screenshot showed exit 126, not 1).

### Layer 3 — LD_PRELOAD exec interceptor (exec-wrapper.c + TermuxShellEnvironment.java)
libandroidide-exec-wrapper.so injected via LD_PRELOAD=<nativeLibraryDir>/...
Intercepts ALL execve() within bash and child processes:
  execve() → on EACCES → exec_via_linker() → real execv("/system/bin/linker64", ...)
LD_PRELOAD is preserved in envp so every child process inherits the wrapper.

**Why:** Layer 2 only covers the initial bash launch. bash's own execve for uname etc. needs the same treatment.

## Key Files
- termux/application/src/main/java/com/termux/app/TermuxInstaller.java (Layer 1)
- termux/emulator/src/main/jni/termux.c (Layer 2)
- termux/application/src/main/cpp/exec-wrapper.c (Layer 3 — new file)
- termux/application/src/main/cpp/Android.mk (builds libandroidide-exec-wrapper)
- termux/shared/src/main/java/com/termux/shared/termux/shell/command/environment/TermuxShellEnvironment.java (Layer 3 LD_PRELOAD injection)

## LD_PRELOAD wrapper does NOT cover Java ProcessBuilder calls
Layer 3 (LD_PRELOAD) only intercepts execve() inside already-running processes (bash and children).
Java's `ProcessBuilder.start()` calls `fork()+execve()` from the JVM itself — the wrapper isn't
loaded there. Any Java code that directly exec-s an app-private binary must apply the linker64 trick
at the command-list level:
  if (SDK_INT >= 36) prepend("/system/bin/linker64") to command
Fixed in: ToolingServerRunner.kt (for JVM/tooling-api-all.jar launch)

## CRITICAL: linker64 can only load ELF binaries — not shell scripts
The linker64 trick in termux.c must check the file's magic bytes before using it.
If the target file starts with `#!` (shebang), it is a script, NOT an ELF.
Passing a script to linker64 produces: `error: "..." has bad ELF magic: 23212f64`
(0x23212f64 = '#!fd', the first 4 bytes of a shebang like #!/data/...)

**Fix (termux.c Layer 2):** Before calling execv(linker_path, ...), read the first 4 bytes:
- If `\x7fELF`: proceed as before — linker64 cmd argv[1..]
- If `#!`: parse interpreter from shebang, exec: linker64 interp script argv[1..]
  The interpreter (e.g. bash) is an ELF; linker64 loads it, and it runs the script.
Implemented in termux/emulator/src/main/jni/termux.c linker64 fallback block.

## PID retrieval
`ReflectionUtils.getDeclaredField(process, "pid")` is blocked by hidden-API restrictions on API 28+.
Use `process.pid().toInt()` (Java 9+ / API 26+) instead. Fixed in ToolingServerRunner.kt.

## exec_wrap.c was dead code — now compiled into libtermux
`termux/emulator/src/main/jni/exec_wrap.c` is a second, cleaner execve-override implementation.
It existed but was NOT in `termux/emulator/src/main/jni/Android.mk`, so it was never compiled.
Fix: added `exec_wrap.c` to LOCAL_SRC_FILES in Android.mk + `-ldl` to LOCAL_LDLIBS.
This embeds the override directly in libtermux.so (loaded in the app/JVM process), providing
defense-in-depth in case LD_PRELOAD is not honored by linker64 in direct-invocation mode.

**Symbol conflict risk**: both exec_wrap.c (libtermux.so) and exec-wrapper.c (libandroidide-exec-wrapper.so)
export `int execve(...)`. When both are loaded in the same process the dynamic linker picks one silently.
See proposed task #4 for resolution.

## EPERM handling added
Both exec_wrap.c and exec-wrapper.c originally only checked `errno == EACCES`.
Some Android 16 kernel/SELinux configs return EPERM for exec denial.
Fixed: check `errno != EACCES && errno != EPERM` in both files.

## Diagnostic log added
TermuxShellEnvironment.java now logs a WARN when libandroidide-exec-wrapper.so is NOT found
in nativeLibraryDir — the single most common silent failure cause (old APK without the .so).

## Testing
- MUST clear app data before testing — TermuxInstaller skips if prefix already exists
- Exit code 1 = exec never started; Exit code 126 = exec started but binary not executable
- Check logcat for "Android 16 exec-wrapper: LD_PRELOAD set to ..." — if you see "NOT found" instead, the APK is old and needs a rebuild
