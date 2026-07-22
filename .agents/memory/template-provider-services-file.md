---
name: Library-module @AutoService unreliable — manual META-INF/services required in core/app
description: Any @AutoService-registered service in a library module needs a manual services file in core/app or ServiceLoader will throw ServiceNotFoundException at runtime
---

# Library-module @AutoService → manual META-INF/services in core/app

## The Rule
Any `@AutoService`-registered service interface whose implementation lives in a **library module**
must also have a manual entry in `core/app/src/main/resources/META-INF/services/<interface-FQN>`.

**Why:** `@AutoService` + kapt/ksp generates `META-INF/services` inside the library module's
build output. These files can fail to be merged into the final APK (R8 shrinking, AGP merging
quirks). Without the entry, `ServiceLoader.findFirstOrThrow()` throws `ServiceNotFoundException`,
crashing the app. This has bitten at least two service interfaces so far.

**How to apply:** Whenever a new `@AutoService`-registered service is added in a library module,
also create `core/app/src/main/resources/META-INF/services/<interface-FQN>` containing the
fully-qualified implementation class name.

**Why:** `@AutoService` + `kapt` generates META-INF/services inside the `templates-impl`
library module's build output. This file can fail to be merged into the final APK in some
build configurations (especially with R8 shrinking). Without the entry, `ServiceLoader` in
`ITemplateProvider.getInstance()` throws `ServiceNotFoundException`, which crashes
`TemplateListFragment` on the main thread during `MainActivity.onStart()` — making the app
permanently unopenable after first setup.

**How to apply:** Any new `@AutoService`-registered service in a library module should also
get a manual entry in `core/app/src/main/resources/META-INF/services/<interface-FQN>` as
belt-and-suspenders.

## Known cases fixed
- `com.itsaky.androidide.templates.ITemplateProvider` → `TemplateProviderImpl` (templates-impl module)
- `com.itsaky.androidide.templates.ITemplateWidgetViewProvider` → `TemplateWidgetViewProviderImpl` (templates-impl module)
- `com.itsaky.androidide.projects.IProjectManager` → `ProjectManagerImpl` (core/projects module) — crash on "open/create project"
- `com.itsaky.androidide.actions.ActionsRegistry` → `DefaultActionsRegistry` (core/actions module) — crash on EditorActivity launch
- `com.itsaky.androidide.xml.resources.ResourceTableRegistry` → `DefaultResourceTableRegistry` (xml/utils module)
- `com.itsaky.androidide.xml.versions.ApiVersionsRegistry` → `DefaultApiVersionsRegistry` (xml/utils module)
- `com.itsaky.androidide.xml.widgets.WidgetTableRegistry` → `DefaultWidgetTableRegistry` (xml/utils module)

## Known working without manual file (do NOT add duplicate)
- `com.itsaky.androidide.lookup.Lookup` — `DefaultLookup` in utilities/lookup; app starts correctly so its @AutoService output is merged properly
- Services whose implementations live directly in `core/app` — @AutoService works for same-module impls

## Defense-in-depth
`TemplateListFragment.reloadTemplates()` also wraps the `getInstance()` call in a try/catch.
If ServiceLoader fails for any reason, an empty template list is shown instead of crashing
the app. This means the user can still open the app and see an error rather than a blank screen.
