---
name: TemplateProviderImpl missing META-INF/services causes MainActivity crash
description: Why ITemplateProvider and ITemplateWidgetViewProvider need manual META-INF/services files, and where to put them
---

# TemplateProvider META-INF/services registration

## The Rule
`ITemplateProvider` and `ITemplateWidgetViewProvider` must have manual `META-INF/services`
entries in `core/app/src/main/resources/META-INF/services/` in addition to `@AutoService`.

**Why:** `@AutoService` + `kapt` generates META-INF/services inside the `templates-impl`
library module's build output. This file can fail to be merged into the final APK in some
build configurations (especially with R8 shrinking). Without the entry, `ServiceLoader` in
`ITemplateProvider.getInstance()` throws `ServiceNotFoundException`, which crashes
`TemplateListFragment` on the main thread during `MainActivity.onStart()` — making the app
permanently unopenable after first setup.

**How to apply:** Any new `@AutoService`-registered service in a library module should also
get a manual entry in `core/app/src/main/resources/META-INF/services/<interface-FQN>` as
belt-and-suspenders.

## Files added
- `core/app/src/main/resources/META-INF/services/com.itsaky.androidide.templates.ITemplateProvider`
  → `com.itsaky.androidide.templates.impl.TemplateProviderImpl`
- `core/app/src/main/resources/META-INF/services/com.itsaky.androidide.templates.ITemplateWidgetViewProvider`
  → `com.itsaky.androidide.templates.impl.TemplateWidgetViewProviderImpl`

## Defense-in-depth
`TemplateListFragment.reloadTemplates()` also wraps the `getInstance()` call in a try/catch.
If ServiceLoader fails for any reason, an empty template list is shown instead of crashing
the app. This means the user can still open the app and see an error rather than a blank screen.
