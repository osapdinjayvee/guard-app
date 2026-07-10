# PRD — Android Mobile App
# Guard Attendance Mobile Application (Android)

**Document Version:** 1.0  
**Platform:** Android Native (Kotlin + Jetpack Compose)  
**Purpose:** Mobile app PRD for security guards  
**Related Backend:** Laravel 12 API + Sanctum + Filament v4  

---

# 1. Product Overview

The Android Guard Attendance App is a mobile application for security guards to record attendance using QR checkpoint scanning, selfie capture, timestamped image evidence, and real-time GPS location. The application is designed to be **offline-first**, allowing attendance capture even when internet connectivity is unstable or unavailable.

The Android app is the field-facing component of the overall Guard Attendance System. Its primary goal is to let a guard complete a valid attendance transaction in a fast, guided, and verifiable way.

---

# 2. Product Goals

## Primary Goals
- Allow guards to record **Time In** and **Time Out** using QR checkpoints.
- Require **selfie proof** and **real-time location** for every attendance record.
- Display **date, time, location, checkpoint, and attendance type** on the attendance image.
- Work **offline first** and synchronize automatically when online.
- Provide guards with access to **history**, **reports**, and **settings**.

## Secondary Goals
- Reduce attendance fraud.
- Minimize time required to complete attendance.
- Provide a modern Android user experience that is easy to use for non-technical users.

---

# 3. Target Users

## Primary User
### Security Guard
Needs:
- quick login
- fast QR scanning
- selfie capture
- offline support
- clear sync visibility
- attendance history and reports

---

# 4. Scope

## In Scope
- Login / logout
- Home dashboard
- Bottom navigation with center **Scan QR** action
- QR scanning
- Attendance type selection (Time In / Time Out)
- Selfie capture with live metadata
- GPS capture
- Duties acknowledgement before submission
- Offline save to local database
- Background sync to backend
- Attendance history
- Reports
- PDF generation / sharing
- Settings

## Out of Scope
- iOS app
- incident reporting
- geofencing
- face recognition
- payroll
- supervisor approval flow

---

# 5. Navigation Structure

The app uses a modern bottom navigation with the **center action dedicated to Scan QR**.

## Bottom Navigation
- **Home**
- **History**
- **Scan QR** (center main action)
- **Reports**
- **Settings**

---

# 6. Main User Flow

1. User logs in.
2. Home dashboard opens.
3. User taps **Scan QR**.
4. User scans checkpoint QR.
5. User selects **Time In** or **Time Out**.
6. Selfie camera opens.
7. App fetches real-time GPS location and current time.
8. Camera screen displays live metadata:
   - guard name
   - date
   - time
   - latitude / longitude
   - GPS accuracy
   - checkpoint
   - attendance type
9. User captures selfie.
10. User reviews preview.
11. Duties & Responsibilities acknowledgement screen appears.
12. User confirms acknowledgement.
13. User taps submit.
14. Attendance record is saved locally first.
15. If online, record syncs immediately.
16. If offline, record remains queued for sync.
17. History updates.

---

# 7. Functional Requirements

# 7.1 Authentication

## Login
- User logs in with username and password.
- App authenticates via Laravel Sanctum token flow.
- Token is stored securely on device.
- Session persists until logout or invalidation.

## Logout
- Remove local auth token.
- Clear session data as needed.

---

# 7.2 Home Screen

## Purpose
Provide a quick overview and fast access to the main attendance action.

## Home Content
- Guard name / welcome
- Today’s attendance status
- Last attendance record
- Pending sync count
- Quick scan CTA
- Optional announcements
- Online / offline indicator

---

# 7.3 QR Scanning

## Requirements
- Open scanner using camera.
- Read QR checkpoint code.
- Validate QR against locally cached checkpoint data or server data.
- Handle invalid / disabled / unknown checkpoints.

## Output
A valid checkpoint object must be resolved before proceeding.

---

# 7.4 Attendance Type Selection

Supported attendance types:
- Time In
- Time Out

This step occurs after a valid QR scan and before selfie capture.

---

# 7.5 Selfie Capture

## Requirements
- Open front camera by default.
- Show live preview.
- Fetch current GPS location while preview is open.
- Display live metadata overlay.
- Allow retake before submission.

## Overlay Metadata
- Guard name
- Date
- Time
- Latitude
- Longitude
- GPS accuracy
- Checkpoint
- Attendance type

## Final Watermark
The captured attendance image must contain the metadata as a permanent watermark / overlay in the saved image.

---

# 7.6 Real-Time GPS

## Requirements
- Capture current latitude / longitude.
- Record GPS accuracy in meters.
- Associate location with attendance transaction.
- Show location metadata during selfie capture.

## Failure Handling
If GPS is unavailable, app should either:
- prevent submission, or
- allow submission based on configured business rule

---

# 7.7 Duties & Responsibilities Acknowledgement

Before final submission:
- display latest duties/instructions
- require acknowledgement checkbox
- prevent submission until acknowledged

Example:
“I have read and understood my duties and responsibilities.”

---

# 7.8 Attendance Submission

## Attendance Record Must Include
- user id
- checkpoint id
- attendance type
- selfie image
- attendance timestamp
- latitude
- longitude
- accuracy
- duties acknowledgement flag
- sync status
- device identifier if available

## Submission Behavior
1. Validate required data.
2. Save locally first.
3. Queue for sync.
4. If online, attempt upload immediately.
5. Update sync status after upload result.

---

# 7.9 History

## History List
Each history item should show:
- date
- time
- checkpoint
- attendance type
- sync status

## History Detail
Each record detail should show:
- selfie image
- timestamp
- checkpoint
- attendance type
- latitude / longitude
- accuracy
- sync status

---

# 7.10 Reports

## Report Types
- Daily
- Weekly
- Monthly
- Custom range

## Actions
- View
- Generate PDF
- Download / Share PDF

---

# 7.11 Settings

## Settings Scope
- profile
- logout
- sync now
- offline data info
- camera quality preference
- app version / about
- dark mode (optional)
- troubleshooting sync state (optional)

---

# 8. Offline-First Requirements

## Principles
- Attendance must be saved locally before upload.
- The user should never lose a valid attendance record due to connectivity failure.
- Sync must happen in the background when possible.

## Local Data to Store
- auth/session metadata
- checkpoint cache
- duties cache
- attendance records
- sync queue

## Sync States
- pending
- syncing
- synced
- failed

---

# 9. Non-Functional Requirements

## Performance
- Main attendance flow should feel fast.
- QR scanner and camera should initialize quickly.

## Reliability
- App should work under intermittent connectivity.
- Background sync should retry failed uploads.

## Usability
- Large touch targets
- simple labels
- minimal steps for attendance

## Security
- secure token storage
- HTTPS API calls
- avoid plain text sensitive storage

---

# 10. Technical Direction

## Recommended Stack
- Kotlin
- Jetpack Compose
- Material 3
- MVVM
- CameraX
- ML Kit QR Scanner
- Fused Location Provider
- Room
- WorkManager
- Retrofit / OkHttp
- Hilt

## Suggested Modules
- authentication
- home
- scanner
- attendance
- camera
- location
- duties
- history
- reports
- settings
- sync
- core-network
- core-database
- core-ui

---

# 11. Permissions

Likely Android permissions:
- Camera
- Fine location
- Internet / network
- background work support as needed

---

# 12. MVP Deliverables

- Login
- Home
- Bottom nav with center Scan QR
- QR scanning
- Time In / Time Out selection
- Selfie capture with watermark metadata
- GPS capture
- Duties acknowledgement
- Offline save + sync
- History
- Reports
- PDF export
- Settings
