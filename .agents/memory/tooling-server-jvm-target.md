---
name: Tooling server JVM target
description: The tooling-api shadow JAR JVM target must match the JDK bundled with AndroidIDE on the target device.
---

# Tooling Server JVM Target

## The Situation
The `tooling/impl` shadow JAR (`tooling-api-all.jar`) is exec'd by AndroidIDE's bundled JDK on-device.
The root `build.gradle.kts` propagates `BuildConfig.javaVersion = VERSION_21` to all modules via
`configureJavaModule()` and `tasks.withType<KotlinCompile>()`. Historically AndroidIDE bundled JDK 17,
so an `afterEvaluate {}` override forced the tooling modules back to Java 17.

## Current Setting (JVM 21)
All modules — including all tooling shadow JAR modules — are now set to **Java 21** because:
- The user's device bundles JDK 21 (`Running on Java version: 21.0.1-internal` observed in logs).
- `:utilities:framework-stubs` (and potentially other modules) requires JVM 21+; keeping tooling
  modules at JVM 17 caused `Dependency resolution is looking for a library compatible with JVM
  runtime version 17, but … only compatible with JVM runtime version 21 or newer` build failures.

**Why:** Class files compiled to JVM 21 (class file version 65.0) cannot run on JDK 17 (max 61.0).
If the target device bundles JDK 17, revert the `afterEvaluate` overrides in all affected modules
back to `"17"` / `JVM_17`.

**How to apply:** If a device ships JDK 17, re-add `afterEvaluate {}` blocks in all modules listed
below that re-set `sourceCompatibility`, `targetCompatibility` = `"17"` and Kotlin `jvmTarget` =
`JVM_17`. Use `afterEvaluate` to ensure the override runs after root `subprojects` callbacks.

## Affected modules (currently JVM 21)
- `tooling/impl`, `tooling/api`, `tooling/model`, `tooling/events`, `tooling/builder-model-impl`
- `utilities/shared`, `utilities/build-info`
- `logging/logger`, `utilities/build-info`, `xml/dom`
- `annotation/processors`
- `composite-builds/build-logic/{build,common,plugins}`
- `tooling/plugin/src/test/.../build-logic/ide`

## Propagation chain
`tooling/impl` → `tooling/api` → `tooling/model` → `tooling/builder-model-impl`, `tooling/events`;
`tooling/api` → `utilities/shared` → `utilities/build-info`

If new modules are added as dependencies of `tooling/impl`, check whether they also need the
`afterEvaluate` Java version override to match the device JDK.
