# AndroidIDE

An IDE to develop real, Gradle-based Android applications on Android devices. Licensed under GPLv3.

> **Note:** This project is no longer actively maintained by its original authors.

## Project Overview

AndroidIDE is a large, multi-module Android application written in Kotlin and Java. It runs **on an Android device**, not as a web app — it cannot be run directly on Replit as a web server.

## Stack

- **Language:** Kotlin / Java
- **Build system:** Gradle (multi-module)
- **Target platform:** Android devices

## Key modules (top-level)

- `app/` — Main application module
- `editor/` — Code editor (based on sora-editor)
- `lsp/` — Language Server Protocol integration (Java, XML)
- `terminal/` — Terminal emulator (based on Termux)
- `gradle-plugin/` — Custom Gradle plugin
- `common/`, `shared/`, `resources/` — Shared utilities and assets

## How to build

Building requires the Android SDK and is intended to be done from an Android development environment or CI. A full Gradle build on Replit is not recommended without significant environment setup.

```bash
./gradlew assembleDebug
```

## Links

- [Documentation](https://docs.androidide.com/)
- [GitHub](https://github.com/AndroidIDEOfficial/AndroidIDE)

## User preferences

_None recorded yet._
