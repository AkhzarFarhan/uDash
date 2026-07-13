# Project Current Status & Architecture Specification — uDash

uDash is a fully automated background sync-and-upload companion application for Android designed specifically for **DDPAI dashcams**. It runs silently in the background, automatically detects your dashcam's Wi-Fi access point, downloads recordings, merges them into cohesive drive sessions, and uploads them to a private YouTube playlist.

---

## 🏗️ Architectural Design Principles

The application relies on several advanced design choices to ensure reliability under varying network conditions and power constraints:

1. **Dual Network Socket Binding:**
   Android devices route all traffic through interfaces with internet connectivity. Since dashcam Wi-Fi hotspots do not provide internet, Android routinely ignores them. uDash uses [BoundHttpClientFactory](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/network/BoundHttpClientFactory.kt) to bind OkHttp sockets directly to the dashcam's local `Network` interface, bypassing standard OS routing. This enables simultaneous connection to the dashcam (via local Wi-Fi) and cellular data.
2. **Offline-First State Machine:**
   Downloads, validation, and merges are managed locally. Room acts as the source of truth, routing files through status transitions: `DISCOVERED` ➔ `DOWNLOADING` ➔ `DOWNLOADED` ➔ `MERGING` ➔ `MERGED` ➔ `UPLOADING` ➔ `UPLOADED`.
3. **Resumable Chunks Protocol:**
   Large video file uploads to YouTube are fragile. uDash implements the **YouTube Resumable Upload API** in [YouTubeUploader](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/youtube/YouTubeUploader.kt), allowing it to query already-uploaded offsets from Google and resume uploads seamlessly across app restarts or network switches.
4. **Double-Gate Integrity Verifier:**
   To prevent uploading corrupted files or HTML gateway error pages, uDash runs a two-step validation:
   - **MP4 Atom Scanner:** A lightweight local parser ([Mp4AtomScanner](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/integrity/Mp4AtomScanner.kt)) checking for `ftyp`, `mdat`, and `moov` atom headers.
   - **FFprobe Verification:** (Optional/Soft fallback) Uses a lightweight `FFprobeKit` command execution to check stream index readability.
5. **Lossless Video Merging:**
   DDPAI dashcams record videos in short 1-minute segments. Uploading dozens of individual files results in a fragmented YouTube experience. uDash clusters contiguous clips using a temporal proximity algorithm ([DriveGrouper](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/merge/DriveGrouper.kt)), then losslessly merges them using Android's native `MediaExtractor` and `MediaMuxer` ([Mp4Merger](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/merge/Mp4Merger.kt)), avoiding slow re-encoding.
6. **Thread-Safe Local Logger with EXTREME support:**
   The application implements a custom logging database ([LogRepository](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/data/repo/LogRepository.kt)) that supports the `EXTREME` logging level for trace diagnostics. Logging operations run on a background thread pool, caching DB writes and pruning logs to a maximum limit periodically to prevent performance degradation.

---

## 📂 Source Code Catalog & Module Breakdown

### 1. Root & Entry Point
- **[App.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/App.kt):** Initializes Notification Channels, starts `SyncController` network monitoring, and registers a global uncaught exception handler that persists crashes to the local database before the app exits.

### 2. Dashcam Network Layer (`com.ddpai.uploader.dashcam`)
- **[DashcamClient.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/dashcam/DashcamClient.kt):** Connects to the bound network interface, executes directory listing endpoint discovery, and runs range-based HTTP download requests. Supports HTTP range-resumes and defends against non-supportive HTTP 200 server fallbacks.
- **[DashcamFileListParser.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/dashcam/DashcamFileListParser.kt):** Standardizes parsing of DDPAI's directory structure (either custom JSON models or raw Apache-like gateway HTML file indexes) and extracts capturing timestamps using date-regex parsing.
- **[ListingFilter.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/dashcam/ListingFilter.kt):** Evaluates discovered files and ignores those outside configured rules (e.g. ignores files too old or not fitting filename pattern).

### 3. Data & Config Layer (`com.ddpai.uploader.data`)
- **[AppConfig.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/data/config/AppConfig.kt):** Holds client IDs, secrets, max retry attempts, upload settings, and the default Gateway IP (`193.168.0.1`).
- **[ConfigRepository.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/data/config/ConfigRepository.kt):** Persists configuration fields and AppAuth state variables using Android Jetpack's **EncryptedSharedPreferences** to encrypt credentials at rest.
- **[AppDatabase.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/data/db/AppDatabase.kt):** Defines Room database configurations with destructively falling-back migrations.
- **[VideoFileDao.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/data/db/VideoFileDao.kt):** SQL query mappings to select downloads, uploads, check statuses, and update metadata.
- **[LogDao.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/data/db/LogDao.kt):** CRUD methods to insert logs, select recent messages, clear histories, and prune database entries.
- **[VideoFileEntity.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/data/db/entity/VideoFileEntity.kt):** Maps files database table attributes (URI, path, state, kind: segment vs merged, upload session url, error, capturing/updating timestamps).
- **[LogEntity.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/data/db/entity/LogEntity.kt):** Maps application log attributes (tag, message, severity, timestamp).
- **[FileRepository.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/data/repo/FileRepository.kt):** Standard Repository facade coordinating SQLite operations, file naming, and transaction updates during segment mergers.
- **[LogRepository.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/data/repo/LogRepository.kt):** Thread-safe application log manager wrapping standard Android `Log` calls, capturing StackTraces, and routing writes to Room.

### 4. Dependency Injection (`com.ddpai.uploader.di`)
- **[ServiceLocator.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/di/ServiceLocator.kt):** Simple singleton service registry housing data repositories, DB connectors, network types, and the active network reference.

### 5. Video Integrity (`com.ddpai.uploader.integrity`)
- **[Mp4AtomScanner.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/integrity/Mp4AtomScanner.kt):** Custom parser scanning through MP4 containers using `RandomAccessFile` to find `ftyp`, `mdat`, and `moov` chunk offsets without executing external subprocesses.
- **[IntegrityVerifier.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/integrity/IntegrityVerifier.kt):** Orchestrates validation checks and calls FFprobe duration checks if libraries compile successfully.

### 6. Video Merger Layer (`com.ddpai.uploader.merge`)
- **[DriveGrouper.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/merge/DriveGrouper.kt):** Pure functional algorithm grouping adjacent files by comparing epoch offsets, sorting streams, and building group descriptors.
- **[MergeNaming.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/merge/MergeNaming.kt):** Assigns filenames to merged drives using timestamps (e.g. `DRIVE_20260713_172819_F.mp4`).
- **[Mp4Merger.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/merge/Mp4Merger.kt):** Implements format validation and concatenates multiple MP4 inputs by appending samples directly via raw buffers, shifting presentation timestamps offset.

### 7. Network Monitor (`com.ddpai.uploader.network`)
- **[BoundHttpClientFactory.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/network/BoundHttpClientFactory.kt):** Builds specialized OkHttp clients tied to target networks or configures standard timeouts.
- **[NetworkMonitor.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/network/NetworkMonitor.kt):** Connects to `ConnectivityManager` callbacks to check for Wi-Fi SSID, gateway transitions, and determines whether active networks fit configurations.
- **[DashcamNetworkResolver.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/network/DashcamNetworkResolver.kt):** Helper to classify active link gateways without active callbacks.

### 8. WorkManager Pipeline (`com.ddpai.uploader.pipeline`)
- **[PipelineScheduler.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/pipeline/PipelineScheduler.kt):** Registers background execution workers in sequential chains using WorkManager API.
- **[DownloadWorker.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/pipeline/DownloadWorker.kt):** Scans the dashcam folder, saves new files to SQLite, downloads pending segments sequentially, and verifies their integrity.
- **[MergeWorker.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/pipeline/MergeWorker.kt):** Gathers downloaded segments and combines contiguous files into singular drive files.
- **[UploadWorker.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/pipeline/UploadWorker.kt):** Uploads videos to YouTube using resumable chunks and tracks token auth statuses.
- **[WatcherService.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/pipeline/WatcherService.kt):** Persistent foreground service wrapping Wi-Fi connectivity tracking.
- **[ProgressBus.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/pipeline/ProgressBus.kt):** Thread-safe progress publisher emitting current speeds and percentages to UI collectors.

### 9. YouTube Integration (`com.ddpai.uploader.youtube`)
- **[YouTubeAuthManager.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/youtube/YouTubeAuthManager.kt):** Encapsulates PKCE Authorization code grant flow via AppAuth SDK, handles redirection, and manages access tokens.
- **[YouTubeUploader.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/youtube/YouTubeUploader.kt):** Handles YouTube Resumable upload handshakes, metadata submissions, offset checking, and chunk transfers.
- **[QuotaClock.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/youtube/QuotaClock.kt):** Tracks daily YouTube API quotas, pausing uploads automatically when a quota limit error is detected.

### 10. UI & Composable Screen Layouts (`com.ddpai.uploader.ui`)
- **[DashboardScreen.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/ui/dashboard/DashboardScreen.kt):** Central control panel showing upload speeds, queued items, and a scrollable debug log tail.
- **[FileListScreen.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/ui/files/FileListScreen.kt):** Displays SQLite files with status indicators (Discovered, Downloading, Merged, Uploading, etc.) and player controls.
- **[LogConsoleScreen.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/ui/logs/LogConsoleScreen.kt):** Detailed log screen supporting level filtering (DEBUG, INFO, WARN, ERROR, EXTREME), search, and clipboard actions.
- **[PlayerScreen.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/ui/player/PlayerScreen.kt):** Implements local video playback using ExoPlayer.
- **[ConfigScreen.kt](file:///C:/GitHub/uDash/app/src/main/java/com/ddpai/uploader/ui/config/ConfigScreen.kt):** Allows editing preferences (Gateway, YouTube IDs, storage configuration) and initiating authorization flows.

---

## 🧪 Verification & Testing Suite

uDash maintains unit test coverage for critical components:
- **[DashcamFileListParserTest](file:///C:/GitHub/uDash/app/src/test/java/com/ddpai/uploader/dashcam/DashcamFileListParserTest.kt):** Validates parsing of JSON and HTML directory formats, verifying timezone offsets.
- **[FileRepositoryTest](file:///C:/GitHub/uDash/app/src/test/java/com/ddpai/uploader/data/repo/FileRepositoryTest.kt):** Checks reconciliation logic and duplicate prevention.
- **[Mp4AtomScannerTest](file:///C:/GitHub/uDash/app/src/test/java/com/ddpai/uploader/integrity/Mp4AtomScannerTest.kt):** Validates scanner capabilities using mock files (verifying `ftyp`, `mdat`, and `moov` structures).
- **[DriveGrouperTest](file:///C:/GitHub/uDash/app/src/test/java/com/ddpai/uploader/merge/DriveGrouperTest.kt):** Tests drive aggregation rules, gap detection, and group closures.
- **[RetryPolicyTest](file:///C:/GitHub/uDash/app/src/test/java/com/ddpai/uploader/pipeline/RetryPolicyTest.kt):** Validates exponential backoff computations.
- **[QuotaClockTest](file:///C:/GitHub/uDash/app/src/test/java/com/ddpai/uploader/youtube/QuotaClockTest.kt):** Verifies quota lockouts and release timestamps.

---

## 🛠️ Build Configuration

- **Minimum SDK:** 26 (Android 8.0)
- **Target SDK:** 34 (Android 14)
- **Kotlin:** 2.0.20
- **Gradle:** 8.9
- **AGP:** 8.5.2
- **Compiler Options:** Java 17 compatibility.
