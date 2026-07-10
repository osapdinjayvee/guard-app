# PRD — Filament Admin Panel & Laravel Backend
# Guard Attendance System (Admin / Web / API)

**Document Version:** 1.0  
**Backend:** Laravel 12 + Sanctum + Filament v4  
**Database:** PostgreSQL  
**Purpose:** Admin panel and backend PRD for the Guard Attendance System  

---

# 1. Product Overview

The Filament Admin Panel and Laravel backend serve as the administration, reporting, and data management layer for the Guard Attendance System.

This component is responsible for:
- authenticating mobile users,
- managing guards and checkpoint QR codes,
- receiving attendance submissions from the Android app,
- storing selfie / timestamp / location attendance evidence,
- generating reports,
- managing duties and responsibilities content,
- exposing settings and reference data to the mobile app.

---

# 2. Product Goals

## Primary Goals
- Provide a secure API for the Android app.
- Provide an admin panel for managing guards, checkpoints, attendance, reports, and duties.
- Store and retrieve attendance records reliably.
- Support PDF and operational reporting.
- Provide system configuration for mobile behavior and attendance rules.

## Secondary Goals
- Provide visibility into attendance activity per guard and checkpoint.
- Support operational auditing and future expansion.
- Keep the admin experience efficient for HR, supervisors, and administrators.

---

# 3. User Roles

## Administrator
Can manage the entire system.

## Security Supervisor / Operations
Can review attendance, checkpoints, and reports.

## HR / Reporting User
Can review attendance summaries and export reports.

---

# 4. Scope

## In Scope
- Laravel Sanctum authentication for mobile
- Filament admin dashboard
- guard account management
- checkpoint QR management
- attendance management
- duties & responsibilities content management
- announcements management (optional but recommended)
- reports and exports
- settings/configuration for mobile app
- API endpoints for Android app
- audit logging (recommended)

## Out of Scope
- payroll computation
- full scheduling / rostering
- incident management module
- real-time map monitoring
- multi-tenant support in first release

---

# 5. System Responsibilities

The backend must:

1. Authenticate mobile users.
2. Return profile, checkpoint, duties, and settings data to the app.
3. Receive attendance submissions with metadata and selfie images.
4. Store attendance data and image evidence.
5. Allow admins to view, filter, and export attendance records.
6. Provide duties content to be acknowledged by guards.
7. Provide report generation for both admin and mobile consumption.

---

# 6. Filament Modules

# 6.1 Dashboard

## Purpose
Provide administrators with an overview of attendance activity and system health.

## Dashboard Content
- total guards
- today’s attendance count
- time-ins vs time-outs
- recent attendance activity
- attendance by checkpoint
- pending / failed sync indicators if tracked
- quick links to common admin actions

---

# 6.2 Guards Management

## Requirements
Admins should be able to:
- create guard accounts
- edit guard profiles
- activate / deactivate users
- reset passwords
- assign roles if needed

## Suggested Guard Fields
- name
- username
- email (optional)
- status
- role
- last login / last sync info (if tracked)

---

# 6.3 QR Checkpoint Management

## Requirements
Admins should be able to:
- create checkpoints
- assign checkpoint codes / QR values
- edit checkpoint names
- store optional location metadata
- activate / deactivate checkpoints

## Suggested Fields
- code
- name
- description
- latitude / longitude (optional)
- status

---

# 6.4 Attendance Management

## Purpose
Allow admins to inspect submitted attendance transactions and supporting evidence.

## Attendance List Requirements
Admins should be able to filter by:
- guard
- checkpoint
- attendance type
- date range
- sync / source metadata if available

## Attendance Detail Requirements
Each attendance record should display:
- guard
- attendance type
- checkpoint
- timestamp
- selfie image
- latitude / longitude
- GPS accuracy
- duties acknowledgement
- device info if tracked
- created / synced timestamps

## Attendance Actions
- view record
- export filtered data
- print report
- optionally flag suspicious records in future

---

# 6.5 Duties & Responsibilities Management

## Purpose
Manage the content that guards must acknowledge before attendance submission.

## Requirements
Admins should be able to:
- create duties/instructions
- edit duties content
- mark a duties entry active/inactive
- maintain current active duties for mobile retrieval

---

# 6.6 Reports

## Requirements
Admins should be able to generate:
- daily attendance reports
- weekly reports
- monthly reports
- custom date range reports
- per-guard reports
- per-checkpoint reports

## Export Formats
- PDF
- spreadsheet / CSV / Excel (recommended)

---

# 6.7 Settings / Configuration

## Purpose
Provide configurable system values used by the mobile app and attendance logic.

## Possible Settings
- GPS accuracy threshold
- image upload quality / size settings reference
- attendance rules
- active duties behavior
- app maintenance message
- mobile config values

---

# 6.8 Announcements (Optional but Recommended)

## Purpose
Allow admins to publish messages to the mobile home screen.

## Requirements
- create announcement
- edit announcement
- activate / deactivate announcement

---

# 7. API Requirements

The Laravel backend must expose secure API endpoints for the Android app.

# 7.1 Authentication API

## POST /api/login
Authenticates user and returns token + profile.

## POST /api/logout
Invalidates current token / session if implemented.

## GET /api/profile
Returns the authenticated user profile.

---

# 7.2 Attendance API

## POST /api/attendance
Creates an attendance record.

### Responsibilities
- validate auth
- validate checkpoint
- validate attendance type
- store image
- store timestamp / GPS metadata
- return created record response

## GET /api/attendance/history
Returns attendance history for current mobile user.

## GET /api/attendance/report
Returns report data for current mobile user.

---

# 7.3 Checkpoint API

## GET /api/checkpoints
Returns checkpoint list for mobile cache.

## POST /api/checkpoint/validate
Optional endpoint to validate a QR scan against server data.

---

# 7.4 Content API

## GET /api/duties
Returns current active duties content.

## GET /api/announcements
Returns active announcements.

## GET /api/settings
Returns mobile-facing configuration.

---

# 7.5 Sync / Batch Upload API (Optional)
If batch syncing is preferred:

## POST /api/sync/attendance
Accepts multiple attendance records for upload.

---

# 8. Data Model Requirements

# 8.1 users
Stores system users such as guards and admins.

Suggested fields:
- id
- name
- username
- email
- password
- role
- status
- created_at
- updated_at

---

# 8.2 qr_checkpoints
Stores valid attendance checkpoints.

Suggested fields:
- id
- code
- name
- description
- latitude
- longitude
- status
- created_at
- updated_at

---

# 8.3 attendance
Stores submitted attendance records.

Suggested fields:
- id
- user_id
- qr_checkpoint_id
- attendance_type
- selfie_path
- latitude
- longitude
- accuracy
- device_id
- duties_acknowledged
- attendance_timestamp
- sync_status (optional if tracked server-side)
- created_at
- updated_at

---

# 8.4 duties
Stores duties and responsibilities content.

Suggested fields:
- id
- title
- content
- active
- effective_date (optional)
- created_at
- updated_at

---

# 8.5 devices (Optional)
Stores device metadata if device registration is needed.

Suggested fields:
- id
- user_id
- device_identifier
- device_model
- app_version
- last_login_at
- last_sync_at

---

# 8.6 announcements (Optional)
Stores mobile announcements.

Suggested fields:
- id
- title
- content
- active
- created_at
- updated_at

---

# 9. Reporting Requirements

## Admin Reports Must Support
- per guard
- per checkpoint
- per date range
- daily / weekly / monthly summaries
- printable PDF
- spreadsheet export

## PDF Contents
- report title
- date range
- filters used
- attendance table
- generation timestamp

---

# 10. Security Requirements

## Transport
- HTTPS only

## Authentication
- Sanctum token authentication for mobile
- auth middleware for admin routes and API routes

## Validation
- validate uploaded image types and sizes
- validate checkpoint references
- validate attendance types
- validate required attendance metadata

## Authorization
- restrict admin features by role/permission if needed

## Audit
Recommended:
- admin action logs
- attendance modification logs
- report export logs if required

---

# 11. Storage Requirements

The backend must store:
- attendance images / selfies
- generated reports if persisted
- exported files if needed

## Recommended Storage Strategy
- use Laravel storage disks
- store attendance selfies in organized folders
- keep public access controlled according to business rules

---

# 12. Non-Functional Requirements

## Reliability
- attendance submissions must be stored consistently
- API should support intermittent mobile sync behavior

## Performance
- admin filters and reports should be responsive
- checkpoint and duties endpoints should be lightweight for mobile

## Maintainability
- use clear domain separation in Laravel
- keep Filament resources modular
- keep API contracts stable

## Scalability
- support hundreds of guards and growing attendance records
- allow future migration from cPanel to VPS if needed

---

# 13. Recommended Filament Resources / Backend Domains

## Filament Resources
- Users / Guards
- QR Checkpoints
- Attendance
- Duties
- Announcements
- Reports / report actions
- Settings

## Laravel Domains / Services
- Auth
- Attendance
- Checkpoints
- Duties
- Reports
- Mobile Settings
- Media / Image storage

---

# 14. MVP Deliverables

## Admin / Backend MVP
- Sanctum login/logout/profile API
- attendance create/history/report API
- checkpoints API
- duties API
- settings API
- Filament dashboard
- guard management
- checkpoint management
- attendance management
- duties management
- reports export
- basic system settings

---

# 15. Future Enhancements

- geofencing validation
- face verification support
- incident reporting module
- real-time monitoring dashboard
- multi-tenant architecture
- supervisor approval workflows
- richer analytics

---

# 16. Success Criteria

- Admins can manage guards and checkpoints without direct database access.
- Attendance records from mobile are stored reliably with image + GPS metadata.
- Reports can be generated by date range and exported.
- Duties content can be updated in Filament and reflected in mobile workflows.
- The backend supports offline-first mobile synchronization patterns without data loss.
