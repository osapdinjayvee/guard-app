# API decisions requiring backend sign-off

`openapi.yaml` is a **draft written by the Android side** so client work can proceed against a
stub before the backend exists. The seven decisions below are baked into that draft. Each has a
recommendation and the reason for it. Changing any of them after T-12 (the network layer) is
written is expensive; changing 1, 2, or 3 after launch means migrating data.

Two of these — **1** and **7** — are not style preferences. Getting them wrong produces
duplicated or silently lost attendance records, which is the failure this whole app exists to
prevent.

---

## 1. Idempotency — `client_uuid` with a UNIQUE constraint

**Recommendation:** add `client_uuid` (UUID) to the `attendance` table with a `UNIQUE` index.
`POST /api/attendance` returns `201` the first time and `200` with the existing record for a
repeated `client_uuid`.

**Why.** The app is designed for bad connectivity. Eventually the server will commit a record
and its `201` response will be dropped in transit. The client, seeing a failure, retries — and
without a uniqueness constraint the server writes a **second** attendance row. HR reports then
double-count, and the duplicate is expensive to reconcile after the fact because both rows are
legitimate-looking.

A client-side key alone fixes nothing: it only works if the server enforces it. The backend PRD
(§8.3) currently defines no such column, so this is a **cross-repo schema change**, not a client
detail.

The client generates the UUID once, persists it with the record *before* the first send, and
reuses it on every retry. Regenerating per attempt would defeat the whole mechanism.

## 2. Selfie upload — `multipart/form-data`, not base64

**Recommendation:** `multipart/form-data`.

**Why.** Base64 inflates the payload by roughly a third and forces the whole image through
memory as a string on a phone that is already holding a full-resolution bitmap. Multipart also
lets the server stream the file to storage. The only argument for base64 is JSON convenience,
and it is not worth the memory and bandwidth on a field device.

## 3. Timestamp authority — device stamps `captured_at`, server stamps `received_at`

**Recommendation:** both, stored separately. Never overwrite `captured_at` with server time.

**Why.** Attendance is captured offline and may upload hours later, so server receipt time is
not when the guard was at the checkpoint. But the device clock can be wrong, or deliberately
changed to fake attendance — the exact fraud the PRD wants to prevent.

Keeping both gives the audit trail a check: a large `received_at - captured_at` delta is normal
for offline capture, but a `captured_at` in the future, or a delta that disagrees with the
device's uptime, is a tamper signal. **The server should flag these, not silently correct
them.** Correcting destroys the evidence.

## 4. GPS failure policy, and what a 401 does to the sync queue

**Recommendation:** `gps_failure_policy` in `GET /api/settings`, defaulting to `block` when the
setting has never been fetched. "Failure" means no fix within `gps_timeout_seconds`, **or** a
fix worse than `gps_accuracy_threshold_m`.

The PRD explicitly leaves this open ("prevent submission, or allow submission based on
configured business rule"). Defaulting to `block` fails safe: an attendance record without a
location is weak evidence, and a guard who cannot submit will report the problem, whereas
silently accepted location-less records will not be noticed until an audit.

**Separately:** a `401` during background sync must **not** discard the queue. Records stay
`PENDING` until the guard re-authenticates. Dropping them loses captured evidence.

## 5. Checkpoint validation — local cache is authoritative for scanning

**Recommendation:** the client resolves a scanned code against its cached
`GET /api/checkpoints` list. `POST /api/checkpoint/validate` is an optional online re-check and
must never be required to complete a scan.

**Why.** Requiring a server round-trip to scan a QR code contradicts offline-first: a guard at a
basement checkpoint with no signal could not record attendance at all. The server re-validates
at sync time anyway (see 7).

## 6. Duties versioning — submit `duties_version_id`

**Recommendation:** `GET /api/duties` returns the revision `id`; the client submits it back as
`duties_version_id`.

**Why.** A guard may be offline for days and acknowledge a *cached* duties revision while the
admin has since published new ones. Without the version, the record asserts "the guard
acknowledged their duties" without saying which duties — which makes the acknowledgement
useless in a dispute.

## 7. Server-side rejection at sync time needs a `REJECTED` state

**Recommendation:** `POST /api/attendance` returns `409` with a machine-readable `error.code`
(`checkpoint_disabled`, `checkpoint_unknown`, `out_of_sequence`). The client moves the record to
a `REJECTED` state, keeps the image, and surfaces it to the guard.

**Why.** The server validates the checkpoint and attendance type — but by then the record has
already been committed locally and shown to the guard as done. The sync state machine in the
PRD has only `pending / syncing / synced / failed`, and none of those fit: `failed` implies
"retry", and retrying a permanently invalid record loops forever.

**Also unresolved and needed for 7:** is a second consecutive `TIME_IN` an error? The PRD never
says. If it is, the server must reject it (`out_of_sequence`) *and* the client should prevent
it at capture time, because a rejection after the guard has walked away is useless.

---

## Still open, not blocking the contract

These affect the client only, and can be settled before their task starts:

- **Selfie retention** after `SYNCED` — delete, keep N days, or keep forever? Blocks T-11's
  schema. History detail needs the image, so deleting means refetching `selfie_url`.
- **Reports source** — local Room (works offline, misses records synced from another device) or
  `GET /api/attendance/report` (needs connectivity)? Blocks T-30.
- **Cache freshness** — TTL and refresh trigger for checkpoints, duties, settings.
- **`device_id`** — app-generated UUID in secure storage, `ANDROID_ID`, or omit for MVP?
- **Multi-account on a shared device** — may a guard log out while another guard's records are
  still queued?
- **Performance budgets** — the NFRs say "feels fast" with no numbers.
