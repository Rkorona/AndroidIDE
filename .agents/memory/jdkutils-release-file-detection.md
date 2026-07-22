---
name: JdkUtils release-file detection for Android 16
description: Why findJavaInstallations() reads the 'release' file instead of executing java, and where the exec-based fallback still lurks
---

# JdkUtils: release-file detection

## The Rule
`findJavaInstallations()` must NOT execute the java binary to detect installed JDKs.
Use `getDistFromReleaseFile(jdkDir)` first; fall back to `getDistFromJavaBin()` only when
the `release` file is absent (covers pre-Android-16 custom installs).

**Why:** On Android 16, W^X + SELinux policy blocks `execve()` on files in the app's private
data directory (`/data/data/.../usr/opt/...`). Calling `ProcessBuilder.start()` on `bash` or
`java` there throws `IOException (EACCES)`, which is caught in the outer try/catch and logged
as "Failed to list java alternatives", returning an empty list — causing the onboarding screen
to loop forever even after a successful install.

**How to apply:** Any new code path that needs to inspect an installed JDK should call
`JdkUtils.getDistFromReleaseFile(jdkDir)` first. The exec-based path
(`getDistFromJavaBin` / `executeWithBash`) is fine for LSP/build tooling after bootstrap
(covered by the LD_PRELOAD exec-wrapper), but not for cold detection at onboarding time.

## Still-open exec-based paths (potential future regressions)
- `getDistFromJavaHome()` → `getDistFromJavaBin()` — used when user sets a custom JDK in
  Settings → Build & Run. Not yet updated; tracked as follow-up task.
- `executeWithBash()` in general — used by build tooling; protected by LD_PRELOAD wrapper
  after bootstrap, but the wrapper is not active during onboarding JDK detection.

## Key file
- `core/app/src/main/java/com/itsaky/androidide/utils/JdkUtils.kt`
  — `findJavaInstallations()`, `getDistFromReleaseFile()` (new), `executeWithBash()` (old path)
