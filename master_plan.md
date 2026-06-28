# DDPAI Dashcam Auto-Uploader — Exhaustive Android Implementation Plan

> **Audience:** This document is written so that an LLM (or junior developer) can implement the entire app **without guessing**. Every file, class, function, dependency version, and edge case is specified. Follow the tasks **in order**. Do not skip steps. Do not invent APIs that are not listed here.

---

## 0. Golden Rules (Read First, Obey Always)

1. **Language:** Kotlin only. **Min SDK 26 (Android 8.0)**, **Target SDK 34 (Android 14)**, **Compile SDK 34**.
2. **UI Toolkit:** Jetpack Compose (Material 3). No XML layouts except the launcher theme.
3. **Architecture:** MVVM + Repository pattern + a single `WorkManager`-driven background pipeline. Use a foreground `Service` only where continuous network work needs to survive screen-off; otherwise prefer `WorkManager`.
4. **Database:** Room (SQLite). Single source of truth for file state.
5. **Async:** Kotlin Coroutines + Flow. No RxJava, no AsyncTask, no raw Threads.
6. **Networking to dashcam:** Raw `OkHttp` calls **bound to the Wi-Fi network object**. Never use Retrofit for dashcam downloads (you need streaming + network binding).
7. **YouTube upload:** Google API Java Client + resumable upload. OAuth2 via AppAuth (no Google Play Services dependency required, works on any device).
8. **Idempotency rule:** A file is identified uniquely by its **filename** (which embeds timestamp). The DB enforces uniqueness so nothing is ever downloaded or uploaded twice.
9. **State machine rule:** Every file row has exactly ONE status at a time. Transitions are strictly: `DISCOVERED → DOWNLOADING → DOWNLOADED → UPLOADING → UPLOADED → (row kept as record, local file deleted)`. Error/corruption paths reset to `PENDING`.
10. **Never delete a local file** unless DB status is confirmed `UPLOADED` (upload returned a YouTube video ID). Never re-download a file whose status is `DOWNLOADED`, `UPLOADING`, or `UPLOADED`.
11. **No hardcoded secrets.** YouTube Client ID/Secret come from the in-app Config screen, stored encrypted.
12. **Logging:** Every meaningful action writes a structured log row to the DB log table AND emits to Logcat. The Dashboard shows these logs live.

---

## 1. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                         UI LAYER (Compose)                      │
│  Dashboard │ FileList │ Player │ Config │ LogConsole            │
└───────────────┬──────────────────────────────────────────────┘
                │ StateFlow / events
┌───────────────▼──────────────────────────────────────────────┐
│                       VIEWMODEL LAYER                           │
│  DashboardViewModel │ ConfigViewModel │ PlayerViewModel         │
└───────────────┬──────────────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────┐
│                      REPOSITORY LAYER                           │
│  FileRepository │ ConfigRepository │ LogRepository              │
└──────┬───────────────┬───────────────────┬───────────────────┘
       │               │                    │
┌──────▼─────┐ ┌───────▼────────┐ ┌─────────▼──────────┐
│  Room DB   │ │ DashcamClient  │ │  YouTubeUploader   │
│ (SQLite)   │ │ (OkHttp+bind)  │ │ (resumable+OAuth)  │
└────────────┘ └────────────────┘ └────────────────────┘
       ▲               ▲                    ▲
       │               │                    │
┌──────┴───────────────┴────────────────────┴──────────────────┐
│                  BACKGROUND ORCHESTRATION                      │
│  NetworkMonitor (callback) → triggers:                        │
│   • DownloadWorker  (when gateway == 193.168.0.1)             │
│   • UploadWorker    (when on home/internet Wi-Fi)             │
│  + IntegrityVerifier (FFprobe) inside DownloadWorker          │
└───────────────────────────────────────────────────────────────┘
```

### Component responsibilities

- **NetworkMonitor:** Observes connectivity. Determines whether the *currently active Wi-Fi* is the **dashcam AP** (DHCP gateway `193.168.0.1`) or a **normal internet Wi-Fi**. Enqueues the correct worker.
- **DashcamClient:** Lists files, downloads files, all over HTTP bound to the dashcam Wi-Fi `Network` object.
- **IntegrityVerifier:** Runs FFprobe on downloaded files to confirm `moov` atom + size.
- **YouTubeUploader:** Resumable upload of a single file, returns video ID.
- **Room DB:** Holds `video_files` and `app_logs` tables.

---

## 2. Project Setup

### Task 2.1 — Create project
- Android Studio → New Project → **Empty Activity (Compose)**.
- Name: `DDPAIUploader`. Package: `com.ddpai.uploader`.
- Min SDK 26, language Kotlin, build config Kotlin DSL (`build.gradle.kts`).

### Task 2.2 — `gradle/libs.versions.toml` (Version Catalog)

Create/replace `gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.5.2"
kotlin = "2.0.20"
ksp = "2.0.20-1.0.25"
coreKtx = "1.13.1"
lifecycle = "2.8.6"
activityCompose = "1.9.2"
composeBom = "2024.09.03"
room = "2.6.1"
workManager = "2.9.1"
okhttp = "4.12.0"
datastore = "1.1.1"
securityCrypto = "1.1.0-alpha06"
media3 = "1.4.1"
appauth = "0.11.1"
googleApiClient = "2.7.0"
youtubeApi = "v3-rev20240514-2.0.0"
googleHttpGson = "1.45.0"
ffmpegKit = "6.0-2"
coil = "2.7.0"
accompanistPermissions = "0.36.0"
navigationCompose = "2.8.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workManager" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
androidx-security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "securityCrypto" }
androidx-media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
androidx-media3-ui = { group = "androidx.media3", name = "media3-ui", version.ref = "media3" }
appauth = { group = "net.openid", name = "appauth", version.ref = "appauth" }
google-api-client-android = { group = "com.google.api-client", name = "google-api-client-android", version.ref = "googleApiClient" }
google-youtube = { group = "com.google.apis", name = "google-api-services-youtube", version.ref = "youtubeApi" }
google-http-gson = { group = "com.google.http-client", name = "google-http-client-gson", version.ref = "googleHttpGson" }
ffmpeg-kit = { group = "com.arthenica", name = "ffmpeg-kit-full", version.ref = "ffmpegKit" }
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }
accompanist-permissions = { group = "com.google.accompanist", name = "accompanist-permissions", version.ref = "accompanistPermissions" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

> **NOTE on FFprobe:** `ffmpeg-kit-full` from Maven Central (`com.arthenica`) bundles `ffprobe`. The official Arthenica/FFmpegKit retired hosting in 2025 — if `com.arthenica:ffmpeg-kit-full:6.0-2` fails to resolve, fall back to a maintained community fork. The integrity check **must** degrade gracefully if FFprobe is unavailable (see Task 9.4): in that case, fall back to a pure-Kotlin MP4 atom scan for `ftyp`/`moov`/`mdat`. **Implement the pure-Kotlin scanner regardless — it is the primary check; FFprobe is the secondary confirmation.**

### Task 2.3 — Module `build.gradle.kts` (app)

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ddpai.uploader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ddpai.uploader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // OAuth redirect scheme used by AppAuth. Must match Google Console redirect URI.
        manifestPlaceholders["appAuthRedirectScheme"] = "com.ddpai.uploader"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "META-INF/DEPENDENCIES"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(libs.appauth)
    implementation(libs.google.api.client.android)
    implementation(libs.google.youtube)
    implementation(libs.google.http.gson)

    implementation(libs.ffmpeg.kit)
    implementation(libs.coil.compose)
    implementation(libs.accompanist.permissions)
}
```

### Task 2.4 — Root `settings.gradle.kts` repositories
Ensure `mavenCentral()` and `google()` are present in `dependencyResolutionManagement`.

---
## 3. AndroidManifest & Permissions

### Task 3.1 — Permissions (`AndroidManifest.xml`, inside `<manifest>`)

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<!-- Needed if you ever read SSID, but the design AVOIDS SSID; gateway IP is used instead. -->
```

> **Key design decision (per dashcam spec a):** We deliberately do NOT request `ACCESS_FINE_LOCATION`. SSID is never read. Dashcam detection is done purely via the DHCP gateway IP of the active link. This keeps the app permission-light.

### Task 3.2 — Application & components

```xml
<application
    android:name=".App"
    android:allowBackup="false"
    android:icon="@mipmap/ic_launcher"
    android:label="@string/app_name"
    android:theme="@style/Theme.DDPAIUploader"
    android:usesCleartextTraffic="true"> <!-- dashcam is plain HTTP on 193.168.0.1 -->

    <activity
        android:name=".ui.MainActivity"
        android:exported="true"
        android:launchMode="singleTop">
        <intent-filter>
            <action android:name="android.intent.action.MAIN"/>
            <category android:name="android.intent.category.LAUNCHER"/>
        </intent-filter>
    </activity>

    <!-- AppAuth redirect receiver. Scheme must equal applicationId for the custom URI. -->
    <activity
        android:name="net.openid.appauth.RedirectUriReceiverActivity"
        android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.VIEW"/>
            <category android:name="android.intent.category.DEFAULT"/>
            <category android:name="android.intent.category.BROWSABLE"/>
            <data android:scheme="com.ddpai.uploader"/>
        </intent-filter>
    </activity>

    <service
        android:name=".pipeline.PipelineForegroundService"
        android:exported="false"
        android:foregroundServiceType="dataSync"/>
</application>
```

> **`usesCleartextTraffic="true"`** is mandatory: the dashcam serves plain HTTP. To be stricter, instead add a `network_security_config.xml` that permits cleartext ONLY for `193.168.0.1`. Recommended (Task 3.3).

### Task 3.3 — `res/xml/network_security_config.xml` (recommended, stricter)

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">193.168.0.1</domain>
    </domain-config>
    <base-config cleartextTrafficPermitted="false"/>
</network-security-config>
```
Reference it: `android:networkSecurityConfig="@xml/network_security_config"` on `<application>` and remove `usesCleartextTraffic`.

---

## 4. Package / File Structure

Create exactly these packages and files. (Empty now; filled by later tasks.)

```
com.ddpai.uploader
├── App.kt                              (Application + WorkManager config + notif channels)
├── di/
│   └── ServiceLocator.kt               (manual DI — no Hilt to stay lightweight)
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt
│   │   ├── VideoFileDao.kt
│   │   ├── LogDao.kt
│   │   ├── entity/
│   │   │   ├── VideoFileEntity.kt
│   │   │   └── LogEntity.kt
│   │   └── Converters.kt
│   ├── config/
│   │   ├── ConfigRepository.kt         (EncryptedSharedPreferences + DataStore)
│   │   └── AppConfig.kt                (data class)
│   ├── repo/
│   │   ├── FileRepository.kt
│   │   └── LogRepository.kt
│   └── model/
│       ├── FileStatus.kt               (enum)
│       ├── DashcamFile.kt              (parsed listing item)
│       └── LogLevel.kt                 (enum)
├── network/
│   ├── NetworkMonitor.kt
│   ├── NetworkType.kt                  (enum: DASHCAM_AP, HOME_WIFI, OTHER, NONE)
│   └── BoundHttpClientFactory.kt
├── dashcam/
│   ├── DashcamClient.kt
│   └── DashcamFileListParser.kt
├── integrity/
│   ├── IntegrityVerifier.kt
│   └── Mp4AtomScanner.kt
├── youtube/
│   ├── YouTubeAuthManager.kt           (AppAuth OAuth2)
│   └── YouTubeUploader.kt              (resumable upload)
├── pipeline/
│   ├── DownloadWorker.kt
│   ├── UploadWorker.kt
│   ├── PipelineForegroundService.kt
│   └── PipelineScheduler.kt            (enqueues workers)
└── ui/
    ├── MainActivity.kt
    ├── theme/ (Color.kt, Type.kt, Theme.kt)
    ├── nav/ AppNav.kt
    ├── dashboard/ DashboardScreen.kt, DashboardViewModel.kt
    ├── files/ FileListScreen.kt
    ├── player/ PlayerScreen.kt, PlayerViewModel.kt
    ├── config/ ConfigScreen.kt, ConfigViewModel.kt
    └── logs/ LogConsoleScreen.kt
```

---

## 5. Data Model & Database (Room)

### Task 5.1 — `data/model/FileStatus.kt`

```kotlin
enum class FileStatus {
    DISCOVERED,   // seen in dashcam listing, not yet downloaded
    DOWNLOADING,  // download in progress
    DOWNLOADED,   // on disk, integrity-verified, awaiting upload
    UPLOADING,    // upload in progress
    UPLOADED,     // uploaded to YouTube; local file deleted
    PENDING,      // reset state after a recoverable failure (re-attempt download)
    FAILED        // permanent failure after max retries (visible to user)
}
```

### Task 5.2 — `data/model/LogLevel.kt`

```kotlin
enum class LogLevel { DEBUG, INFO, WARN, ERROR }
```

### Task 5.3 — `data/db/entity/VideoFileEntity.kt`

> **`fileName` is the PRIMARY KEY.** This is the single mechanism guaranteeing no double-download/upload. Filenames embed timestamp and are unique on the dashcam.

```kotlin
@Entity(tableName = "video_files")
data class VideoFileEntity(
    @PrimaryKey val fileName: String,         // e.g. 20260626180905_0060.mp4
    val remoteUrl: String,                    // http://193.168.0.1/<fileName>
    val localPath: String?,                   // absolute path once downloaded, null after deletion
    val status: String,                       // FileStatus.name
    val sizeBytes: Long = 0L,                 // expected/actual size
    val downloadedBytes: Long = 0L,           // for resume + progress
    val youtubeVideoId: String? = null,
    val uploadSessionUrl: String? = null,     // resumable upload URI to resume interrupted uploads
    val retryCount: Int = 0,
    val errorMessage: String? = null,
    val capturedAtEpoch: Long = 0L,           // parsed from filename
    val discoveredAtEpoch: Long = System.currentTimeMillis(),
    val updatedAtEpoch: Long = System.currentTimeMillis()
)
```

### Task 5.4 — `data/db/entity/LogEntity.kt`

```kotlin
@Entity(tableName = "app_logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val level: String,        // LogLevel.name
    val tag: String,          // e.g. "DownloadWorker"
    val message: String,
    val fileName: String? = null
)
```

### Task 5.5 — `data/db/VideoFileDao.kt`

```kotlin
@Dao
interface VideoFileDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(file: VideoFileEntity): Long

    @Update suspend fun update(file: VideoFileEntity)

    @Query("SELECT * FROM video_files WHERE fileName = :name LIMIT 1")
    suspend fun getByName(name: String): VideoFileEntity?

    @Query("SELECT fileName FROM video_files")
    suspend fun getAllKnownFileNames(): List<String>

    @Query("SELECT * FROM video_files WHERE status IN (:statuses) ORDER BY capturedAtEpoch ASC")
    suspend fun getByStatuses(statuses: List<String>): List<VideoFileEntity>

    // Oldest-first queue for uploads
    @Query("SELECT * FROM video_files WHERE status = 'DOWNLOADED' ORDER BY capturedAtEpoch ASC LIMIT 1")
    suspend fun nextToUpload(): VideoFileEntity?

    @Query("SELECT * FROM video_files WHERE status IN ('DISCOVERED','PENDING') ORDER BY capturedAtEpoch ASC")
    suspend fun pendingDownloads(): List<VideoFileEntity>

    @Query("SELECT * FROM video_files ORDER BY capturedAtEpoch DESC")
    fun observeAll(): Flow<List<VideoFileEntity>>

    @Query("SELECT COUNT(*) FROM video_files WHERE status = :status")
    fun observeCountByStatus(status: String): Flow<Int>

    @Query("UPDATE video_files SET status = :status, updatedAtEpoch = :ts WHERE fileName = :name")
    suspend fun setStatus(name: String, status: String, ts: Long = System.currentTimeMillis())
}
```

### Task 5.6 — `data/db/LogDao.kt`

```kotlin
@Dao
interface LogDao {
    @Insert suspend fun insert(log: LogEntity)

    @Query("SELECT * FROM app_logs ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<LogEntity>>

    // Ring-buffer cleanup to stay lightweight
    @Query("DELETE FROM app_logs WHERE id NOT IN (SELECT id FROM app_logs ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int = 2000)

    @Query("DELETE FROM app_logs")
    suspend fun clear()
}
```

### Task 5.7 — `data/db/AppDatabase.kt`

```kotlin
@Database(entities = [VideoFileEntity::class, LogEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoFileDao(): VideoFileDao
    abstract fun logDao(): LogDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "ddpai.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
```

---
## 6. Configuration Storage (Secrets)

### Task 6.1 — `data/config/AppConfig.kt`

```kotlin
data class AppConfig(
    val youtubeClientId: String = "",
    val youtubeClientSecret: String = "",       // only needed for "Web/Installed" flow; AppAuth installed-app can use PKCE without secret
    val uploadPrivacy: String = "private",        // private | unlisted | public
    val homeWifiBssidOptional: String = "",       // optional manual override
    val deleteAfterUpload: Boolean = true,
    val wifiAutoStart: Boolean = true,
    val dashcamGateway: String = "193.168.0.1",  // overridable in case firmware differs
    val maxRetries: Int = 5
)
```

### Task 6.2 — `data/config/ConfigRepository.kt`

Use **EncryptedSharedPreferences** (androidx.security.crypto) for `youtubeClientId`/`youtubeClientSecret` and OAuth tokens. Use DataStore Preferences for non-secret toggles. Expose config as a `StateFlow<AppConfig>`.

```kotlin
class ConfigRepository(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

    private val securePrefs = EncryptedSharedPreferences.create(
        context, "secure_config", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    fun save(config: AppConfig) {
        securePrefs.edit()
            .putString("clientId", config.youtubeClientId)
            .putString("clientSecret", config.youtubeClientSecret)
            .putString("privacy", config.uploadPrivacy)
            .putBoolean("deleteAfterUpload", config.deleteAfterUpload)
            .putBoolean("wifiAutoStart", config.wifiAutoStart)
            .putString("gateway", config.dashcamGateway)
            .putInt("maxRetries", config.maxRetries)
            .apply()
        _config.value = config
    }

    private fun load(): AppConfig = AppConfig(
        youtubeClientId = securePrefs.getString("clientId", "") ?: "",
        youtubeClientSecret = securePrefs.getString("clientSecret", "") ?: "",
        uploadPrivacy = securePrefs.getString("privacy", "private") ?: "private",
        deleteAfterUpload = securePrefs.getBoolean("deleteAfterUpload", true),
        wifiAutoStart = securePrefs.getBoolean("wifiAutoStart", true),
        dashcamGateway = securePrefs.getString("gateway", "193.168.0.1") ?: "193.168.0.1",
        maxRetries = securePrefs.getInt("maxRetries", 5)
    )

    fun isConfigured(): Boolean = _config.value.youtubeClientId.isNotBlank()

    // OAuth token persistence (AppAuth AuthState serialized JSON)
    fun saveAuthState(json: String) = securePrefs.edit().putString("authState", json).apply()
    fun loadAuthState(): String? = securePrefs.getString("authState", null)
    fun clearAuthState() = securePrefs.edit().remove("authState").apply()
}
```

> **PKCE note:** For an Android "installed app" OAuth client, Google does NOT issue a usable client secret; the app uses **PKCE**. Therefore the Config screen should mark *Client Secret* as **optional** and the auth flow must use PKCE. Keep the secret field for users who created a "Web application" credential, but the primary supported path is **installed-app + PKCE** with redirect `com.ddpai.uploader:/oauth2redirect`.

---

## 7. Networking — Dashcam Detection & Socket Binding (Spec a)

### Task 7.1 — `network/NetworkType.kt`

```kotlin
enum class NetworkType { DASHCAM_AP, HOME_WIFI, OTHER, NONE }
```

### Task 7.2 — `network/NetworkMonitor.kt`

Core responsibilities:
1. Register a `ConnectivityManager.NetworkCallback`.
2. On every network available/changed, fetch `LinkProperties`.
3. Extract the **DHCP gateway** for the active Wi-Fi link. Two robust methods (use both, OR them):
   - **Route-based:** iterate `linkProperties.routes`; find the default route (`route.isDefaultRoute`) whose `gateway` is an IPv4 address; compare with configured gateway (`193.168.0.1`).
   - **DhcpInfo fallback (API ≤ 30):** `wifiManager.dhcpInfo.gateway` → convert int to dotted IP.
4. Classify:
   - gateway == configured dashcam gateway → `DASHCAM_AP`
   - has Wi-Fi transport + `NET_CAPABILITY_VALIDATED` (real internet) → `HOME_WIFI`
   - else `OTHER`/`NONE`.
5. Keep a reference to the **`Network` object** of the dashcam link; this is what we bind sockets to.

```kotlin
class NetworkMonitor(
    private val context: Context,
    private val configRepo: ConfigRepository,
    private val onNetwork: (NetworkType, Network?) -> Unit
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkTransport.TRANSPORT_WIFI) // import android.net.NetworkCapabilities
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = evaluate(network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = evaluate(network)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = evaluate(network)
            override fun onLost(network: Network) { onNetwork(NetworkType.NONE, null) }
        }
        callback = cb
        cm.registerNetworkCallback(request, cb)
    }

    fun stop() { callback?.let { cm.unregisterNetworkCallback(it) }; callback = null }

    private fun evaluate(network: Network) {
        val caps = cm.getNetworkCapabilities(network) ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            onNetwork(NetworkType.OTHER, null); return
        }
        val lp = cm.getLinkProperties(network)
        val gatewayConfigured = configRepo.config.value.dashcamGateway
        val gatewayIp = extractGateway(lp)
        val type = when {
            gatewayIp == gatewayConfigured -> NetworkType.DASHCAM_AP
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> NetworkType.HOME_WIFI
            else -> NetworkType.OTHER
        }
        onNetwork(type, network)
    }

    private fun extractGateway(lp: LinkProperties?): String? {
        lp ?: return null
        // Method 1: default route gateway
        lp.routes.forEach { r ->
            val gw = r.gateway
            if (r.isDefaultRoute && gw is java.net.Inet4Address) {
                return gw.hostAddress
            }
        }
        // Method 2: DhcpInfo (legacy)
        return try {
            val wifi = context.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val g = wifi.dhcpInfo?.gateway ?: return null
            if (g == 0) null else String.format(
                "%d.%d.%d.%d", g and 0xff, g shr 8 and 0xff, g shr 16 and 0xff, g shr 24 and 0xff
            )
        } catch (e: Exception) { null }
    }
}
```

> **CRITICAL (Spec a — socket binding):** Phones route HTTP to whichever network has internet. The dashcam AP has **no internet**, so the OS will try to send traffic over mobile data and the request to `193.168.0.1` fails. You MUST bind. Two valid approaches — use **per-OkHttpClient binding** (preferred, does not affect the whole process):

### Task 7.3 — `network/BoundHttpClientFactory.kt`

```kotlin
object BoundHttpClientFactory {
    /** OkHttp client whose sockets are forced onto the given (dashcam) network. */
    fun forNetwork(network: Network, callTimeoutSec: Long = 600): OkHttpClient =
        OkHttpClient.Builder()
            .socketFactory(network.socketFactory)            // <-- forces traffic onto dashcam Wi-Fi
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    /** Plain client for YouTube (uses default/internet network). */
    fun default(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
}
```

> Using `network.socketFactory` is cleaner than the global `bindProcessToNetwork`, because the YouTube upload (which needs the *internet* network) runs on a different network than dashcam downloads. The two never conflict. If a firmware quirk forces it, you may *additionally* call `cm.bindProcessToNetwork(network)` around the download block and `cm.bindProcessToNetwork(null)` in a `finally`. **Document both; default to socketFactory binding.**

---
## 8. Dashcam Client — Listing & Download (Spec b)

### Task 8.1 — `data/model/DashcamFile.kt`

```kotlin
data class DashcamFile(
    val fileName: String,
    val sizeBytes: Long,
    val capturedAtEpoch: Long
)
```

### Task 8.2 — `dashcam/DashcamFileListParser.kt`

> **Firmware variance is real.** The listing endpoint may return JSON OR an HTML directory page. Implement BOTH parsers and auto-detect by sniffing the first non-whitespace char (`{`/`[` → JSON, else HTML).

Filename regex (authoritative): `\d{8}\d{6}_(0060|F|R)\.mp4` → capture the 8-digit date + 6-digit time to compute `capturedAtEpoch`.

```kotlin
object DashcamFileListParser {
    private val NAME_RE = Regex("""(\d{8})(\d{6})_(0060|F|R)\.mp4""", RegexOption.IGNORE_CASE)

    fun parse(body: String): List<DashcamFile> {
        val trimmed = body.trimStart()
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) parseJson(body)
        else parseHtml(body)
    }

    private fun parseHtml(html: String): List<DashcamFile> =
        NAME_RE.findAll(html).map { m ->
            DashcamFile(m.value, 0L, epochFrom(m.groupValues[1], m.groupValues[2]))
        }.distinctBy { it.fileName }.toList()

    private fun parseJson(json: String): List<DashcamFile> {
        // Firmware-dependent shape. Robust approach: regex-scan the JSON text for filenames,
        // then attempt to read an adjacent "size"/"fsize" number if present.
        val files = NAME_RE.findAll(json).map { it.value }.distinct().toList()
        return files.map { name ->
            val m = NAME_RE.find(name)!!
            DashcamFile(name, 0L, epochFrom(m.groupValues[1], m.groupValues[2]))
        }
    }

    private fun epochFrom(date8: String, time6: String): Long = try {
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getDefault()
        fmt.parse(date8 + time6)?.time ?: 0L
    } catch (e: Exception) { 0L }
}
```

### Task 8.3 — `dashcam/DashcamClient.kt`

Responsibilities: (1) fetch listing, (2) stream-download a single file with resume support + progress callback. All requests use a client bound to the dashcam `Network`.

```kotlin
class DashcamClient(
    private val network: Network,
    private val gateway: String,                 // "193.168.0.1"
    private val logger: LogRepository
) {
    private val client = BoundHttpClientFactory.forNetwork(network)
    private val base = "http://$gateway"

    /** Spec b: try known command variants until one returns parseable file names. */
    suspend fun listFiles(): List<DashcamFile> = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "$base/vcam/cmd.cgi?cmd=getFileList",
            "$base/vcam/cmd.cgi?cmd=getfilelist",
            "$base/vcam/cmd.cgi",
            "$base/"                              // last resort: root HTML directory listing
        )
        for (url in endpoints) {
            try {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    val files = DashcamFileListParser.parse(body)
                    if (files.isNotEmpty()) {
                        logger.i("DashcamClient", "Listing OK via $url → ${files.size} files")
                        return@withContext files
                    }
                }
            } catch (e: Exception) {
                logger.w("DashcamClient", "Listing endpoint failed: $url (${e.message})")
            }
        }
        logger.w("DashcamClient", "No files found from any endpoint")
        emptyList()
    }

    /**
     * Streams http://gateway/<fileName> to [target]. Supports HTTP Range resume from existingBytes.
     * Returns total bytes written. Throws on network error (caller handles retry).
     */
    suspend fun download(
        fileName: String,
        target: File,
        existingBytes: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val url = "$base/$fileName"
        val reqBuilder = Request.Builder().url(url).get()
        if (existingBytes > 0) reqBuilder.header("Range", "bytes=$existingBytes-")
        val request = reqBuilder.build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                throw IOException("HTTP ${resp.code} for $url")
            }
            val body = resp.body ?: throw IOException("Empty body for $url")
            val totalFromHeader = body.contentLength().let {
                if (it > 0) it + existingBytes else -1L
            }
            val append = existingBytes > 0 && resp.code == 206
            RandomAccessFile(target, "rw").use { raf ->
                if (append) raf.seek(existingBytes) else raf.setLength(0)
                val sink = raf
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var written = existingBytes
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        sink.write(buf, 0, n)
                        written += n
                        onProgress(written, totalFromHeader)
                    }
                    return@withContext written
                }
            }
        }
    }
}
```

> **Edge case (Spec c — corrupted HTML):** If the camera is busy it returns a tiny HTML error body (58 bytes–80 KB) at the download URL. We do NOT trust HTTP success alone. After download we run the integrity check (Task 9). If the file is < 1 MB OR lacks MP4 atoms, it is corrupt → delete + reset to PENDING.

---

## 9. Integrity Verification (Spec d)

### Task 9.1 — Rules (authoritative)
A downloaded file is **VALID** only if ALL hold:
1. File size ≥ **1 MB** (1,048,576 bytes). Smaller ⇒ HTML error body ⇒ corrupt.
2. Contains an `ftyp` atom near the start.
3. Contains an `mdat` atom.
4. Contains a `moov` atom (the metadata index; missing on aborted/interrupted writes).

If any fail ⇒ corrupt ⇒ delete file, set DB status `PENDING`, increment retryCount, log ERROR.

### Task 9.2 — `integrity/Mp4AtomScanner.kt` (PRIMARY, pure-Kotlin, no native dep)

Scans the MP4 box structure by walking top-level atoms. Each atom: 4-byte big-endian size + 4-byte type. Size 1 ⇒ 64-bit extended size in next 8 bytes; size 0 ⇒ extends to EOF.

```kotlin
object Mp4AtomScanner {
    data class Result(val hasFtyp: Boolean, val hasMoov: Boolean, val hasMdat: Boolean, val sizeOk: Boolean) {
        val isValid get() = hasFtyp && hasMoov && hasMdat && sizeOk
    }
    private const val MIN_SIZE = 1_048_576L

    fun scan(file: File): Result {
        if (!file.exists()) return Result(false, false, false, false)
        val sizeOk = file.length() >= MIN_SIZE
        var hasFtyp = false; var hasMoov = false; var hasMdat = false
        try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                var pos = 0L
                while (pos + 8 <= len) {
                    raf.seek(pos)
                    val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
                    val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
                    val (atomSize, headerSize) = when (size32) {
                        1L -> Pair(raf.readLong(), 16L)   // 64-bit size
                        0L -> Pair(len - pos, 8L)         // to EOF
                        else -> Pair(size32, 8L)
                    }
                    when (type) {
                        "ftyp" -> hasFtyp = true
                        "moov" -> hasMoov = true
                        "mdat" -> hasMdat = true
                    }
                    if (atomSize < headerSize) break       // malformed; stop
                    pos += atomSize
                    if (hasFtyp && hasMoov && hasMdat) break
                }
            }
        } catch (e: Exception) { /* treat as failure below */ }
        return Result(hasFtyp, hasMoov, hasMdat, sizeOk)
    }
}
```

### Task 9.3 — `integrity/IntegrityVerifier.kt`

```kotlin
class IntegrityVerifier(private val logger: LogRepository) {
    data class Verdict(val valid: Boolean, val reason: String)

    suspend fun verify(file: File): Verdict = withContext(Dispatchers.IO) {
        val scan = Mp4AtomScanner.scan(file)
        if (!scan.sizeOk) return@withContext Verdict(false, "size<1MB (${file.length()} bytes) — likely HTML error body")
        if (!scan.hasFtyp) return@withContext Verdict(false, "missing ftyp atom")
        if (!scan.hasMdat) return@withContext Verdict(false, "missing mdat atom")
        if (!scan.hasMoov) return@withContext Verdict(false, "missing moov atom — aborted/interrupted write")

        // SECONDARY confirmation via FFprobe (Spec d). Non-fatal if FFprobe unavailable.
        val probe = runFfprobe(file.absolutePath)
        if (probe != null && !probe) {
            return@withContext Verdict(false, "FFprobe could not read stream index")
        }
        Verdict(true, "ok")
    }

    /** Returns true=valid, false=invalid, null=ffprobe unavailable/inconclusive. */
    private fun runFfprobe(path: String): Boolean? = try {
        val session = com.arthenica.ffmpegkit.FFprobeKit.execute(
            "-v error -show_entries format=duration -of default=nw=1 \"$path\""
        )
        val rc = session.returnCode
        when {
            com.arthenica.ffmpegkit.ReturnCode.isSuccess(rc) -> {
                val out = session.output.orEmpty()
                out.contains("duration=") && !out.contains("N/A")
            }
            else -> false
        }
    } catch (t: Throwable) {
        logger.w("IntegrityVerifier", "FFprobe unavailable, relying on atom scan: ${t.message}")
        null
    }
}
```

> **Why atom-scan is primary:** It has zero native dependency risk, runs in milliseconds, and directly checks the `moov` constraint from Spec d. FFprobe is a belt-and-suspenders confirmation that gracefully no-ops if the native lib fails to load.

---
## 10. YouTube OAuth + Resumable Upload

### Task 10.1 — Google Cloud setup (document for the user, shown in-app on Config screen)
1. Create a project at console.cloud.google.com.
2. Enable **YouTube Data API v3**.
3. Configure OAuth consent screen (External; add the user's Google account as a test user; scope `https://www.googleapis.com/auth/youtube.upload`).
4. Create **OAuth client ID → Android** (provide package name `com.ddpai.uploader` + SHA-1) **OR** type **Desktop/Installed** for PKCE custom-scheme flow.
5. Copy the **Client ID** (and Secret if Web type) into the app's Config screen.
6. Redirect URI for AppAuth: `com.ddpai.uploader:/oauth2redirect`.

> **Quota reality (state this in the UI):** Default YouTube Data API quota is 10,000 units/day; each upload costs ~1,600 units ⇒ ~6 uploads/day until a quota increase is granted. The app must surface `quotaExceeded` errors clearly and pause uploads until next day.

### Task 10.2 — `youtube/YouTubeAuthManager.kt` (AppAuth + PKCE)

```kotlin
class YouTubeAuthManager(
    private val context: Context,
    private val configRepo: ConfigRepository
) {
    private val authService = AuthorizationService(context)
    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token")
    )
    private val redirectUri = Uri.parse("com.ddpai.uploader:/oauth2redirect")
    private val scope = "https://www.googleapis.com/auth/youtube.upload"

    fun buildAuthIntent(): Intent {
        val clientId = configRepo.config.value.youtubeClientId
        val req = AuthorizationRequest.Builder(
            serviceConfig, clientId, ResponseTypeValues.CODE, redirectUri
        ).setScope(scope).build()      // AppAuth adds PKCE automatically
        return authService.getAuthorizationRequestIntent(req)
    }

    /** Call from onActivityResult / ActivityResult callback. */
    suspend fun handleAuthResponse(data: Intent): Boolean = suspendCancellableCoroutine { cont ->
        val resp = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)
        if (resp == null) { cont.resume(false); return@suspendCancellableCoroutine }
        val tokenReq = resp.createTokenExchangeRequest()  // includes PKCE verifier
        authService.performTokenRequest(tokenReq) { tokenResp, tokenEx ->
            if (tokenResp != null) {
                val authState = AuthState(resp, tokenResp, tokenEx)
                configRepo.saveAuthState(authState.jsonSerializeString())
                cont.resume(true)
            } else cont.resume(false)
        }
    }

    /** Returns a fresh access token, refreshing if needed. Null if not authed. */
    suspend fun freshAccessToken(): String? = suspendCancellableCoroutine { cont ->
        val json = configRepo.loadAuthState() ?: run { cont.resume(null); return@suspendCancellableCoroutine }
        val state = AuthState.jsonDeserialize(json)
        state.performActionWithFreshTokens(authService) { token, _, ex ->
            if (token != null) { configRepo.saveAuthState(state.jsonSerializeString()); cont.resume(token) }
            else cont.resume(null)
        }
    }

    fun isAuthorized(): Boolean =
        configRepo.loadAuthState()?.let { AuthState.jsonDeserialize(it).isAuthorized } ?: false

    fun signOut() = configRepo.clearAuthState()
}
```

### Task 10.3 — `youtube/YouTubeUploader.kt` (resumable, single file)

> Use **raw resumable protocol over OkHttp** (not the heavy `MediaHttpUploader`) so we can persist the **resumable session URI** and resume across process death. This is what makes uploads fault-tolerant (Spec 6).

Flow:
1. **Initiate:** `POST https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status` with headers `Authorization: Bearer <token>`, `X-Upload-Content-Type: video/mp4`, `X-Upload-Content-Length: <size>`, JSON body = snippet+status. Response `Location` header = **session URI**. Persist it to DB (`uploadSessionUrl`).
2. **Upload bytes:** `PUT <sessionUri>` with `Content-Length` and (for resume) `Content-Range: bytes <start>-<end>/<total>`. Stream the file from `start`.
3. **Resume after interruption:** `PUT <sessionUri>` with `Content-Range: bytes */<total>` and empty body → server replies `308` with `Range: bytes=0-<lastByte>`; resume from `lastByte+1`.
4. **Success:** `200`/`201` with JSON containing video `id` → return it.

```kotlin
class YouTubeUploader(
    private val auth: YouTubeAuthManager,
    private val configRepo: ConfigRepository,
    private val logger: LogRepository
) {
    private val http = BoundHttpClientFactory.default()
    private val INIT_URL =
        "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status"

    suspend fun initiate(file: File, title: String): String {
        val token = auth.freshAccessToken() ?: throw IllegalStateException("Not authorized")
        val privacy = configRepo.config.value.uploadPrivacy
        val metaJson = """
            {"snippet":{"title":${title.json()},"description":"Auto-uploaded dashcam clip","categoryId":"2"},
             "status":{"privacyStatus":"$privacy","selfDeclaredMadeForKids":false}}
        """.trimIndent()
        val req = Request.Builder().url(INIT_URL)
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", "video/mp4")
            .header("X-Upload-Content-Length", file.length().toString())
            .post(metaJson.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("init ${resp.code}: ${resp.body?.string()}")
            return resp.header("Location") ?: throw IOException("No resumable session URI")
        }
    }

    /** Queries server for how many bytes are already stored. Returns next byte offset to send. */
    suspend fun queryOffset(sessionUri: String, total: Long): Long {
        val token = auth.freshAccessToken() ?: throw IllegalStateException("Not authorized")
        val req = Request.Builder().url(sessionUri)
            .header("Authorization", "Bearer $token")
            .header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { resp ->
            return when (resp.code) {
                200, 201 -> total                                  // already complete
                308 -> resp.header("Range")?.substringAfterLast("-")?.toLongOrNull()?.plus(1) ?: 0L
                else -> 0L
            }
        }
    }

    /** Uploads from [startByte]; returns YouTube video ID on success. */
    suspend fun uploadFrom(
        sessionUri: String, file: File, startByte: Long,
        onProgress: (sent: Long, total: Long) -> Unit
    ): String {
        val token = auth.freshAccessToken() ?: throw IllegalStateException("Not authorized")
        val total = file.length()
        val body = FileRangeRequestBody(file, startByte, total, onProgress)
        val req = Request.Builder().url(sessionUri)
            .header("Authorization", "Bearer $token")
            .header("Content-Range", "bytes $startByte-${total - 1}/$total")
            .put(body)
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 200 || resp.code == 201) {
                val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(resp.body?.string().orEmpty())?.groupValues?.get(1)
                return id ?: throw IOException("Upload ok but no video id")
            }
            throw IOException("upload ${resp.code}: ${resp.body?.string()}")
        }
    }
}

// Helper: streams a file slice as an OkHttp RequestBody with progress.
class FileRangeRequestBody(
    private val file: File, private val start: Long, private val total: Long,
    private val onProgress: (Long, Long) -> Unit
) : RequestBody() {
    override fun contentType() = "video/mp4".toMediaType()
    override fun contentLength() = total - start
    override fun writeTo(sink: BufferedSink) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(256 * 1024); var sent = start
            while (true) {
                val n = raf.read(buf); if (n == -1) break
                sink.write(buf, 0, n); sent += n; onProgress(sent, total)
            }
        }
    }
}
private fun String.json() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
```

---
## 11. Repositories

### Task 11.1 — `data/repo/LogRepository.kt`

```kotlin
class LogRepository(private val dao: LogDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun observe(limit: Int = 500) = dao.observeRecent(limit)

    private fun log(level: LogLevel, tag: String, msg: String, file: String? = null) {
        when (level) {
            LogLevel.DEBUG -> android.util.Log.d(tag, msg)
            LogLevel.INFO  -> android.util.Log.i(tag, msg)
            LogLevel.WARN  -> android.util.Log.w(tag, msg)
            LogLevel.ERROR -> android.util.Log.e(tag, msg)
        }
        scope.launch {
            dao.insert(LogEntity(level = level.name, tag = tag, message = msg, fileName = file))
            dao.trimTo(2000)
        }
    }
    fun d(tag: String, m: String, f: String? = null) = log(LogLevel.DEBUG, tag, m, f)
    fun i(tag: String, m: String, f: String? = null) = log(LogLevel.INFO, tag, m, f)
    fun w(tag: String, m: String, f: String? = null) = log(LogLevel.WARN, tag, m, f)
    fun e(tag: String, m: String, f: String? = null) = log(LogLevel.ERROR, tag, m, f)
    suspend fun clear() = dao.clear()
}
```

### Task 11.2 — `data/repo/FileRepository.kt`

```kotlin
class FileRepository(
    private val dao: VideoFileDao,
    private val logger: LogRepository,
    private val storageDir: File
) {
    fun observeAll() = dao.observeAll()
    fun observeCount(status: FileStatus) = dao.observeCountByStatus(status.name)

    /** Insert any newly-discovered files. Existing fileNames are ignored (idempotent). */
    suspend fun reconcileListing(remote: List<DashcamFile>, gateway: String): Int {
        var added = 0
        remote.forEach { f ->
            val entity = VideoFileEntity(
                fileName = f.fileName,
                remoteUrl = "http://$gateway/${f.fileName}",
                localPath = null,
                status = FileStatus.DISCOVERED.name,
                sizeBytes = f.sizeBytes,
                capturedAtEpoch = f.capturedAtEpoch
            )
            if (dao.insertIgnore(entity) != -1L) { added++; logger.i("FileRepo", "Discovered ${f.fileName}") }
        }
        return added
    }

    suspend fun pendingDownloads() = dao.pendingDownloads()
    suspend fun nextToUpload() = dao.nextToUpload()
    suspend fun get(name: String) = dao.getByName(name)
    suspend fun update(e: VideoFileEntity) = dao.update(e.copy(updatedAtEpoch = System.currentTimeMillis()))
    suspend fun setStatus(name: String, s: FileStatus) = dao.setStatus(name, s.name)
    fun fileFor(name: String) = File(storageDir, name)

    suspend fun markCorruptAndReset(name: String, reason: String) {
        val e = dao.getByName(name) ?: return
        fileFor(name).delete()
        dao.update(e.copy(status = FileStatus.PENDING.name, localPath = null,
            downloadedBytes = 0, retryCount = e.retryCount + 1, errorMessage = reason,
            updatedAtEpoch = System.currentTimeMillis()))
        logger.e("FileRepo", "Corrupt $name → reset PENDING: $reason", name)
    }

    suspend fun markUploadedAndDelete(name: String, videoId: String, deleteLocal: Boolean) {
        val e = dao.getByName(name) ?: return
        if (deleteLocal) fileFor(name).delete()
        dao.update(e.copy(status = FileStatus.UPLOADED.name,
            localPath = if (deleteLocal) null else e.localPath,
            youtubeVideoId = videoId, errorMessage = null,
            updatedAtEpoch = System.currentTimeMillis()))
        logger.i("FileRepo", "Uploaded $name → $videoId, localDeleted=$deleteLocal", name)
    }
}
```

> **Storage location:** Use **app-internal** `context.filesDir/videos` (no storage permission needed, auto-cleaned on uninstall) OR `context.getExternalFilesDir("videos")` if files are large and you want them browsable. Recommended: `getExternalFilesDir` for capacity. Set `storageDir` accordingly in `ServiceLocator`.

---

## 12. Background Pipeline (WorkManager + Foreground Service)

### Design
- **DownloadWorker** runs when connected to `DASHCAM_AP`. Loops: list → reconcile DB → download each pending file → verify → mark DOWNLOADED.
- **UploadWorker** runs when connected to `HOME_WIFI`. Loops: take `nextToUpload()` (oldest DOWNLOADED) → resumable upload → mark UPLOADED + delete → repeat until queue empty.
- Both are **expedited unique work** with `KEEP` policy (never two of the same running). Both publish progress via `setForeground` (foreground service type dataSync) and write logs.
- A lightweight **PipelineForegroundService** is optional; with WorkManager's `setForegroundAsync`, you usually don't need a separate service. **Default: use WorkManager foreground info; keep the separate service file as a stub unless a long-lived socket needs it.**

### Task 12.1 — `pipeline/PipelineScheduler.kt`

```kotlin
object PipelineScheduler {
    const val DOWNLOAD_WORK = "ddpai_download"
    const val UPLOAD_WORK = "ddpai_upload"

    fun enqueueDownload(context: Context) {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DOWNLOAD_WORK, ExistingWorkPolicy.KEEP, req)
    }

    fun enqueueUpload(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UPLOAD_WORK, ExistingWorkPolicy.KEEP, req)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DOWNLOAD_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(UPLOAD_WORK)
    }
}
```

### Task 12.2 — `pipeline/DownloadWorker.kt`

```kotlin
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val sl = ServiceLocator.get(applicationContext)

    override suspend fun getForegroundInfo() =
        sl.notifications.downloadForegroundInfo(applicationContext, "Scanning dashcam…")

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        val network = sl.currentDashcamNetwork
            ?: run { sl.log.w("DownloadWorker", "No dashcam network bound"); return Result.success() }

        val gateway = sl.config.config.value.dashcamGateway
        val client = DashcamClient(network, gateway, sl.log)
        val verifier = IntegrityVerifier(sl.log)

        // 1. List + reconcile
        val remote = try { client.listFiles() } catch (e: Exception) {
            sl.log.e("DownloadWorker", "Listing failed: ${e.message}"); return Result.retry()
        }
        val added = sl.files.reconcileListing(remote, gateway)
        sl.log.i("DownloadWorker", "Listing reconciled: +$added new, ${remote.size} total")

        // 2. Download each pending file
        val pending = sl.files.pendingDownloads()
        for (item in pending) {
            if (sl.currentDashcamNetwork == null) { sl.log.w("DownloadWorker", "Lost AP; stopping"); break }
            val target = sl.files.fileFor(item.fileName)
            val existing = if (target.exists()) target.length() else 0L
            try {
                sl.files.setStatus(item.fileName, FileStatus.DOWNLOADING)
                sl.log.i("DownloadWorker", "Downloading ${item.fileName} (resume@$existing)", item.fileName)
                client.download(item.fileName, target, existing) { dl, total ->
                    sl.progress.updateDownload(item.fileName, dl, total)
                }
                // 3. Verify
                val verdict = verifier.verify(target)
                if (!verdict.valid) { sl.files.markCorruptAndReset(item.fileName, verdict.reason); continue }
                sl.files.update(item.copy(
                    status = FileStatus.DOWNLOADED.name,
                    localPath = target.absolutePath,
                    sizeBytes = target.length(),
                    downloadedBytes = target.length(),
                    errorMessage = null))
                sl.log.i("DownloadWorker", "DOWNLOADED ${item.fileName} (${target.length()} bytes)", item.fileName)
            } catch (e: Exception) {
                sl.log.e("DownloadWorker", "Download error ${item.fileName}: ${e.message}", item.fileName)
                val cur = sl.files.get(item.fileName)
                if (cur != null && cur.retryCount >= sl.config.config.value.maxRetries) {
                    sl.files.setStatus(item.fileName, FileStatus.FAILED)
                } else {
                    sl.files.setStatus(item.fileName, FileStatus.PENDING)
                }
                // keep going to next file; AP may still be up
            }
        }
        sl.log.i("DownloadWorker", "Download cycle complete")
        return Result.success()
    }
}
```

### Task 12.3 — `pipeline/UploadWorker.kt`

```kotlin
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val sl = ServiceLocator.get(applicationContext)

    override suspend fun getForegroundInfo() =
        sl.notifications.uploadForegroundInfo(applicationContext, "Uploading to YouTube…")

    override suspend fun doWork(): Result {
        if (!sl.config.isConfigured() || !sl.auth.isAuthorized()) {
            sl.log.w("UploadWorker", "Not configured/authorized; skipping"); return Result.success()
        }
        setForeground(getForegroundInfo())
        val uploader = YouTubeUploader(sl.auth, sl.config, sl.log)
        val deleteLocal = sl.config.config.value.deleteAfterUpload

        while (true) {
            val item = sl.files.nextToUpload() ?: break
            val file = sl.files.fileFor(item.fileName)
            if (!file.exists()) {                     // file vanished; mark uploaded skip or reset
                sl.files.setStatus(item.fileName, FileStatus.PENDING); continue
            }
            try {
                sl.files.setStatus(item.fileName, FileStatus.UPLOADING)
                // Resume if a session URI already exists
                var sessionUri = item.uploadSessionUrl
                var startByte = 0L
                if (sessionUri.isNullOrBlank()) {
                    sessionUri = uploader.initiate(file, item.fileName.removeSuffix(".mp4"))
                    sl.files.update(item.copy(uploadSessionUrl = sessionUri, status = FileStatus.UPLOADING.name))
                    sl.log.i("UploadWorker", "Initiated session for ${item.fileName}", item.fileName)
                } else {
                    startByte = uploader.queryOffset(sessionUri, file.length())
                    sl.log.i("UploadWorker", "Resuming ${item.fileName} @ $startByte", item.fileName)
                }
                val videoId = if (startByte >= file.length()) {
                    // already fully stored; treat as complete (rare) — re-query for id not provided; force re-init
                    uploader.uploadFrom(sessionUri, file, 0L) { s, t -> sl.progress.updateUpload(item.fileName, s, t) }
                } else {
                    uploader.uploadFrom(sessionUri, file, startByte) { s, t -> sl.progress.updateUpload(item.fileName, s, t) }
                }
                sl.files.markUploadedAndDelete(item.fileName, videoId, deleteLocal)
            } catch (e: Exception) {
                val msg = e.message ?: "unknown"
                sl.log.e("UploadWorker", "Upload error ${item.fileName}: $msg", item.fileName)
                if (msg.contains("quota", true) || msg.contains("403")) {
                    sl.log.w("UploadWorker", "Quota likely exceeded; pausing uploads for today")
                    return Result.retry()       // backoff; try later
                }
                val cur = sl.files.get(item.fileName)
                if (cur != null && cur.retryCount >= sl.config.config.value.maxRetries) {
                    sl.files.setStatus(item.fileName, FileStatus.FAILED)
                } else {
                    // keep session URI for resume; bump retry, leave as DOWNLOADED to retry later
                    sl.files.update((cur ?: item).copy(
                        status = FileStatus.DOWNLOADED.name,
                        retryCount = (cur?.retryCount ?: 0) + 1, errorMessage = msg))
                    return Result.retry()
                }
            }
        }
        sl.log.i("UploadWorker", "Upload queue drained")
        return Result.success()
    }
}
```

> **Fault-tolerance summary (Spec 6):**
> - Download resume via HTTP `Range`; partial files continue, not restart.
> - Integrity gate prevents corrupt files from ever uploading.
> - Upload resume via persisted resumable session URI + `Content-Range */total` offset query.
> - WorkManager retries with exponential backoff; `retryCount` caps to `FAILED` so nothing loops forever.
> - All state in SQLite ⇒ survives process death, reboot, app kill.
> - Network binding ensures correct interface; losing AP mid-download just pauses that cycle.

---
## 13. App Wiring — Application, ServiceLocator, Notifications, Progress

### Task 13.1 — `di/ServiceLocator.kt` (manual DI, lightweight, no Hilt)

```kotlin
class ServiceLocator private constructor(context: Context) {
    val db = AppDatabase.get(context)
    val log = LogRepository(db.logDao())
    val config = ConfigRepository(context)
    private val storageDir = File(context.getExternalFilesDir(null), "videos").apply { mkdirs() }
    val files = FileRepository(db.videoFileDao(), log, storageDir)
    val auth = YouTubeAuthManager(context, config)
    val notifications = NotificationHelper
    val progress = ProgressBus

    // Live dashcam network handle set by NetworkMonitor
    @Volatile var currentDashcamNetwork: Network? = null

    companion object {
        @Volatile private var I: ServiceLocator? = null
        fun get(context: Context): ServiceLocator =
            I ?: synchronized(this) { I ?: ServiceLocator(context.applicationContext).also { I = it } }
    }
}
```

### Task 13.2 — `pipeline/ProgressBus.kt` (in-memory progress for UI status bar)

```kotlin
object ProgressBus {
    data class Progress(val fileName: String, val current: Long, val total: Long, val kind: String)
    private val _state = MutableStateFlow<Progress?>(null)
    val state: StateFlow<Progress?> = _state.asStateFlow()
    fun updateDownload(name: String, cur: Long, total: Long) { _state.value = Progress(name, cur, total, "download") }
    fun updateUpload(name: String, cur: Long, total: Long) { _state.value = Progress(name, cur, total, "upload") }
    fun clear() { _state.value = null }
}
```

### Task 13.3 — `NotificationHelper` (channels + ForegroundInfo)

```kotlin
object NotificationHelper {
    const val CHANNEL = "ddpai_pipeline"
    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "Dashcam Pipeline", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
    private fun build(context: Context, text: String) =
        NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle("DDPAI Uploader").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setOngoing(true).build()

    fun downloadForegroundInfo(context: Context, text: String): ForegroundInfo {
        val n = build(context, text)
        return if (Build.VERSION.SDK_INT >= 34)
            ForegroundInfo(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(1001, n)
    }
    fun uploadForegroundInfo(context: Context, text: String): ForegroundInfo {
        val n = build(context, text)
        return if (Build.VERSION.SDK_INT >= 34)
            ForegroundInfo(1002, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(1002, n)
    }
}
```

### Task 13.4 — `App.kt`

```kotlin
class App : Application(), Configuration.Provider {
    lateinit var networkMonitor: NetworkMonitor
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        val sl = ServiceLocator.get(this)
        networkMonitor = NetworkMonitor(this, sl.config) { type, network ->
            when (type) {
                NetworkType.DASHCAM_AP -> {
                    sl.currentDashcamNetwork = network
                    sl.log.i("App", "Dashcam AP detected; enqueue download")
                    if (sl.config.config.value.wifiAutoStart) PipelineScheduler.enqueueDownload(this)
                }
                NetworkType.HOME_WIFI -> {
                    sl.currentDashcamNetwork = null
                    sl.log.i("App", "Home Wi-Fi detected; enqueue upload")
                    if (sl.config.config.value.wifiAutoStart) PipelineScheduler.enqueueUpload(this)
                }
                else -> { sl.currentDashcamNetwork = null }
            }
        }
        networkMonitor.start()
    }
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
```

> Register `App` in manifest (`android:name=".App"`). Because we provide `Configuration.Provider`, **remove** the default WorkManager initializer in manifest (add the `<provider>` removal snippet from WorkManager docs) OR keep default init and drop `Configuration.Provider`. Simplest: drop `Configuration.Provider` and let WorkManager auto-init. **Choose ONE; document the auto-init path as default.**

---

## 14. UI Layer (Compose, Material 3)

> **Design language (Spec 7):** Dark-first "developer console" aesthetic. Monospace for logs, rounded cards for files, a persistent top **status bar** showing current pipeline state + live progress. Accent color teal `#3DDC97`, surfaces near-black `#0E1116`, warning amber, error red. Use Material 3 dynamic color as fallback.

### Task 14.1 — Navigation `ui/nav/AppNav.kt`
Bottom navigation with 4 destinations: **Dashboard**, **Files**, **Logs**, **Config**. Player is a nested route `player/{fileName}`.

```kotlin
sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Dest("dashboard", "Dashboard", Icons.Filled.Dashboard)
    data object Files : Dest("files", "Files", Icons.Filled.VideoLibrary)
    data object Logs : Dest("logs", "Logs", Icons.Filled.Terminal)
    data object Config : Dest("config", "Config", Icons.Filled.Settings)
}
```

### Task 14.2 — Dashboard `ui/dashboard/DashboardScreen.kt` + `DashboardViewModel.kt`
Shows:
- **Status bar card** at top: current `NetworkType`, current action (Idle/Downloading/Uploading), live progress bar from `ProgressBus`.
- **Counters row** (StateFlow counts): Discovered, Downloaded (queued), Uploaded, Failed.
- **Quick actions:** "Scan now" (enqueue download if on AP), "Upload now" (enqueue upload), "Authorize YouTube".
- **Mini log tail** (last ~8 lines), tap → Logs screen.

`DashboardViewModel` exposes:
```kotlin
data class DashUiState(
    val networkType: NetworkType,
    val progress: ProgressBus.Progress?,
    val discovered: Int, val downloaded: Int, val uploading: Int,
    val uploaded: Int, val failed: Int,
    val isAuthorized: Boolean, val isConfigured: Boolean,
    val recentLogs: List<LogEntity>
)
```
Combine flows from `files.observeCount(...)`, `ProgressBus.state`, `log.observe(8)`. Provide `scanNow()`, `uploadNow()`, `authorize()` callbacks calling `PipelineScheduler`/`YouTubeAuthManager`.

### Task 14.3 — Files `ui/files/FileListScreen.kt`
`LazyColumn` over `files.observeAll()`. Each row card: filename, captured time, status chip (color per status), size, progress if active. Row actions:
- **Play** (enabled only when `localPath != null`) → navigate `player/{fileName}`.
- **Retry** (if FAILED) → set status PENDING/ DOWNLOADED appropriately and enqueue.
- **Delete local** (manual) with confirm.

### Task 14.4 — Player `ui/player/PlayerScreen.kt` + `PlayerViewModel.kt` (Spec 4)
Use **Media3 ExoPlayer** inside an `AndroidView(PlayerView)`. Load `localPath`. Handle lifecycle (release on dispose). If file already uploaded+deleted, show "File removed after upload" empty state.

```kotlin
@Composable
fun PlayerScreen(fileName: String, vm: PlayerViewModel = viewModel()) {
    val ctx = LocalContext.current
    val path = vm.localPathFor(fileName)            // suspend/Flow
    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(path) {
        if (path != null) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            player.prepare(); player.playWhenReady = true
        }
        onDispose { player.release() }
    }
    if (path == null) EmptyState("File not available (removed after upload).")
    else AndroidView(factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f/9f))
}
```

### Task 14.5 — Logs `ui/logs/LogConsoleScreen.kt` (Spec 5)
Monospace `LazyColumn` over `log.observe(500)`, newest first, auto-scroll toggle, level filter chips (DEBUG/INFO/WARN/ERROR), color-coded, "Clear logs" + "Copy/Share logs" (export to text). Each line: `HH:mm:ss.SSS [LEVEL] tag: message`.

### Task 14.6 — Config `ui/config/ConfigScreen.kt` + `ConfigViewModel.kt` (Spec 8)
Form fields (all persisted via `ConfigRepository.save`):
- YouTube **Client ID** (required) — masked toggle.
- YouTube **Client Secret** (optional, note PKCE) — masked.
- **Privacy** dropdown: private/unlisted/public.
- **Dashcam gateway** (default `193.168.0.1`, editable for firmware variance).
- **Delete after upload** switch (default ON).
- **Auto-start on Wi-Fi** switch.
- **Max retries** stepper.
- Buttons: **Save**, **Authorize YouTube** (launches `YouTubeAuthManager.buildAuthIntent()` via `rememberLauncherForActivityResult`), **Sign out**, **Test dashcam connection** (pings listing when on AP).
- Inline help card with the Google Cloud setup steps from Task 10.1 and a "Configured ✓ / Not configured ✗" banner.

`ConfigViewModel` validates Client ID non-empty before enabling Authorize. On auth result, call `auth.handleAuthResponse(intent)` and update state.

### Task 14.7 — `ui/MainActivity.kt`
```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ask POST_NOTIFICATIONS on 33+ (accompanist or ActivityResult)
        setContent { DDPAITheme { AppNav() } }
    }
}
```
Request `POST_NOTIFICATIONS` (API 33+) on first launch via Accompanist permissions.

### Task 14.8 — Theme `ui/theme/*`
Material 3 dark color scheme with the palette above; `Typography` with a monospace family for log/console text.

---

## 15. Permissions Runtime Flow
1. On first launch, request `POST_NOTIFICATIONS` (33+). If denied, pipeline still works but no foreground notification UI — log a WARN.
2. No location, no storage permissions required (by design). Document this as a selling point.

---

## 16. End-to-End Test Plan (Manual + Instrumented)

### Task 16.1 — Unit tests
- `Mp4AtomScannerTest`: feed a valid MP4 (assets), a truncated MP4 (moov stripped), a 200-byte HTML file → expect valid/invalid/invalid.
- `DashcamFileListParserTest`: feed sample JSON and HTML listing fixtures → expect correct filename + epoch parsing for `_0060`, `_F`, `_R`.
- `FileRepositoryTest` (Robolectric/in-memory Room): `reconcileListing` twice with same list → second adds 0 (idempotency).

### Task 16.2 — Integration scenarios (document as a checklist)
1. Connect to dashcam AP → DownloadWorker lists, downloads, verifies, marks DOWNLOADED. Re-connect → no re-download.
2. Kill app mid-download → reconnect → resumes via Range, not from zero.
3. Corrupt small file injected → flagged, deleted, reset PENDING, re-downloaded.
4. Connect to home Wi-Fi (authorized) → uploads oldest first, deletes after each success.
5. Kill app mid-upload → reconnect → resumes via session URI offset query.
6. Quota error simulated (403) → upload pauses, retries next cycle, file retained.
7. Play a DOWNLOADED file → ExoPlayer renders. Play an UPLOADED+deleted file → empty state.
8. Config: enter Client ID, authorize, verify token persists across restart.

### Task 16.3 — Robustness checks
- Airplane-mode toggling during each phase.
- Reboot device with queue non-empty → WorkManager + DB resume correctly.
- Verify no double upload by checking YouTube channel for duplicates after re-runs.

---

## 17. Build Order (Strict Sequence for the Implementing LLM)

Implement and compile-check after each numbered group:
1. Project setup + version catalog + Gradle (Tasks 2.x).
2. Manifest + permissions + network security config (Tasks 3.x).
3. Package skeleton (Task 4) — create empty files.
4. Models + Room (Tasks 5.x). Compile.
5. Config storage (Tasks 6.x). Compile.
6. Network detection + binding (Tasks 7.x). Compile.
7. Dashcam client + parser (Tasks 8.x). Compile.
8. Integrity (Tasks 9.x). Compile + unit test scanner.
9. YouTube auth + uploader (Tasks 10.x). Compile.
10. Repositories (Tasks 11.x). Compile.
11. Pipeline workers + scheduler (Tasks 12.x). Compile.
12. App wiring: ServiceLocator, ProgressBus, Notifications, App (Tasks 13.x). Compile.
13. UI screens one by one: Theme → Nav → Config → Dashboard → Files → Player → Logs (Tasks 14.x). Compile after each.
14. Permissions flow (Task 15).
15. Tests (Task 16).

---

## 18. Critical "Do-Not-Hallucinate" Reference Card

| Concern | Exact value / API |
|---|---|
| Dashcam gateway | `193.168.0.1` (configurable) |
| Listing endpoints (try in order) | `/vcam/cmd.cgi?cmd=getFileList`, `?cmd=getfilelist`, `/vcam/cmd.cgi`, `/` |
| Download URL | `http://193.168.0.1/<fileName>` (flat, no subfolders) |
| Filename regex | `(\d{8})(\d{6})_(0060\|F\|R)\.mp4` |
| Segment | 60s, suffix `_0060` |
| Valid size | ≥ 1 MB; corrupt = 58 B–80 KB HTML |
| Required atoms | `ftyp`, `mdat`, `moov` (moov missing ⇒ corrupt) |
| Socket binding | `OkHttpClient.socketFactory(network.socketFactory)` (+ optional `bindProcessToNetwork`) |
| Detection | DHCP gateway from `LinkProperties.routes` default route; no SSID/location |
| YouTube scope | `https://www.googleapis.com/auth/youtube.upload` |
| Resumable init | `POST .../upload/youtube/v3/videos?uploadType=resumable&part=snippet,status` |
| Resume offset | `PUT sessionUri` with `Content-Range: bytes */total` → `308` + `Range` header |
| Upload cost | ~1600 units; default 10k/day quota |
| OAuth | AppAuth installed-app + PKCE; redirect `com.ddpai.uploader:/oauth2redirect` |
| State machine | DISCOVERED→DOWNLOADING→DOWNLOADED→UPLOADING→UPLOADED; errors→PENDING; cap→FAILED |
| Delete trigger | only after upload returns a YouTube video ID |

---

## 19. Known Pitfalls & Required Mitigations
1. **Traffic leaking to mobile data** → always bind via `network.socketFactory`. Verify by logging the local socket's network.
2. **`usesCleartextTraffic`** must be enabled (or scoped via network_security_config) or HTTP to dashcam is blocked on API 28+.
3. **FFmpegKit availability** — Maven hosting changed in 2025. Primary integrity = pure-Kotlin atom scanner; FFprobe optional. Never hard-crash if the native lib is absent.
4. **WorkManager init duplication** — pick auto-init OR `Configuration.Provider`, not both.
5. **Foreground service type** — on API 34+ must pass `FOREGROUND_SERVICE_TYPE_DATA_SYNC` and declare it in the manifest, else `MissingForegroundServiceTypeException`.
6. **OAuth redirect scheme** must exactly match `manifestPlaceholders["appAuthRedirectScheme"]` and the Google Console redirect URI.
7. **YouTube quota** — surface 403/quotaExceeded and pause; don't burn retries.
8. **Resume correctness** — persist `uploadSessionUrl` before sending bytes so a crash mid-PUT is recoverable.
9. **Idempotency** — never use a surrogate auto-id as the file key; the filename IS the key.
10. **Large files in internal storage** — prefer `getExternalFilesDir` for capacity; still no runtime permission needed on API 26+.

---

*End of plan. Implement strictly in the Build Order of Section 17.*
