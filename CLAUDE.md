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
```

Run a single unit test with `--tests`, which takes a fully-qualified class or method:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.guardapp.ExampleUnitTest"
.\gradlew.bat testDebugUnitTest --tests "com.example.guardapp.ExampleUnitTest.addition_isCorrect"
```

### The scaffold does not build as checked in

`assembleDebug` fails at `:app:checkDebugAarMetadata`. The catalog pins `coreKtx = "1.19.0"`, but `androidx.core:core-ktx:1.19.0` requires `compileSdk 37` and AGP 9.1.0, while the project is on `compileSdk 36` / AGP 9.0.1.

Fix by pinning `coreKtx = "1.17.0"` in `gradle/libs.versions.toml`. That is verified to make both `assembleDebug` and `testDebugUnitTest` pass unchanged.

Do **not** reach for the AGP upgrade instead unless you intend to do all three steps: AGP 9.1.0 requires Gradle ≥ 9.3.1 (project is on 9.1.0) *and* `compileSdk 37`, and SDK platform 37 is not installed locally (only 34, 35, 36 are).

## Build configuration

- **AGP 9.0.1 / Gradle 9.1.0 / Java 11 target / minSdk 24 / targetSdk 36.** `app/build.gradle.kts` uses AGP 9's block DSL — `compileSdk { version = release(36) }`, not `compileSdk = 36`.
- **Kotlin compiles with no Kotlin Gradle plugin declared.** AGP 9 has built-in Kotlin support; `compileDebugKotlin` runs even though `libs.versions.toml` declares only `com.android.application`. Adding Compose will require explicitly declaring the Kotlin and Compose compiler plugins — verify how they interact with AGP's built-in Kotlin before assuming the usual `kotlin-android` setup applies.
- **All dependencies go through the version catalog** at `gradle/libs.versions.toml` and are referenced as `libs.*`. `settings.gradle.kts` sets `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, so a `repositories {}` block in a module build file is a hard error — add repositories in `settings.gradle.kts` only.
- In `pluginManagement`, `google()` is content-filtered to `com.android.*`, `com.google.*`, and `androidx.*`. Plugins outside those groups (e.g. JetBrains Kotlin, KSP) resolve from `gradlePluginPortal()`/`mavenCentral()`. The `dependencyResolutionManagement` `google()` is unfiltered.
- **Current deps are the Views stack** (`appcompat`, `com.google.android.material`, `Theme.MaterialComponents.DayNight.DarkActionBar`), not the Compose + Material 3 stack the PRD specifies. Note that catalog's `material` alias is Material *Components for Views*, not `androidx.compose.material3`.
- `namespace` and `applicationId` are still the placeholder `com.example.guardapp`.
- This directory is **not a git repository**, so edits are not recoverable via git. Be careful with destructive changes.

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