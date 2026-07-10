# uDash Rectification — Design Spec

**Date:** 2026-07-10
**Status:** Approved by owner (approach A: surgical rectification)
**Baseline:** commit `033d772` ("buggy"). Project compiles; all 3 unit tests pass. All defects below are runtime/design defects found by code audit.

## Context

uDash auto-downloads 60-second MP4 segments from a DDPAI dashcam over its internet-less Wi-Fi AP (gateway `193.168.0.1`), then uploads them to YouTube from home Wi-Fi and deletes local copies. Owner-confirmed symptoms: downloads from the dashcam fail, crashes/UI misbehavior; the YouTube path is untested. Decisions made during brainstorming:

- **Quota:** merge consecutive segments into one file per drive session before upload (YouTube default quota allows ~6 uploads/day; raw segments arrive at 1/minute).
- **Background:** support BOTH a persistent foreground watcher service and a battery-saver periodic mode, selectable in Config.
- **Architecture:** keep MVVM + Room + WorkManager + bound OkHttp; fix in place. No Hilt, no new heavyweight dependencies.

## Part 1 — Download pipeline fixes

### 1a. Foreground-service crash (pipeline-breaking)
`DownloadWorker.doWork()` and `UploadWorker.doWork()` call `setForeground()` first thing. On Android 12+ with the app in background (the primary trigger path), this throws `ForegroundServiceStartNotAllowedException` and kills the worker.
**Fix:** wrap `setForeground(getForegroundInfo())` in try/catch (`ForegroundServiceStartNotAllowedException`, `IllegalStateException`); on failure, log WARN and continue as a non-foreground expedited job. Keep `getForegroundInfo()` override (required for expedited work pre-12).

### 1b. In-worker dashcam network resolution (pipeline-breaking)
Workers depend on `ServiceLocator.currentDashcamNetwork`, set only by a callback registered in `App.onCreate`. After process restart, or if the worker runs before the callback fires, it is null and `DownloadWorker` returns `Result.success()` — sync silently skipped.
**Fix:** new `network/DashcamNetworkResolver`: iterate `ConnectivityManager` networks; return the Wi-Fi-transport `Network` whose IPv4 default-route gateway equals the configured gateway (reuse/extract the gateway logic from `NetworkMonitor`). `DownloadWorker` resolves the network itself; `currentDashcamNetwork` becomes a hint only. If unresolved: `Result.retry()`; if `runAttemptCount >= 5`, return `Result.success()` and wait for the next connect event.

### 1c. Skip the still-recording newest file
The camera is always writing the latest segment; downloading it always fails the `moov` check, and after `maxRetries` sessions the file is permanently `FAILED` even though it completed seconds after the first attempt.
**Fix:** after parsing the listing, exclude the single file with the newest embedded timestamp from reconciliation for this cycle. It is picked up next cycle.

### 1d. Retry accounting on download errors
The download catch-block resets status to `PENDING` without incrementing `retryCount` (only the corruption path increments), so network-failing files retry forever.
**Fix:** new DAO method that atomically sets status=`PENDING`, `retryCount = retryCount + 1`, `errorMessage`; use it in the catch block. `maxRetries` check then applies uniformly.

### 1e. Gateway-change cleartext trap
`network_security_config.xml` whitelists cleartext only for `193.168.0.1`, but the gateway is user-editable in Config; changing it silently blocks all camera HTTP.
**Fix:** delete the NSC file; set `android:usesCleartextTraffic="true"` on `<application>`. The app talks only to the camera (local HTTP) and Google (HTTPS).

### 1f. Live per-file progress
`downloadedBytes`/`sizeBytes` are never updated during download, so the Files-screen progress bar shows 0%.
**Fix:** `DownloadWorker` persists `downloadedBytes` (and `sizeBytes` from `Content-Length` on first response) throttled to ≤1 DB write/second. `ProgressBus` remains the dashboard's live feed.

### 1g. Debounce network events
`onCapabilitiesChanged` refires cause repeated "detected; enqueue" logs and enqueues.
**Fix:** the shared network handler (see 4a `SyncController`) acts only when the classified `NetworkType` *changes*.

## Part 2 — Upload + OAuth fixes

### 2a. OAuth client secret + setup UX (pipeline-breaking, untested path)
`YouTubeAuthManager` never sends the client secret, but Google requires it for Web/Desktop client types — the types the Config screen instructions recommend. Token exchange fails with `invalid_client`.
**Fix:**
- If a client secret is present in config, pass `ClientSecretPost` as client authentication in both token exchange and refresh; otherwise PKCE-only (correct for Android-type clients).
- Config instructions: recommend **Android** OAuth client type (package `com.ddpai.uploader` + signing SHA-1, no secret); Web/Desktop + secret supported as fallback.
- Config screen displays the installed app's package name and signing SHA-1 with copy-to-clipboard, since Google Console requires them.

### 2b. Mobile-data leak on uploads (pipeline-breaking)
Upload work uses constraint `CONNECTED`; the dashcam AP satisfies it, and the OS then routes YouTube traffic over mobile data (dashcam Wi-Fi has no internet).
**Fix:** constraint `UNMETERED`; additionally an in-worker guard: active network must have Wi-Fi transport + `NET_CAPABILITY_VALIDATED` and must NOT be the dashcam gateway; otherwise `Result.retry()`.

### 2c. Completed-session handling
`queryOffset` returning 200/201 discards the response; the worker then re-PUTs the full file into a finished session and errors out; the video ID is lost.
**Fix:** `queryOffset` returns a sealed result: `Complete(videoId)` (parsed from the 200/201 body) | `Offset(nextByte)`. On `Complete`, mark uploaded directly.

### 2d. Session expiry
Resumable session URIs expire (~1 day); 404/410 responses are never handled, so the file can never upload.
**Fix:** on 404/410/400 from the session URI, clear `uploadSessionUrl` in DB and initiate a fresh session once within the same attempt; further failures follow normal retry accounting.

### 2e. Real quota detection
Any error message containing "403" is treated as quota and retried every ~30 s forever.
**Fix:** parse the error body for `quotaExceeded`/`uploadLimitExceeded`. On quota: persist `quotaPausedUntil` (next midnight America/Los_Angeles) in config storage; re-enqueue upload work with matching initial delay; dashboard shows "Uploads paused until …". Non-quota 4xx follows retry→`FAILED` accounting. 401 with failed refresh sets a "re-authorize" flag surfaced on Dashboard and Config.

## Part 3 — Merge-per-drive

### Data model (Room v1→v2, real migration — remove `fallbackToDestructiveMigration`)
`video_files` gains:
- `kind TEXT NOT NULL DEFAULT 'SEGMENT'` — `SEGMENT` | `MERGED`
- `mergedInto TEXT` — nullable; for consumed segments, the merged output's fileName

New `FileStatus.MERGED` (terminal for segments, like `UPLOADED`).

```
SEGMENT: DISCOVERED → DOWNLOADING → DOWNLOADED → MERGED (consumed; local file deleted)
MERGED:                             DOWNLOADED → UPLOADING → UPLOADED (local file deleted)
```

### Grouping rules
- Group key: camera stream, from filename suffix (`_F`, `_R`, `_0060` are three distinct keys).
- A drive session: consecutive `DOWNLOADED` segments where each starts ≤ 120 s after the previous start.
- A group is *closed* (mergeable) when its newest segment's capture time + 60 s lies more than 5 minutes in the past (guards the "parked in garage, camera still recording near home Wi-Fi" case; merging only ever runs on home Wi-Fi, where the drive is normally over).
- Cap: 60 segments per output; longer drives produce multiple parts.
- Single-segment closed groups skip the file merge; `MergeWorker` relabels the row to kind `MERGED` in place (the segment file *is* the drive file). No copy.

### Upload gate
`nextToUpload()` changes to `WHERE status = 'DOWNLOADED' AND kind = 'MERGED'`. Raw segments are never uploaded directly; every upload-ready file — multi-segment merges, relabeled single segments, and the merge-failure fallback below — passes through `MergeWorker` first and carries kind `MERGED`. Order stays `capturedAtEpoch ASC`.

### `merge/Mp4Merger`
Platform APIs only: `MediaExtractor` (per segment) → `MediaMuxer` (one output), sample copy with presentation-timestamp offsets; no re-encode. Each segment's `MediaFormat` (mime, width/height, sample rate, channel count) must match the group head; mismatch closes the group at that boundary. Output name: `DRIVE_<yyyyMMdd_HHmmss>_<stream>.mp4` (start time + stream key; parts get `_p2`, `_p3`…).

### `pipeline/MergeWorker`
Runs on home Wi-Fi, chained before upload (`WorkManager` chain merge → upload; manual "Upload now" uses the same chain). Procedure per group: write to `<output>.tmp` → verify with `Mp4AtomScanner` (+ size ≥ 1 MB) → atomic rename → one DB transaction (insert merged row with status `DOWNLOADED`, kind `MERGED`, `localPath`, `sizeBytes`, `capturedAtEpoch` = first segment's; mark segments `MERGED` + `mergedInto`) → delete segment files. Crash at any point: segments untouched, `.tmp` cleaned on next run, re-merge idempotent. Merge failure (extractor error): relabel each segment of the group to kind `MERGED` so they upload individually as fallback (log ERROR, don't block the queue).

### Upload metadata
Merged uploads: title `Dashcam <yyyy-MM-dd HH:mm> (<Front|Rear|Cam>, <N> min)`; description lists the source segment names. Segment uploads keep the filename title.

### UI
Files list shows merged rows (playable, distinct chip) and consumed segments as `Merged → <output>`. Player works unchanged off `localPath`.

## Part 4 — Watcher modes, slimming, polish

### 4a. Sync modes (`syncMode` config: `PERSISTENT` default | `BATTERY_SAVER`)
- Shared `pipeline/SyncController`: owns `NetworkMonitor`, debounces transitions (1g), enqueues download/merge/upload work. Used by both modes; `App.onCreate` delegates to it.
- **Persistent:** `pipeline/WatcherService` — foreground service, type **`specialUse`** (manifest `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` = "dashcam-wifi-watcher"; `specialUse` is legal to start from `BOOT_COMPLETED` on Android 14+, unlike `dataSync`; requires the `FOREGROUND_SERVICE_SPECIAL_USE` permission in the manifest), IMPORTANCE_MIN notification "Watching for dashcam Wi-Fi". Started by new `BootReceiver` (`RECEIVE_BOOT_COMPLETED`) and on app launch when mode is persistent; stopped on mode switch.
- **Battery saver:** no service; 15-minute `PeriodicWorkRequest` (`SyncCheckWorker`) classifies the current network and enqueues appropriate work; instant triggers still fire while the process is alive.
- Delete the unused `PipelineForegroundService` stub.

### 4b. Dependency slimming (~50–60 MB APK reduction)
Remove from `libs.versions.toml` + `app/build.gradle.kts`: `ffmpeg-kit`, `google-api-client-android`, `google-api-services-youtube`, `google-http-gson`, `coil-compose`, `accompanist-permissions`, `androidx-datastore-preferences`. `IntegrityVerifier` drops the FFprobe branch; `Mp4AtomScanner` is the sole integrity gate (spec-compliant: size ≥ 1 MB + `ftyp`+`mdat`+`moov`). None of the removed libraries are on any other live code path.

### 4c. UI/UX polish
- Fix deprecated Compose APIs: `Divider`→`HorizontalDivider`, `LinearProgressIndicator` lambda overload, `menuAnchor(MenuAnchorType…)`.
- Log console: add `EXTREME` filter chip; use `LogRepository.x()` for wire-level logs (HTTP lines, byte counts, worker state transitions).
- Dashboard: quota-paused banner (2e), re-authorize banner, sync-mode indicator, "Merged" counter.
- Config: sync-mode toggle, package-name/SHA-1 copy card (2a).

### 4d. Verification
- New unit tests: drive-session group builder (boundaries, stream separation, 60-cap, single-segment skip), retry accounting (1d), quota-error body parser (2e), `queryOffset` completed-session parsing (2c).
- Existing tests (`Mp4AtomScannerTest`, `DashcamFileListParserTest`, `FileRepositoryTest`) keep passing; build gate = `:app:assembleDebug :app:testDebugUnitTest`.
- Manual E2E checklist: fresh install → Config (Android OAuth client via SHA-1 card) → authorize → drive: newest-file skip + downloads with app backgrounded (Android 12+ device) → home: merge + single upload + local deletion → kill-app and reboot cases in both sync modes → quota simulation (revoke quota or mock 403 body) → play merged and segment files → gateway change still downloads.

## Out of scope
- DB pruning of old `UPLOADED`/`MERGED` rows (unbounded growth is slow; revisit later).
- YouTube quota-increase application, Google Drive backend.
- Localization, tablet layouts.
