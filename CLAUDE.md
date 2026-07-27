# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status: greenfield scaffold

This repo is a **bare Android Studio scaffold with no application code yet**. `app/src/main/` contains only `AndroidManifest.xml` and resources — there is no `MainActivity`, no launcher `<activity>` in the manifest, and the only Kotlin files are the two generated example tests. The APK builds but has nothing to launch.

Everything the app should do is specified in `.docs/`, which is the source of truth for requirements:

- `.docs/Guard_Attendance_Android_PRD.md` — this app (Kotlin, Compose, offline-first attendance capture)
- `.docs/Guard_Attendance_Filament_PRD.md` — the **backend, which lives in a different repository** (Laravel 12 + Sanctum + Filament v4 + PostgreSQL). Read it for the API contract and data model this app must conform to.

Treat the PRDs as the design brief; the current dependency set does **not** yet match the stack they call for (see below).

## Build and test

The wrapper needs a JDK, and **no `java` is on this machine's `PATH`** — `gradlew` fails with `JAVA_HOME is not set`. Set `JAVA_HOME` to the JDK bundled with Android Studio (JBR 21) first:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

Then, from the project root (PowerShell):

```powershell
.\gradlew.bat assembleDebug                 # build debug APK
.\gradlew.bat testDebugUnitTest             # host-side unit tests
.\gradlew.bat connectedDebugAndroidTest     # instrumented tests (needs device/emulator)
.\gradlew.bat lint                          # AGP lint
.\gradlew.bat installDebug                  # install on connected device
.\gradlew.bat release -PdryRun              # preview the next version bump
```

Run a single unit test with `--tests`, which takes a fully-qualified class or method:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.guardapp.ExampleUnitTest"
.\gradlew.bat testDebugUnitTest --tests "com.example.guardapp.ExampleUnitTest.addition_isCorrect"
```

### `core-ktx` is deliberately pinned to 1.17.0

Do not bump it. `androidx.core:core-ktx:1.19.0` (the version the scaffold shipped with) fails `:app:checkDebugAarMetadata` because it requires `compileSdk 37` and AGP 9.1.0, while the project is on `compileSdk 36` / AGP 9.0.1.

Moving to 1.19.0 is a three-part change, not a version bump: AGP 9.1.0 also requires Gradle ≥ 9.3.1 (project is on 9.1.0), and `compileSdk 37` — SDK platform 37 is not installed locally (only 34, 35, 36 are).

## Build configuration

- **AGP 9.0.1 / Gradle 9.1.0 / Java 11 target / minSdk 24 / targetSdk 36.** `app/build.gradle.kts` uses AGP 9's block DSL — `compileSdk { version = release(36) }`, not `compileSdk = 36`.
- **Kotlin compiles with no Kotlin Gradle plugin declared.** AGP 9 has built-in Kotlin support (Kotlin **2.2.10**); `compileDebugKotlin` runs even though `libs.versions.toml` declares only `com.android.application`. Do **not** add `org.jetbrains.kotlin.android` — it is not needed and would double-configure Kotlin.
- **Compose, KSP, Room, and Hilt were verified by building them together** (see `.docs/Implementation_Plan.md` §1). Compose needs only `org.jetbrains.kotlin.plugin.compose:2.2.10` plus `buildFeatures { compose = true }`. KSP (`2.2.10-2.0.2`) additionally requires `android.disallowKotlinSourceSets=false` in `gradle.properties`, because KSP registers generated sources through the `kotlin.sourceSets` DSL that built-in Kotlin forbids.
- **Two libraries have a version floor on this toolchain, and both fail in misleading ways.** Room must be **≥ 2.7.1** — 2.6.1 dies inside the KSP processor with `IllegalStateException: unexpected jvm signature V`. Hilt must be **≥ ~2.58** (verified at `2.60.1`) — 2.57.1 dies at plugin application with `Android BaseExtension not found`, because AGP 9 removed that legacy API. Neither error names the real cause; pin the versions rather than debugging them.
- **All dependencies go through the version catalog** at `gradle/libs.versions.toml` and are referenced as `libs.*`. `settings.gradle.kts` sets `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, so a `repositories {}` block in a module build file is a hard error — add repositories in `settings.gradle.kts` only.
- In `pluginManagement`, `google()` is content-filtered to `com.android.*`, `com.google.*`, and `androidx.*`. Plugins outside those groups (e.g. JetBrains Kotlin, KSP) resolve from `gradlePluginPortal()`/`mavenCentral()`. The `dependencyResolutionManagement` `google()` is unfiltered.
- **Current deps are the Views stack** (`appcompat`, `com.google.android.material`, `Theme.MaterialComponents.DayNight.DarkActionBar`), not the Compose + Material 3 stack the PRD specifies. Note that catalog's `material` alias is Material *Components for Views*, not `androidx.compose.material3`.
- `namespace` and `applicationId` are still the placeholder `com.example.guardapp`.
- Work happens on the `develop` branch; `origin` is `github.com:osapdinjayvee/guard-app`. Untracked files are not recoverable, so check `git status` before deleting.

## Agent orchestration (ruflo)

This project is initialized with [ruflo](https://github.com/ruvnet/ruflo): `.claude/` (agents, commands, skills), `.claude-flow/` (runtime), and `.mcp.json`, which registers the `claude-flow` MCP server. Its tools (`memory_store`, `memory_search`, `swarm_init`, `agent_spawn`) load on demand via ToolSearch and require the server to be approved once per machine.

Reach for a swarm when work genuinely spans subsystems. The attendance transaction is the case that warrants it: a single change can touch the scanner, camera overlay, location, Room schema, the WorkManager sync queue, and the API contract at once. Routine work — one screen, one Gradle edit, one bug — does not.

When delegating, give the agent the constraint, not just the task. The three that bite hardest here:

- The write path is **save locally, then sync**. An agent that reaches for the network first will produce plausible code that loses attendance records offline.
- Selfie metadata must be **burned into the image**, not merely overlaid in the preview.
- Thresholds (GPS accuracy, image quality) come from `GET /api/settings`, not from constants.

`.docs/` is the source of truth for behavior; the backend PRD defines the API contract this app codes against. Point agents at the relevant PRD section rather than letting them infer requirements from the empty scaffold — there is almost no code here yet to infer from.

## Commits and releases

Commit messages **must** follow [Conventional Commits v1.0.0](https://www.conventionalcommits.org/en/v1.0.0/) — `.githooks/commit-msg` rejects anything else. Allowed types: `build`, `chore`, `ci`, `docs`, `feat`, `fix`, `perf`, `refactor`, `revert`, `style`, `test`.

```
feat(scanner): decode checkpoint QR codes
fix(sync): retry failed attendance uploads
refactor!: drop support for minSdk 24
```

The hook lives in the repo and is activated per-clone with `git config core.hooksPath .githooks`. It is already set here. Merge, revert, and `fixup!`/`squash!` messages bypass validation.

The app version lives in `version.properties` (root) and is read by `app/build.gradle.kts`. **Never hand-edit it** — `./gradlew release` owns that file. The task reads every commit since the last `v*` tag, infers the bump, rewrites `version.properties`, regenerates `CHANGELOG.md`, commits `chore(release): vX.Y.Z`, and tags it. Pushing stays manual.

Bump rules: a `!` or a `BREAKING CHANGE:` footer → major, `feat` → minor, anything else → patch. **While the major is still `0`, a breaking change bumps the minor rather than going to `1.0.0`** — leaving 0.x is a deliberate act, not a side effect. `versionCode` increments by one per release.

The task refuses to run on a dirty working tree or when the tag already exists. Preview with `-PdryRun`, which prints the bump and the changelog entry and writes nothing.

`version.properties` also carries **`minSupportedVersionCode`**, the oldest build still allowed to run. `release` copies it forward untouched — raising it is a separate, deliberate act that blocks every handset below it (see below), so do it only when older builds genuinely cannot work against the server any more.

### Shipping a release

The app is sideloaded, not distributed through Play, so it checks for its own updates: `GET https://minsu.edu.ph/app/guard-version.json`, compared against `BuildConfig.VERSION_CODE`. Two files have to reach that directory, and `./gradlew packageUpdate` produces both.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat release              # bumps version.properties + CHANGELOG.md, commits, tags
git push --follow-tags origin develop
.\gradlew.bat packageUpdate        # assembleRelease, then guard.apk + guard-version.json
# upload both from app/build/distribution/ to https://minsu.edu.ph/app/
```

**Upload `guard.apk` before `guard-version.json`.** In the other order, every handset is briefly told to download a file that 404s.

Never hand-write `guard-version.json`. It is generated from `version.properties` and the APK's real SHA-256, which is the only thing keeping the manifest from claiming a version the APK does not carry. `packageUpdate` fails rather than emitting an unsigned APK, because an unsigned one cannot be installed at all and the failure would otherwise only surface on a guard's phone.

Two server-side requirements: the directory must be **HTTPS** (`usesCleartextTraffic="false"` means plain HTTP is refused), and `guard-version.json` must be served with `Cache-Control: no-cache` — a cached manifest means nobody ever hears about the release.

**The signing key is the whole ballgame.** An update can only install over the existing app if it is signed with the same key, so `keystore.properties` (gitignored, read by `app/build.gradle.kts`) must point at the *same* `.jks` forever. Losing it means no guard can ever be updated again — only uninstall and reinstall, which destroys unsynced attendance. Back it up somewhere that is not this machine. A build signed with a different key than the one already installed cannot replace it; those handsets need **Sync now** first, then uninstall and reinstall.

### Editing `gradle/libs.versions.toml` on Windows

Windows PowerShell 5.1's `Set-Content`/`Out-File` default to UTF-16, and `-Encoding utf8` writes a **BOM**. A BOM at the top of the TOML breaks the catalog parser (`Unexpected '﻿'`, build fails before anything compiles). Use the Edit/Write tools, or `[IO.File]::WriteAllText(...)`.

## Target architecture (from the Android PRD)

The app is **offline-first**: a valid attendance record must never be lost to connectivity failure. The write path is *save locally, then sync* — never network-first.

PRD-recommended stack: Kotlin, Jetpack Compose + Material 3, MVVM, CameraX, ML Kit (QR), Fused Location Provider, Room, WorkManager, Retrofit/OkHttp, Hilt. Suggested module split: `authentication`, `home`, `scanner`, `attendance`, `camera`, `location`, `duties`, `history`, `reports`, `settings`, `sync`, `core-network`, `core-database`, `core-ui`.

### The attendance transaction

This flow is the core of the product; most screens exist to serve it.

1. Scan checkpoint QR → resolve to a valid checkpoint (against the local checkpoint cache or the server) before continuing.
2. Choose attendance type: **Time In** or **Time Out**.
3. Front camera opens; GPS is acquired while the preview is live.
4. A **live metadata overlay** shows guard name, date, time, lat/long, GPS accuracy, checkpoint, and attendance type.
5. Capture selfie. The same metadata must be **burned into the saved image as a permanent watermark** — the overlay alone is not sufficient.
6. Duties & Responsibilities acknowledgement gate: submission is blocked until the checkbox is confirmed.
7. Save to Room **first**, enqueue for sync, then upload immediately if online.

An attendance record carries: user id, checkpoint id, attendance type, selfie image, timestamp, latitude, longitude, accuracy, duties-acknowledged flag, sync status, and device id when available.

Sync status is one of `pending`, `syncing`, `synced`, `failed`; failed uploads retry in the background. Sync state is surfaced to the user (pending count on Home, per-record status in History), so it is user-visible state, not an internal detail.

Locally cached data: auth/session metadata, checkpoints, duties, attendance records, sync queue.

### Navigation

Bottom navigation with **Scan QR as the center action**: Home, History, **Scan QR**, Reports, Settings.

### Backend API contract

Laravel + Sanctum token auth over HTTPS; the token is stored securely on-device and the session persists until logout or invalidation.

| Endpoint | Purpose |
| --- | --- |
| `POST /api/login`, `POST /api/logout`, `GET /api/profile` | auth + profile |
| `POST /api/attendance` | submit one attendance record |
| `GET /api/attendance/history`, `GET /api/attendance/report` | current user's records |
| `GET /api/checkpoints`, `POST /api/checkpoint/validate` | checkpoint cache + optional server-side QR validation |
| `GET /api/duties`, `GET /api/announcements`, `GET /api/settings` | content and mobile-facing config |
| `POST /api/sync/attendance` | optional batch upload |

`GET /api/settings` returns server-controlled values that drive client behavior (e.g. GPS accuracy threshold, image quality) — read config from it rather than hardcoding thresholds.

GPS failure handling is deliberately a **business rule, not a fixed behavior**: either block submission or allow it, per configuration. Check the current setting before implementing either branch.

## MVP scope

In: login, home, bottom nav with center Scan QR, QR scanning, Time In/Out, watermarked selfie capture, GPS capture, duties acknowledgement, offline save + background sync, history, reports, PDF export, settings.

Explicitly out of scope: iOS, incident reporting, geofencing, face recognition, payroll, supervisor approval flows.