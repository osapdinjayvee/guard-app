# GuardApp

Android app for security guards to record attendance with QR checkpoint scanning, selfie proof, and real-time GPS — designed to work offline and sync in the background.

GuardApp is the field-facing half of the Guard Attendance System. The admin panel and API (Laravel 12 + Sanctum + Filament v4 + PostgreSQL) live in a separate repository.

> **Status: greenfield.** This repo is currently an Android Studio scaffold. There is no `MainActivity`, no launcher activity, and no feature code — only generated resources and two example tests. The APK builds, but there is nothing to launch. Requirements live in [`.docs/`](.docs/).

## Requirements

| | |
| --- | --- |
| JDK | 21 (the JBR bundled with Android Studio works) |
| Android SDK | Platform 36 |
| Gradle | 9.1.0, via the included wrapper |
| Android Gradle Plugin | 9.0.1 |
| minSdk / targetSdk | 24 / 36 |

## Getting started

The Gradle wrapper needs a JDK on `JAVA_HOME`. If you don't have one installed system-wide, point it at Android Studio's bundled runtime:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

Then build:

```powershell
.\gradlew.bat assembleDebug
```

### Known issue: the scaffold does not build as-is

The build fails at `:app:checkDebugAarMetadata`. `gradle/libs.versions.toml` pins `coreKtx = "1.19.0"`, but `androidx.core:core-ktx:1.19.0` requires `compileSdk 37` and AGP 9.1.0, while this project targets `compileSdk 36` on AGP 9.0.1.

The one-line fix is to pin an older `core-ktx` in `gradle/libs.versions.toml`:

```toml
coreKtx = "1.17.0"
```

Upgrading AGP instead is a larger change than it looks: AGP 9.1.0 also requires Gradle 9.3.1+ and SDK platform 37.

> When editing `libs.versions.toml` on Windows, don't write it with PowerShell's `Set-Content -Encoding utf8` — it emits a UTF-8 BOM, and a BOM breaks Gradle's TOML parser.

## Common commands

```powershell
.\gradlew.bat assembleDebug                 # build debug APK
.\gradlew.bat installDebug                  # install on a connected device
.\gradlew.bat testDebugUnitTest             # host-side unit tests
.\gradlew.bat connectedDebugAndroidTest     # instrumented tests (device/emulator required)
.\gradlew.bat lint                          # Android lint
```

Run a single unit test by fully-qualified class or method:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.guardapp.ExampleUnitTest"
.\gradlew.bat testDebugUnitTest --tests "com.example.guardapp.ExampleUnitTest.addition_isCorrect"
```

## How attendance works

The app is **offline-first**: a valid attendance record is saved to the local database before any network call, so it can never be lost to a dropped connection.

1. The guard scans a checkpoint QR code, which must resolve to a known checkpoint.
2. They pick **Time In** or **Time Out**.
3. The front camera opens and GPS is acquired while the preview is live.
4. Guard name, date, time, latitude/longitude, GPS accuracy, checkpoint, and attendance type are shown as a live overlay — and burned into the captured image as a permanent watermark.
5. The guard acknowledges their Duties & Responsibilities. Submission is blocked until they do.
6. The record is written locally, queued, and uploaded immediately if the device is online.

Queued records move through `pending → syncing → synced`, or land in `failed` and are retried in the background. Sync state is visible to the guard: a pending count on Home, and a per-record status in History.

Navigation is a bottom bar with Scan QR as the center action: **Home · History · Scan QR · Reports · Settings**.

## Project layout

```
.docs/                  Product requirements (source of truth)
app/                    The Android application module
gradle/libs.versions.toml   Version catalog — all dependencies are declared here
```

Dependencies are managed exclusively through the version catalog and referenced as `libs.*`. Repositories are declared in `settings.gradle.kts`; because the build sets `FAIL_ON_PROJECT_REPOS`, adding a `repositories {}` block to a module build file is an error.

## Planned stack

The scaffold currently ships the Views toolkit (AppCompat + Material Components). The target stack, per the PRD, is:

Kotlin · Jetpack Compose · Material 3 · MVVM · CameraX · ML Kit (QR) · Fused Location Provider · Room · WorkManager · Retrofit/OkHttp · Hilt

## Backend API

The app talks to a Laravel backend over HTTPS using Sanctum token authentication.

| Endpoint | Purpose |
| --- | --- |
| `POST /api/login` · `POST /api/logout` · `GET /api/profile` | Authentication and profile |
| `POST /api/attendance` | Submit an attendance record |
| `GET /api/attendance/history` · `GET /api/attendance/report` | The current guard's records |
| `GET /api/checkpoints` · `POST /api/checkpoint/validate` | Checkpoint cache and QR validation |
| `GET /api/duties` · `GET /api/announcements` · `GET /api/settings` | Content and mobile configuration |
| `POST /api/sync/attendance` | Batch upload (optional) |

`GET /api/settings` supplies server-controlled values such as the GPS accuracy threshold and image quality. Read them from the API rather than hardcoding them.

## Scope

**MVP:** login, home, bottom navigation, QR scanning, Time In/Out, watermarked selfie capture, GPS capture, duties acknowledgement, offline save and background sync, history, reports, PDF export, settings.

**Out of scope:** iOS, incident reporting, geofencing, face recognition, payroll, supervisor approval workflows.

## Documentation

- [`.docs/Guard_Attendance_Android_PRD.md`](.docs/Guard_Attendance_Android_PRD.md) — this app
- [`.docs/Guard_Attendance_Filament_PRD.md`](.docs/Guard_Attendance_Filament_PRD.md) — backend and admin panel
- [`CLAUDE.md`](CLAUDE.md) — guidance for Claude Code