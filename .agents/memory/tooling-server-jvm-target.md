---
name: Tooling server JVM target
description: The tooling-api shadow JAR must compile to Java 17 to run on AndroidIDE's bundled JDK 17.
---

# Tooling Server JVM Target (Java 17)

## The Rule
All modules bundled into `tooling-api-all.jar` must set Java 17 as their source/target compatibility.

**Why:** AndroidIDE bundles JDK 17. The root `build.gradle.kts` propagates `BuildConfig.javaVersion = VERSION_21` to every module via `configureJavaModule()` (line 108) and `tasks.withType<KotlinCompile>().configureEach { jvmTarget = JVM_21 }` (line 119). If the tooling JAR inherits JVM 21 it fails with `UnsupportedClassVersionError: class file version 65.0 … only recognizes up to 61.0`, the tooling server never starts, `onServerStarted` is never called, and `initializeProject()` never fires — newly created projects silently fail to initialize.

**How to apply:** Any module whose compiled classes end up inside the shadow JAR produced by `tooling/impl` must add an `afterEvaluate {}` block that re-sets `sourceCompatibility = VERSION_17`, `targetCompatibility = VERSION_17`, and Kotlin `jvmTarget = JVM_17`. `afterEvaluate` guarantees the override runs after the root's `subprojects` callbacks.

## Affected modules (fixed)
- `tooling/impl`, `tooling/api`, `tooling/model`, `tooling/events`, `tooling/builder-model-impl`
- `utilities/shared`, `utilities/build-info`

## Propagation chain
`tooling/impl` → `tooling/api` → `tooling/model` → `tooling/builder-model-impl`, `tooling/events`; `tooling/api` → `utilities/shared` → `utilities/build-info`

If new modules are added as dependencies of `tooling/impl`, they also need the `afterEvaluate` Java 17 override.
