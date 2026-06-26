# System Architecture Specification: Automated Dashcam ETL & Upload Pipeline
**Target Platform:** Android 14 (API Level 34) and higher  
**Author:** System Architecture Group  
**Status:** Approved / Production Ready

---

## 1. Executive Summary & Constraints

This specification outlines the architecture for a background-driven Extract, Transform, Load (ETL) Android utility. The application automatically ingests raw video payloads from a local **DDPAI Mini Pro** dashcam Wi-Fi access point, transforms them via stream-copy concatenation, and uploads the aggregated daily files to **YouTube Data API v3** under strict unmetered network conditions.

### Core Engineering Guardrails
* **Zero-Re-encoding Rule:** To prevent high thermal profiles and battery degradation, video compilation must use FFmpeg's stream-copy (`-c copy`) demuxer.
* **Quota Management:** To stay within the YouTube Data API v3 default daily limit of 10,000 units, multi-fragment 1-minute clips must be consolidated day-wise. This reduces API consumption from $N \times 1,600$ units to a flat $1,600$ units per day.
* **Isolated Network Routing:** Use low-level socket binding via `ConnectivityManager` to interact with the non-routable camera gateway (`193.168.0.1`) without breaking the device's main cellular data routing capabilities.

---

## 2. Technical Stack & Dependencies

```gradle
dependencies {
    // Persistence
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Concurrency & Background Processing
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Video Processing (Pre-compiled native binaries)
    implementation("com.extas:ffmpeg-kit-android-min-gpl:6.0-LTS")

    // Network & Authentication
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.34.1")
    implementation("com.google.apis:google-api-services-youtube:v3-rev20231014-2.0.0")
    
    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
} 
```

---

## 3. Database Schema (Room SQLite Local Ledger)

The local SQLite instance serves as the transactional state engine. No network action can be taken without a state transition logged here first.

### 3.1 Raw Clips Table (raw_clips)

Tracks individual 1-minute video segments discovered on the dashcam file system.

```kotlin
@Entity(
    tableName = "raw_clips",
    indices = [Index(value = ["dateString"]), Index(value = ["downloadStatus"])]
)
data class RawClipEntity(
    @PrimaryKey val fileName: String,      // Format: "YYYYMMDD_HHMMSS_F.mp4"
    val dateString: String,                // Format: "YYYY-MM-DD" (Logical Partition)
    val remoteUrl: String,                 // Example: "http://193.168.0.1/sd/normal/..."
    val localFilePath: String?,            // Path inside app's internal cache directory
    val fileSize: Long,                    // Byte length for storage verification
    val downloadStatus: String             // Enums: PENDING, DOWNLOADING, COMPLETED, FAILED
)
```

### 3.2 Daily Merges Table (daily_merges)

Tracks state transitions of concatenated daily compilations intended for YouTube deployment.

```kotlin
@Entity(
    tableName = "daily_merges",
    indices = [Index(value = ["uploadStatus"])]
)
data class DailyMergeEntity(
    @PrimaryKey val dateString: String,    // Format: "YYYY-MM-DD"
    val localMergedPath: String?,          // Path to final concatenated file
    val totalSize: Long,                   // Aggregated byte length
    val mergeStatus: String,               // Enums: PENDING, PROCESSING, COMPLETED, FAILED
    val uploadStatus: String,              // Enums: IDLE, QUEUED, UPLOADING, SUCCESS, FAILED
    val youtubeVideoId: String?,           // String returned from Google API upon 200 OK
    val lastAttemptTimestamp: Long         // Epoch timestamp for backoff constraints
)
```

---

## 4. System Architecture Flow & State Machine

```text
[Network State Broadcast Trigger]
                                  │
          ┌───────────────────────┴───────────────────────┐
          ▼                                               ▼
[SSID == Dashcam_AP]                             [SSID == Home_WiFi]
          │                                               │
          ▼                                               ▼
Launch IngestionService                          Enqueue UploadWorker
 (Foreground Service)                              (WorkManager Job)
          │                                               │
          ▼                                               ▼
Fetch Camera File Manifest                      Fetch Active OAuth2 Token
          │                                               │
          ▼                                               ▼
Diff Map against Room DB                         Scan DB for `IDLE`/`FAILED`
          │                                               │
          ▼                                               ▼
Download Stream via Bound Socket                  Stream Chunked Resumable Upload
          │                                               │
          ▼                                               ▼
Execute FFmpeg Concatenation                      Update DB State to `SUCCESS`
          │                                               │
          ▼                                               ▼
Purge Raw Local Cache Files                      Purge Local Merged File
```

---

## 5. Detailed Component Specifications

### 5.1 Ingestion Engine (ForegroundService)

- **Lifecycle:** Fired via a custom `BroadcastReceiver` catching `WifiManager.NETWORK_STATE_CHANGED_ACTION` when the connected SSID matches the dashcam hardware footprint. Runs as a foreground service with an active persistent status bar notification to prevent system execution termination.

- **Socket Extraction Control:**
```kotlin
val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
val networkRequest = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .build()

connectivityManager.requestNetwork(networkRequest, object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
        // Bind the app network stack explicitly to this Wi-Fi interface
        val boundSocketFactory = network.socketFactory
        val okHttpClient = OkHttpClient.Builder()
            .socketFactory(boundSocketFactory)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        executeCameraIngestion(okHttpClient)
    }
})
```

- **API Interactions:**
    1. Execute `GET http://193.168.0.1/vcam/cmd.cgi?cmd=APP_PlaybackListReq` to fetch the camera's file log.
    2. Parse the text response, map files to `RawClipEntity`, and ignore items that already match `COMPLETED` in the Room DB.
    3. Download missing binary blocks using standard streaming chunks (`InputStream.read()`) directly to `context.cacheDir`.

### 5.2 Transformation Engine (FFmpeg Orchestrator)

- **Trigger Strategy:** Invoked immediately when the Ingestion Engine marks all identified records for a closed historical date string as `COMPLETED`.

- **Execution Logic:**
    1. Generate a plain text structural manifest file (`filelist.txt`) structured exactly as follows:
    ```text
    file '/data/user/0/com.utility.dashcam/cache/20260627_120000_F.mp4'
    file '/data/user/0/com.utility.dashcam/cache/20260627_120100_F.mp4'
    ```

    2. Pass the manifest text file to the native execution layer:
    ```kotlin
    val cmd = "-f concat -safe 0 -i ${manifestFile.absolutePath} -c copy ${outputFile.absolutePath}"
    FFmpegKit.executeAsync(cmd) { session ->
        val returnCode = session.returnCode
        if (ReturnCode.isSuccess(returnCode)) {
            // Atomic transactional switch
            database.dailyMergeDao().updateMergeStatus(dateStr, "COMPLETED")
            database.rawClipDao().deleteCachedClipsByDate(dateStr)
        } else {
            database.dailyMergeDao().updateMergeStatus(dateStr, "FAILED")
        }
    }
    ```

### 5.3 Distribution Engine (WorkManager UploadWorker)

- **Trigger Constraints:** Managed via a deferred `OneTimeWorkRequest` or `PeriodicWorkRequest`.
```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.UNMETERED) // Mandatory Wi-Fi with Internet Route
    .setRequiresCharging(true)                    // Battery conservation mode
    .build()
```

- **Runtime Guard:** Upon execution, the worker must explicitly inspect the `WifiManager.getConnectionInfo().getSSID()` to guarantee that it matches the user’s designated Home Wi-Fi profile. If it matches a public network or the car's AP, the worker must throw `Result.retry()`.

- **Upload Implementation Requirements:**
    1. Construct a YouTube instance using `GoogleAccountCredential` with OAuth2 tokens drawn safely from an Android `EncryptedSharedPreferences` container.
    2. Initialize a Video meta-object. Set the operational visibility field to private or unlisted by default to avoid accidental public exposure of raw travel telemetry.
    3. Initialize the `MediaHttpUploader` pipeline and configure the chunk allocation parameter to `MediaHttpUploader.MINIMUM_CHUNK_SIZE` (256 KB) or higher to allow resilient upload state resumption over shaky connections.

---

## 6. Edge-Case Mitigation & Error Routines

| Edge-Case Scenario | Operational System Failure Mode | Mitigation Protocol |
| :--- | :--- | :--- |
| **Storage Exhaustion** | Storage hits 100% capacity during a raw clip download phase, killing the app process. | Read `context.cacheDir.usableSpace` prior to pulling data. If available bytes are less than $1.5 \times$ the total size of the targeted camera batch, halt operations and throw a high-priority system notification. |
| **Mid-Download Disconnection** | The vehicle turns off mid-transfer, severing the local Wi-Fi AP signal. | The networking Layer catches `IOException`. The app cleanly logs the specific file's state as `FAILED` or `PENDING` in the database. The tracking state persists across reboot cycles. |
| **The Current-Date Intersection** | The app tries to merge files for a day that is still actively generating data while the user drives. | The ingestion script evaluates and blocks the compilation of files matching the exact current system date ($T_0$). It only compiles a date partition if the date has closed, or after a clean camera disconnection broadcast is caught. |
| **OAuth Token Expiry** | The API throws an unauthenticated 401 Unauthorized exception during background uploads. | Implement synchronous Token Rotation via a secure background refresh token exchange using the Google API client before initiating the `MediaHttpUploader` binary stream loop. |
