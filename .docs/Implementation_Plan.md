# Implementation Plan — Guard Attendance Android App

**Status:** Greenfield. Zero application code exists.
**Derived from:** `Guard_Attendance_Android_PRD.md`, `Guard_Attendance_Filament_PRD.md`.
**Method:** Drafted by parallel specification / architecture / planning agents, then adversarially reviewed. Toolchain claims below were **empirically verified** by building throwaway spikes, not inferred.

---

## 1. Verified toolchain facts

The single largest risk in the first draft of this plan was "can the target stack even build on AGP 9.0.1?" That question is now **answered**, by building it. Do not re-litigate these; they are measured, not assumed.

| Question | Answer | How it was established |
| --- | --- | --- |
| Does AGP 9.0.1 compile Kotlin with no `kotlin-android` plugin? | **Yes.** Built-in Kotlin. | `compileDebugKotlin` runs with only `com.android.application` in the catalog. |
| What Kotlin version does AGP 9.0.1 use? | **2.2.10** | Resolved `kotlin-compose-compiler-plugin-embeddable:2.2.10`. |
| Does Compose + Material 3 build on this toolchain? | **Yes.** | Built a Compose `MainActivity` to a working APK. `ComposableSingletons` (compiler-generated) present in the dex. |
| Is a separate `kotlin-android` plugin needed for Compose? | **No.** Only `org.jetbrains.kotlin.plugin.compose:2.2.10`. | Spike built without it. |
| Does KSP work? | **Yes, but only with a flag.** | Fails at configuration: *"Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin."* Requires `android.disallowKotlinSourceSets=false` in `gradle.properties`. |
| Does Room work? | **Only ≥ 2.7.x.** | Room **2.6.1 fails** under KSP2/Kotlin 2.2 with `IllegalStateException: unexpected jvm signature V`. **Room 2.7.1 succeeds** and generates `GuardDatabase_Impl.kt`. |
| Does Hilt work? | **Only ≥ ~2.58.** | Hilt **2.57.1 fails**: `Failed to apply plugin 'com.google.dagger.hilt.android' > Android BaseExtension not found` — AGP 9 removed the legacy extension API the plugin reads. **Hilt 2.60.1 succeeds**; `Hilt_GuardApp`, `Hilt_MainActivity`, and `HomeViewModel_HiltModules` all land in the APK. |

### Verified-good pin set

```toml
kotlin      = "2.2.10"          # must match AGP 9.0.1's built-in Kotlin
ksp         = "2.2.10-2.0.2"    # Kotlin-version-locked
composeBom  = "2024.09.00"
activityCompose = "1.9.3"
room        = "2.7.1"           # 2.6.1 is BROKEN here
hilt        = "2.60.1"          # 2.57.1 is BROKEN here (no AGP 9 support)
coreKtx     = "1.17.0"          # do not bump: 1.19.0 needs compileSdk 37 + AGP 9.1.0 + Gradle 9.3.1
```

This exact set was built together — Compose + KSP + Room + Hilt in one app, with an `@HiltAndroidApp` application, a `@Module` providing Room, an `@Inject constructor` repository, a `@HiltViewModel`, and `hiltViewModel()` inside a composable. It produces a working APK.

```properties
# gradle.properties — required for KSP (Room/Hilt/Moshi codegen) under built-in Kotlin
android.disallowKotlinSourceSets=false
```

`org.jetbrains.kotlin.plugin.compose` and `com.google.devtools.ksp` resolve from `gradlePluginPortal()`/`mavenCentral()`, **not** the content-filtered `google()` in `settings.gradle.kts`. No repository changes needed.

**No toolchain spikes remain.** Compose, KSP, Room, and Hilt are all verified together on this exact toolchain. Two of the four needed a version floor that nothing in the docs would have told you about, and both failed in ways that look like unrelated bugs: Room 2.6.1 dies inside the KSP processor, and Hilt 2.57.1 dies at plugin application. **Pin the versions above rather than picking "the version the tutorial used."**

**Ceiling warning:** every new androidx dependency must be checked against `compileSdk 36`. Recent releases increasingly demand 37 and will fail `checkDebugAarMetadata`, exactly as `core-ktx:1.19.0` does. Add dependencies in small batches and run `assembleDebug` after each.

---

## 2. Architecture summary

**Single-module, package-by-feature** for the MVP. The PRD's 14 "suggested modules" are realized as *packages*, not Gradle modules. Rationale: the toolchain is new, and each extra Gradle module multiplies the built-in-Kotlin/KSP/Compose wiring risk. Enforce the boundary by rule — `feature.*` may depend on `domain` and `core.*`, never on another `feature.*` — so extraction later is a move-refactor, not a rewrite.

*(Dissent worth recording: the reviewer argued module boundaries are the best mechanism to enforce the offline-first invariant, and that single-module is only defensible as an MVP shortcut. Revisit once Hilt is proven and build times or team parallelism actually hurt.)*

```
com.<org>.guardapp
├── core/{network,database,datastore,security,location,camera,sync,ui,common}
├── domain/{model,repository}          # framework-free
├── data/                              # repository impls + mappers
└── feature/{auth,home,scanner,attendance,selfie,duties,history,reports,settings}
```

**Room is the source of truth. The network is a synchronization detail.** The UI observes Room via `Flow` and never renders directly from a network response. Home's pending-sync count and History's per-record status are therefore just queries — no extra bookkeeping.

### The attendance transaction

```
scan QR → resolve checkpoint (local cache first) → pick Time In/Out
        → front camera + GPS acquired live → overlay shows metadata
        → shutter: SNAPSHOT gps+timestamp (not stale preview values)
        → burn metadata into the bitmap → save JPEG locally
        → duties acknowledgement gate (blocks submit)
        → Room INSERT (syncStatus=PENDING)  ◄── COMMITTED. Record is durable.
        → enqueue unique work
        → worker claims → uploads → SYNCED / FAILED(+backoff)
```

Two invariants that agents and humans both get wrong:

1. **Save locally, then sync.** There is exactly *one* upload path — the worker queue. "Immediate upload when online" is the same `enqueueUniqueWork` call whose network constraint is already satisfied. A second, direct-POST code path is precisely what creates duplicate records.
2. **The watermark is burned into pixels.** Capture in-memory (`OnImageCapturedCallback`), draw the metadata onto a mutable `Bitmap` via `Canvas`, *then* compress and write. EXIF is invisible and strippable and does **not** satisfy PRD §7.5. Do not use `takePicture(OutputFileOptions, …)` for the evidence image — it writes straight to disk with no burn-in hook. Handle rotation and the front-camera horizontal mirror, and downscale before drawing to avoid OOM on high-MP sensors.

### Sync correctness (this is where the project most likely fails)

The queue drains via a compare-and-swap claim:

```sql
UPDATE attendance SET syncStatus='SYNCING'
 WHERE id=:id AND syncStatus IN ('PENDING','FAILED')
```

Rows-affected = 0 means another worker owns it. This prevents two *concurrent* workers double-sending. It does **not** solve either of these:

- **Stuck `SYNCING` orphan.** Process death mid-upload leaves a row in `SYNCING`, which the predicate above never re-selects. The record is silently lost — the exact outcome offline-first exists to prevent. **Required:** a startup + periodic sweep returning stale `SYNCING` rows to `PENDING`.
- **Lost response.** Server commits, the 200 is dropped by the flaky connectivity this app is *designed for*, client marks `FAILED` and retries → duplicate row on the server. A client-side idempotency key fixes this **only if the backend enforces it.** The backend PRD §8.3 defines no such field and no unique constraint. This is a **cross-repo contract change**, not a client decision (see T-9).

Generate the idempotency key **once**, persist it with the record before the first send, and reuse it on every retry.

**Use a plain `OneTimeWorkRequest` with `NetworkType.CONNECTED`, not expedited work.** On API 24–30 (`minSdk` is 24) expedited work degrades to a foreground service requiring a notification and subject to quota. A constrained one-time request runs promptly with no notification.

**Token storage:** do *not* add `androidx.security:security-crypto` — it is deprecated with no successor. Use AndroidKeyStore AES/GCM (supported at API 24) over DataStore, or Tink.

**PDF export:** `android.graphics.pdf.PdfDocument` needs no dependency. It is a bare Canvas API, so tables, pagination, and headers are hand-drawn — sufficient, but not cheap. Budget accordingly.

---

## 3. Phases

Ordered so the riskiest unknowns die first. Phase 0 shrank considerably because the toolchain spikes are already done.

| Phase | Goal | Exit criteria | Size |
| --- | --- | --- | --- |
| **0 — Foundation** | Real app skeleton on the verified pin set | Launcher activity renders Compose M3; Hilt boots; Room+Retrofit compile; package renamed off `com.example`; CI green | **M** |
| **1 — Contract & unblock** | Freeze the API contract; stub server | OpenAPI spec committed; auth/image-format/timestamp/idempotency decided; MockWebServer serves every endpoint | **M** |
| **2 — Domain & data** | The offline-first spine | Room schema + repositories; local-first write path; network layer against stub; secure token storage | **L** |
| **3 — Attendance transaction** | The product | scan → type → GPS+preview → burned-in selfie → duties gate → local save → enqueue, offline **and** online | **L** |
| **4 — Sync** | Durable queue drain | pending→syncing→synced/failed; stale-SYNCING reclaim; survives process death; state visible in UI | **M** |
| **5 — Surrounding screens** | Rest of MVP surface | Home, History, Reports+PDF, Settings | **L** (parallel) |
| **6 — Hardening & release** | Real backend, ship | staging integration; edge cases; E2E in CI; tagged build | **M** |

Phase 1 runs **concurrently with Phase 0** — it is coordination work needing no app code.

---

## 4. Tasks

| ID | Title | Phase | Size | Depends on | Definition of done |
| --- | --- | --- | --- | --- | --- |
| T-1 | Rename package off `com.example.guardapp` | 0 | S | — | `namespace` + `applicationId` are a real id; `assembleDebug` + `testDebugUnitTest` green |
| T-2 | Apply verified pin set; enable Compose | 0 | S | T-1 | Catalog gains kotlin 2.2.10 / compose plugin / KSP 2.2.10-2.0.2 / Room 2.7.1; `android.disallowKotlinSourceSets=false` set; builds |
| T-3 | `MainActivity` + launcher activity + M3 theme | 0 | M | T-2 | App installs and shows a Compose screen; Views/AppCompat theme retired |
| T-4 | Wire Hilt (2.60.1) | 0 | S | T-3 | `@HiltAndroidApp` boots; `@HiltViewModel` + `hiltViewModel()` resolve. Verified to work — do not use Hilt < 2.58 |
| T-5 | Room + Retrofit on classpath, smoke DAO + service | 0 | S | T-4 | `@Entity`/`@Dao` generate `_Impl`; Retrofit interface compiles |
| T-6 | Bottom nav scaffold, center Scan QR | 0 | M | T-3 | Home · History · **Scan** · Reports · Settings route to placeholders |
| T-7 | CI: build + unit test + lint | 0 | S | T-1 | Runs `assembleDebug`, `testDebugUnitTest`, `lint` on JDK 21; green on a PR |
| T-8 | API contract (OpenAPI) for all endpoints | 1 | M | — | Every endpoint specified incl. pagination; committed to `.docs/`; backend sign-off |
| T-9 | Ratify cross-repo decisions | 1 | S | T-8 | Written: token lifecycle · **multipart vs base64** · error envelope · **timestamp authority** · GPS-failure rule · `/api/settings` keys · **`client_uuid` idempotency with a server UNIQUE constraint** |
| T-10 | MockWebServer stub for every endpoint | 1 | M | T-8 | Success + error responses behind a debug base-URL switch; usable by app and tests |
| T-11 | Room schema + sync state machine | 2 | M | T-5, T-8 | Entities/DAOs; `syncStatus` enum; indices on `(syncStatus,nextAttemptAt)`; migration strategy; instrumented tests |
| T-12 | Network layer: Retrofit + auth interceptor + error mapping | 2 | M | T-5, T-9, T-10 | Typed results; `Authorization` redacted in logs; logging `NONE` in release; runs against stub |
| T-13 | Secure token storage (Keystore AES/GCM + DataStore) | 2 | S | T-4, T-12 | Survives restart; cleared on logout; **no `security-crypto`** |
| T-14 | Repositories: attendance, checkpoints, duties, settings | 2 | M | T-11, T-12 | Local-first reads; **write path is Room-then-enqueue**; thresholds read from `/api/settings`; unit-tested with fakes |
| T-15 | Login + logout | 2 | M | T-13, T-6 | Sanctum token stored; routes to Home; error states |
| T-16 | Runtime permissions (camera, fine location) | 0/3 | S | T-3 | Rationale + denied handling; reusable gate composable |
| T-17 | QR scanner + checkpoint resolution | 3 | M | T-14, T-16 | Resolves via local cache first; rejects unknown/disabled |
| T-18 | Attendance type selection | 3 | S | T-17 | Time In / Time Out carried forward |
| T-19 | Selfie preview + live GPS + metadata overlay | 3 | L | T-17, T-16 | Front camera; all 8 overlay fields; retake |
| T-20 | **Burn watermark into saved image** | 3 | M | T-19 | Instrumented test decodes the *saved file* and asserts metadata pixels exist. Reviewed specifically for preview-vs-persisted confusion |
| T-21 | GPS capture + failure rule from settings | 3 | S | T-14, T-19 | Branch chosen at runtime from config; no hardcoded thresholds |
| T-22 | Duties acknowledgement gate | 3 | M | T-14, T-19 | Cached duties render offline; submit blocked until acknowledged; `dutiesVersionId` recorded |
| T-23 | Submission: validate → Room → enqueue | 3 | M | T-20, T-21, T-22, T-14 | Airplane-mode submit persists as `PENDING`; app kill does not lose it |
| T-24 | Sync worker: claim, upload, retry/backoff | 4 | M | T-23, T-11 | CAS claim; per-record `nextAttemptAt`; permanent 4xx stops auto-retry; `OneTimeWorkRequest`, not expedited |
| T-25 | **Stale-`SYNCING` reclaim sweep** | 4 | S | T-24 | Startup + periodic sweep returns orphaned `SYNCING` rows to `PENDING`; test kills the process mid-upload |
| T-26 | Sync state surfaced (Home count, History status) | 4 | S | T-24, T-28, T-30 | Flows off Room; online/offline indicator |
| T-27 | Manual "Sync now" | 4 | S | T-24 | Enqueues a drain; reports outcome; can retry permanent failures |
| T-28 | Home dashboard | 5 | M | T-14, T-15 | Name, today's status, last record, pending count, scan CTA, announcements |
| T-29 | History list + detail | 5 | M | T-14 | All PRD fields; paginated; local-first |
| T-30 | Reports: daily/weekly/monthly/custom | 5 | M | T-14 | Range selection; source-of-truth decision from T-9 honored |
| T-31 | PDF generation + share | 5 | M | T-30 | `PdfDocument`: title, range, filters, table, generation timestamp |
| T-32 | Settings screen | 5 | M | T-13, T-14 | Profile, logout, sync now, offline info, camera quality, about |
| T-33 | Test suite incl. offline E2E | 6 | L | T-23, T-24, T-25, T-29 | Airplane-mode capture → `PENDING` → reconnect → `SYNCED`; process-death and lost-response cases covered |
| T-34 | Real-backend integration | 6 | M | T-33, *backend repo* | Staging base URL; contract drift reconciled; idempotency verified end to end |
| T-35 | Release hardening + tagged build | 6 | S | T-34 | R8 config; edge cases; `./gradlew release` |

### Critical path

```
T-1 → T-2 → T-3 → T-4 → T-5 → T-11 → T-14 → T-17 → T-19 → T-20 → T-23 → T-24 → T-25 → T-33 → T-34 → T-35
```

The contract chain `T-8 → T-9 → T-10` is a parallel prerequisite of T-12/T-14. **If it slips, it moves onto the critical path.** T-34 is gated by an external team.

All of Phase 0 is now de-risked: T-2 and T-4 are a known-good pin set, not spikes. The riskiest nodes on the path are **T-20** (evidence integrity — the watermark must be in the pixels) and **T-24/T-25** (record durability — the orphaned-`SYNCING` and lost-response holes). Those are correctness problems, not toolchain problems, and they will not announce themselves in a demo.

### Parallelization

- Phase 0 and Phase 1 run concurrently from day one.
- Once **T-14** lands, fan out: T-15, T-28, T-29, T-30→T-31, T-32 are mutually independent.
- The capture chain T-17 → T-19 → T-20 → T-23 is inherently serial.
- T-30/T-31 (Reports/PDF) never touch camera or sync — fully parallel to Phases 3–4.

---

## 5. Backend dependency

The Laravel/Filament backend is a **separate repository**. Nothing in this repo proves whether it exists yet — treat its readiness as an **assumption with a blocking dependency**, not a fact, and verify externally.

Strategy: **contract-first, stub-backed.** The Android team drafts the OpenAPI spec (it has both PRDs), gets backend sign-off, and builds everything against a MockWebServer stub. Phases 2–5 complete with zero real backend. T-34 swaps the base URL and reconciles drift.

Decisions that must be settled **before** networked code (T-9), because retrofitting them is expensive:

- **Idempotency.** Add `client_uuid` to the `attendance` table with a `UNIQUE` constraint; make `POST /api/attendance` return the existing record on conflict. *Without this, duplicate attendance rows on lost responses are guaranteed.* The backend PRD currently specifies neither.
- **Image upload format** — multipart vs base64. Changes the Retrofit signature, worker payload handling, and memory footprint. Multipart is the safer default; base64 inflates payloads ~33%.
- **Timestamp authority** — offline capture means the *device* must stamp `attendance_timestamp`; the server should record receipt time separately. Make it explicit or history and reports will be subtly wrong. Device clocks can be tampered with; the server should flag large deltas.
- **Auth lifecycle** — does a Sanctum token expire? What does a 401 during background sync do to the queue? (It must not drop it.)
- **Checkpoint validation** — is the cached `GET /api/checkpoints` list authoritative offline, or is `POST /api/checkpoint/validate` required? The latter breaks offline scanning.
- **`GET /api/settings` keys** — GPS accuracy threshold, image quality, and the GPS-failure rule. Wrong keys silently defeat the "no hardcoded thresholds" requirement.
- **Server-side rejection at sync time.** `POST /api/attendance` validates the checkpoint and type — but the record was already committed locally and shown to the guard as done. The sync state machine has **no `REJECTED` state**. Add one, or a locally-valid record can be silently dropped.

---

## 6. Top risks

| # | Risk | Mitigation |
| --- | --- | --- |
| R-1 | **Duplicate / lost attendance records in sync.** The most likely way this project fails. Lost-response → duplicate; process-death → orphaned `SYNCING` → record lost. | Server-enforced `client_uuid` uniqueness (T-9); stale-`SYNCING` reclaim (T-25); explicitly test both in T-33. Neither is fixed by the CAS claim alone. |
| R-2 | **Watermark only overlaid, not burned in.** Passes every happy-path demo; makes the evidence worthless. | T-20 has an instrumented test that decodes the persisted file. Review for preview-vs-persisted confusion. |
| R-3 | **Network-first write path.** Loses records offline. | Enforced in T-14/T-23; airplane-mode E2E in T-33. |
| R-4 | Backend lags indefinitely. | Contract-first + stub (§5). Freeze the contract early so drift is small. |
| R-5 | androidx version ceiling vs `compileSdk 36`. | Add deps in small batches; `assembleDebug` after each; keep the known-good pin set. |
| R-6 | ~~Hilt vs AGP 9~~ **Retired.** Verified working at 2.60.1. | Pin `hilt = "2.60.1"`. Versions < 2.58 fail with `Android BaseExtension not found`. |
| R-7 | Hardcoded thresholds instead of `/api/settings`. | Settings repo is the only source; review rule: no numeric threshold constants in capture code. |
| R-8 | Front-camera mirror / OOM in the burn-in path. | Normalize rotation, handle horizontal flip, downscale before drawing. |

---

## 7. Open questions for the product owner

These are underspecified in the PRDs and **block** the tasks named:

1. **GPS failure** (blocks T-21): default when the setting is unfetched? Does "unavailable" mean no fix at all, or accuracy worse than threshold? What is stored under "allow" — nulls, last-known, sentinel?
2. **Duplicate / out-of-order attendance** (blocks T-9, T-23): is a second consecutive Time In rejected? By client or server?
3. **Selfie retention** (blocks T-11): delete the local image after `SYNCED`, keep N days, or forever? History detail needs it — if deleted, detail must refetch.
4. **Reports source** (blocks T-30): local Room (works offline, misses server-side edits) or `GET /api/attendance/report` (needs connectivity)? Do reports include unsynced pending records?
5. **Stale duties** (blocks T-22): a guard may acknowledge duties cached days ago while the admin has published new ones. Track a duties version and submit it with the record?
6. **Cache freshness** (blocks T-14): TTL and refresh trigger for checkpoints, duties, settings, announcements.
7. **Multi-account on a shared device** (blocks T-15): can a guard log out while another guard's records are still queued?
8. **`device_id` source** (blocks T-11): app-generated UUID, `ANDROID_ID`, or omit for MVP?
9. **Performance budgets** (blocks T-33): NFRs say "feels fast" with no numbers. Camera open ≤ 1.5s? GPS soft-timeout ≤ 10s?
10. **Which "optional" items are in MVP?** Announcements, dark mode, batch sync, `checkpoint/validate` are marked optional in the PRDs but appear in screen specs.
