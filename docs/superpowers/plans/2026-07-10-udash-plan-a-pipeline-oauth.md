# uDash Plan A — Pipeline & OAuth Rectification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing download and YouTube-upload pipeline actually work end-to-end (it currently fails to sync in background and the untested upload path is broken), and slim the app.

**Architecture:** Surgical fixes to the existing MVVM + Room + WorkManager + bound-OkHttp code. Workers self-resolve the dashcam network instead of depending on a live callback, foreground promotion is made failure-tolerant, OAuth learns to send a client secret, uploads are pinned to real internet Wi-Fi, and quota is detected properly. Pure decision logic is extracted into small testable units; Android glue is verified by build + manual checklist. No Room schema change in this plan (schema stays version 1).

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Room, WorkManager, OkHttp, AppAuth, Media3.

## Global Constraints

- Language Kotlin only. minSdk 26, targetSdk 34, compileSdk 34. JVM target 17.
- Package `com.ddpai.uploader`. OAuth redirect `com.ddpai.uploader:/oauth2redirect`. YouTube scope `https://www.googleapis.com/auth/youtube.upload`.
- Dashcam gateway default `193.168.0.1` (user-editable in Config). Dashcam is plain HTTP; all Google traffic is HTTPS.
- Filename regex (authoritative): `(\d{8})(\d{6})_(0060|F|R)\.mp4`.
- Integrity gate: file size ≥ 1 MB (1,048,576 bytes) AND contains `ftyp` + `mdat` + `moov` atoms. Pure-Kotlin `Mp4AtomScanner` is the sole gate (no FFprobe).
- Quota reset zone: `America/Los_Angeles` (YouTube quota resets midnight Pacific).
- Manual DI via `ServiceLocator`; no Hilt. Do not add new heavyweight dependencies.
- Build/test gate for every task: `./gradlew :app:assembleDebug :app:testDebugUnitTest`.
- Dependencies to REMOVE (Task 1, confirmed unused): `dev.ffmpegkit-maintained:ffmpeg-kit-free-81`, `com.google.api-client:google-api-client-android`, `com.google.apis:google-api-services-youtube`, `com.google.http-client:google-http-client-gson`, `io.coil-kt:coil-compose`, `com.google.accompanist:accompanist-permissions`, `androidx.datastore:datastore-preferences`.
- Commit after every task with the message shown in its final step.

> **Build environment note:** if `gradlew` fails with `java.io.IOException: Unable to establish loopback connection`, the JDK's Unix-domain-socket temp path is too long. Set `_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\gtmp` (create `C:\gtmp` first). This is a local shell quirk, not a code issue.

---

### Task 1: Remove unused dependencies and the FFprobe branch

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts:44-79`
- Modify: `app/src/main/java/com/ddpai/uploader/integrity/IntegrityVerifier.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `IntegrityVerifier.verify(file: File): IntegrityVerifier.Verdict` (unchanged signature; internals now scanner-only).

- [ ] **Step 1: Remove library entries from the version catalog**

In `gradle/libs.versions.toml`, delete these lines from `[versions]`:
```toml
ffmpegKit = "8.1.7"
googleApiClient = "2.7.0"
youtubeApi = "v3-rev20240514-2.0.0"
googleHttpGson = "1.45.0"
datastore = "1.1.1"
coil = "2.7.0"
accompanistPermissions = "0.36.0"
```
And delete these lines from `[libraries]`:
```toml
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
google-api-client-android = { group = "com.google.api-client", name = "google-api-client-android", version.ref = "googleApiClient" }
google-youtube = { group = "com.google.apis", name = "google-api-services-youtube", version.ref = "youtubeApi" }
google-http-gson = { group = "com.google.http-client", name = "google-http-client-gson", version.ref = "googleHttpGson" }
ffmpeg-kit = { group = "dev.ffmpegkit-maintained", name = "ffmpeg-kit-free-81", version.ref = "ffmpegKit" }
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }
accompanist-permissions = { group = "com.google.accompanist", name = "accompanist-permissions", version.ref = "accompanistPermissions" }
```

- [ ] **Step 2: Remove the dependency declarations from the app module**

In `app/build.gradle.kts`, delete exactly these seven lines from the `dependencies { }` block (keep `implementation(libs.appauth)` and every other line):
```kotlin
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.google.api.client.android)
    implementation(libs.google.youtube)
    implementation(libs.google.http.gson)
    implementation(libs.ffmpeg.kit)
    implementation(libs.coil.compose)
    implementation(libs.accompanist.permissions)
```

- [ ] **Step 3: Replace `IntegrityVerifier.kt` with a scanner-only version**

```kotlin
package com.ddpai.uploader.integrity

import com.ddpai.uploader.data.repo.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class IntegrityVerifier(private val logger: LogRepository) {
    data class Verdict(val valid: Boolean, val reason: String)

    suspend fun verify(file: File): Verdict = withContext(Dispatchers.IO) {
        val scan = Mp4AtomScanner.scan(file)
        when {
            !scan.sizeOk -> Verdict(false, "size<1MB (${file.length()} bytes) — likely HTML error body")
            !scan.hasFtyp -> Verdict(false, "missing ftyp atom")
            !scan.hasMdat -> Verdict(false, "missing mdat atom")
            !scan.hasMoov -> Verdict(false, "missing moov atom — aborted/interrupted write")
            else -> Verdict(true, "ok")
        }.also {
            if (!it.valid) logger.w("IntegrityVerifier", "${file.name}: ${it.reason}")
        }
    }
}
```

- [ ] **Step 4: Build to verify nothing else referenced the removed libraries**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`. `Mp4AtomScannerTest` and the other two existing tests still pass. APK no longer packages `libavcodec.so`/`libffmpegkit.so` (the earlier `stripDebugDebugSymbols` warning about those `.so` files disappears).

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/ddpai/uploader/integrity/IntegrityVerifier.kt
git commit -m "chore: drop FFmpegKit and 6 unused libraries; scanner is sole integrity gate"
```

---

### Task 2: Fix the cleartext-traffic gateway trap

**Files:**
- Delete: `app/src/main/res/xml/network_security_config.xml`
- Modify: `app/src/main/AndroidManifest.xml:14-21`

**Interfaces:**
- Consumes: nothing.
- Produces: cleartext HTTP permitted to any host (only the local camera is ever contacted over HTTP; Google is HTTPS).

- [ ] **Step 1: Delete the network security config**

```bash
git rm app/src/main/res/xml/network_security_config.xml
```

- [ ] **Step 2: Update the `<application>` tag**

In `app/src/main/AndroidManifest.xml`, replace the line:
```xml
        android:networkSecurityConfig="@xml/network_security_config"
```
with:
```xml
        android:usesCleartextTraffic="true"
```
(The `<application>` opening tag keeps every other attribute — `android:name`, `allowBackup`, `icon`, `label`, `theme`, `tools:targetApi`.)

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual verification note (record in commit, no device needed yet)**

Rationale captured for reviewers: a user editing the gateway in Config to a non-`193.168.0.1` value no longer silently loses all camera HTTP, because cleartext is no longer whitelisted to a single hard-coded IP.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/network_security_config.xml
git commit -m "fix: allow cleartext to any host so editable gateway keeps working"
```

---

### Task 3: Extract shared gateway extraction and a standalone dashcam network resolver

**Files:**
- Create: `app/src/main/java/com/ddpai/uploader/network/NetworkGateway.kt`
- Create: `app/src/main/java/com/ddpai/uploader/network/DashcamNetworkResolver.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/network/NetworkMonitor.kt:56-73`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `object NetworkGateway { fun extract(cm: ConnectivityManager, network: Network, context: Context? = null): String? }` — tries, in order: (1) LinkProperties default-route gateway; (2) API 30+ `LinkProperties.dhcpServerAddress` (per-network — for the dashcam the DHCP server IP *is* the gateway `193.168.0.1`); (3) the original legacy `WifiManager.dhcpInfo` gateway (Wi-Fi-specific, independent of the system default network, so it works in-vehicle when cellular is the active network — needs a `context`).
  - `class DashcamNetworkResolver(cm: ConnectivityManager, context: Context) { fun resolve(configuredGateway: String): Network? }`

- [ ] **Step 1: Create `NetworkGateway.kt` (route → dhcpServerAddress → legacy DhcpInfo)**

```kotlin
package com.ddpai.uploader.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.util.Locale

/**
 * Extracts the IPv4 gateway (dotted string) for [network]. Restores the original NetworkMonitor's
 * dual detection and adds a per-network improvement:
 *  1. Default-route gateway from LinkProperties (present on most APs, including no-internet ones).
 *  2. (API 30+) LinkProperties.dhcpServerAddress — for the dashcam the DHCP server IP *is* the
 *     gateway (193.168.0.1). Read from THIS network's own link, so it is correct even when the
 *     dashcam Wi-Fi is not the system default (e.g. cellular active in a vehicle) and can never be
 *     misattributed while iterating multiple networks.
 *  3. Legacy WifiManager.dhcpInfo gateway (the original fallback). This is Wi-Fi-specific — it
 *     reflects the phone's Wi-Fi connection regardless of which network is the system default — so
 *     it also works in-vehicle. Requires a [context].
 */
object NetworkGateway {
    fun extract(cm: ConnectivityManager, network: Network, context: Context? = null): String? {
        val lp = cm.getLinkProperties(network)
        lp?.routes?.forEach { r ->
            val gw = r.gateway
            if (r.isDefaultRoute && gw is Inet4Address) {
                return gw.hostAddress
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dhcpServer = lp?.dhcpServerAddress
            if (dhcpServer is Inet4Address && !dhcpServer.isAnyLocalAddress) {
                return dhcpServer.hostAddress
            }
        }
        if (context != null) {
            try {
                val wifi = context.getSystemService(WifiManager::class.java)
                @Suppress("DEPRECATION")
                val g = wifi?.dhcpInfo?.gateway ?: return null
                if (g != 0) {
                    return String.format(
                        Locale.US, "%d.%d.%d.%d",
                        g and 0xff, g shr 8 and 0xff, g shr 16 and 0xff, g shr 24 and 0xff
                    )
                }
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }
}
```

- [ ] **Step 2: Create `DashcamNetworkResolver.kt`**

```kotlin
package com.ddpai.uploader.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Finds the currently-connected Wi-Fi [Network] whose IPv4 gateway matches the dashcam gateway,
 * without relying on any previously-registered callback. Safe to call from a Worker after process
 * restart. Passes [context] to NetworkGateway so the DhcpInfo fallback covers APs that expose no
 * default route (the fallback self-limits to the active network).
 */
class DashcamNetworkResolver(
    private val cm: ConnectivityManager,
    private val context: Context
) {
    fun resolve(configuredGateway: String): Network? {
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (NetworkGateway.extract(cm, n, context) == configuredGateway) return n
        }
        return null
    }
}
```

- [ ] **Step 3: Make `NetworkMonitor` reuse `NetworkGateway`**

In `app/src/main/java/com/ddpai/uploader/network/NetworkMonitor.kt`, replace the entire `extractGateway` function (lines 56-73) and its usage. Change the `evaluate` call site:
```kotlin
        val gatewayIp = extractGateway(lp)
```
to (passing `context` so the DhcpInfo fallback is preserved for the active network):
```kotlin
        val gatewayIp = NetworkGateway.extract(cm, network, context)
```
Then delete the private `extractGateway(lp: LinkProperties?)` function entirely. Remove imports only if the compiler flags them unused: `android.net.wifi.WifiManager` becomes unused here (its logic moved to `NetworkGateway`) and should be removed; `android.net.LinkProperties` is still referenced by `onLinkPropertiesChanged`'s parameter, so keep it. If `val lp = cm.getLinkProperties(network)` in `evaluate` is now unused, delete it.

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. `NetworkMonitor` behaves identically (same gateway comparison), now sharing one implementation.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/network/
git commit -m "refactor: shared gateway extraction + standalone DashcamNetworkResolver"
```

---

### Task 4: Add pure retry policy and a listing filter, with tests

**Files:**
- Create: `app/src/main/java/com/ddpai/uploader/pipeline/RetryPolicy.kt`
- Create: `app/src/main/java/com/ddpai/uploader/dashcam/ListingFilter.kt`
- Test: `app/src/test/java/com/ddpai/uploader/pipeline/RetryPolicyTest.kt`
- Test: `app/src/test/java/com/ddpai/uploader/dashcam/ListingFilterTest.kt`

**Interfaces:**
- Consumes: `com.ddpai.uploader.data.model.DashcamFile(fileName, sizeBytes, capturedAtEpoch)`.
- Produces:
  - `object RetryPolicy { fun shouldFail(retryCount: Int, maxRetries: Int): Boolean }`
  - `object ListingFilter { fun excludeNewest(files: List<DashcamFile>): List<DashcamFile> }`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/ddpai/uploader/pipeline/RetryPolicyTest.kt`:
```kotlin
package com.ddpai.uploader.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test fun belowCapDoesNotFail() = assertFalse(RetryPolicy.shouldFail(retryCount = 4, maxRetries = 5))
    @Test fun atCapFails() = assertTrue(RetryPolicy.shouldFail(retryCount = 5, maxRetries = 5))
    @Test fun aboveCapFails() = assertTrue(RetryPolicy.shouldFail(retryCount = 7, maxRetries = 5))
}
```

`app/src/test/java/com/ddpai/uploader/dashcam/ListingFilterTest.kt`:
```kotlin
package com.ddpai.uploader.dashcam

import com.ddpai.uploader.data.model.DashcamFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingFilterTest {
    @Test fun excludesSingleNewestByCaptureTime() {
        val files = listOf(
            DashcamFile("20260626180800_0060.mp4", 0L, 1000L),
            DashcamFile("20260626181000_0060.mp4", 0L, 3000L), // newest
            DashcamFile("20260626180900_0060.mp4", 0L, 2000L),
        )
        val kept = ListingFilter.excludeNewest(files).map { it.fileName }
        assertEquals(2, kept.size)
        assertTrue("20260626181000_0060.mp4" !in kept)
    }

    @Test fun emptyListReturnsEmpty() = assertTrue(ListingFilter.excludeNewest(emptyList()).isEmpty())

    @Test fun singleElementIsExcluded() =
        assertTrue(ListingFilter.excludeNewest(listOf(DashcamFile("a", 0L, 1L))).isEmpty())

    @Test fun excludesNewestPerStreamOnDualCamera() {
        // Front + rear are cut simultaneously → identical capture time; BOTH are in-progress.
        val files = listOf(
            DashcamFile("20260626180900_F.mp4", 0L, 2000L),
            DashcamFile("20260626180900_R.mp4", 0L, 2000L),
            DashcamFile("20260626180800_F.mp4", 0L, 1000L),
            DashcamFile("20260626180800_R.mp4", 0L, 1000L),
        )
        val kept = ListingFilter.excludeNewest(files).map { it.fileName }.toSet()
        assertEquals(setOf("20260626180800_F.mp4", "20260626180800_R.mp4"), kept)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ddpai.uploader.pipeline.RetryPolicyTest" --tests "com.ddpai.uploader.dashcam.ListingFilterTest"`
Expected: FAIL — unresolved references `RetryPolicy`, `ListingFilter`.

- [ ] **Step 3: Write the implementations**

`app/src/main/java/com/ddpai/uploader/pipeline/RetryPolicy.kt`:
```kotlin
package com.ddpai.uploader.pipeline

/** Decides whether a file that just consumed a retry has exhausted its budget. */
object RetryPolicy {
    fun shouldFail(retryCount: Int, maxRetries: Int): Boolean = retryCount >= maxRetries
}
```

`app/src/main/java/com/ddpai/uploader/dashcam/ListingFilter.kt`:
```kotlin
package com.ddpai.uploader.dashcam

import com.ddpai.uploader.data.model.DashcamFile

/**
 * The dashcam is always writing the newest segment of each active stream (front `_F`, rear `_R`, or
 * combined `_0060`); those in-progress files have no moov atom yet. Drop the newest file of EACH
 * stream from a listing so we do not repeatedly fail-and-retry an in-progress recording — each
 * reappears, complete, on the next scan. A dual-camera dashcam cuts a front and a rear segment with
 * the same capture time, so both must be excluded, not just one.
 */
object ListingFilter {
    private val STREAM_RE = Regex("""_(0060|F|R)\.mp4$""", RegexOption.IGNORE_CASE)

    fun excludeNewest(files: List<DashcamFile>): List<DashcamFile> {
        if (files.isEmpty()) return files
        val newest = files
            .groupBy { streamKeyOf(it.fileName) }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.capturedAtEpoch }?.fileName }
            .toSet()
        return files.filter { it.fileName !in newest }
    }

    private fun streamKeyOf(fileName: String): String =
        STREAM_RE.find(fileName)?.groupValues?.get(1)?.uppercase() ?: fileName
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ddpai.uploader.pipeline.RetryPolicyTest" --tests "com.ddpai.uploader.dashcam.ListingFilterTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/pipeline/RetryPolicy.kt app/src/main/java/com/ddpai/uploader/dashcam/ListingFilter.kt app/src/test/java/com/ddpai/uploader/pipeline/RetryPolicyTest.kt app/src/test/java/com/ddpai/uploader/dashcam/ListingFilterTest.kt
git commit -m "feat: pure retry policy + newest-file listing filter with tests"
```

---

### Task 5: Add atomic retry DAO method and repository wrapper

**Files:**
- Modify: `app/src/main/java/com/ddpai/uploader/data/db/VideoFileDao.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/data/repo/FileRepository.kt`

**Interfaces:**
- Consumes: existing `VideoFileEntity`, `FileStatus`.
- Produces:
  - `VideoFileDao.recordRetry(name: String, retryStatus: String, error: String, ts: Long)` — atomic `retryCount = retryCount + 1` + status/error update.
  - `VideoFileDao.setDownloadProgress(name: String, downloaded: Long, size: Long, ts: Long)`.
  - `FileRepository.recordRetry(name: String, retryStatus: FileStatus, error: String)`.
  - `FileRepository.setDownloadProgress(name: String, downloaded: Long, size: Long)`.

- [ ] **Step 1: Add DAO methods**

In `app/src/main/java/com/ddpai/uploader/data/db/VideoFileDao.kt`, add inside the interface:
```kotlin
    @Query(
        "UPDATE video_files SET retryCount = retryCount + 1, status = :retryStatus, " +
            "errorMessage = :error, updatedAtEpoch = :ts WHERE fileName = :name"
    )
    suspend fun recordRetry(name: String, retryStatus: String, error: String, ts: Long = System.currentTimeMillis())

    @Query(
        "UPDATE video_files SET downloadedBytes = :downloaded, sizeBytes = :size, " +
            "updatedAtEpoch = :ts WHERE fileName = :name"
    )
    suspend fun setDownloadProgress(name: String, downloaded: Long, size: Long, ts: Long = System.currentTimeMillis())

    /**
     * Reset rows stuck in a transient state after a process kill (no catch block ran). Called once
     * at worker start; safe because each pipeline worker is unique (KEEP), so no live worker holds
     * a row in [fromStatus]. Returns the number of rows reclaimed.
     */
    @Query("UPDATE video_files SET status = :toStatus, updatedAtEpoch = :ts WHERE status = :fromStatus")
    suspend fun reclaimOrphans(fromStatus: String, toStatus: String, ts: Long = System.currentTimeMillis()): Int
```

- [ ] **Step 2: Add repository wrappers**

In `app/src/main/java/com/ddpai/uploader/data/repo/FileRepository.kt`, add methods (near `setStatus`):
```kotlin
    suspend fun recordRetry(name: String, retryStatus: FileStatus, error: String) =
        dao.recordRetry(name, retryStatus.name, error)

    suspend fun setDownloadProgress(name: String, downloaded: Long, size: Long) =
        dao.setDownloadProgress(name, downloaded, size)

    suspend fun reclaimOrphans(from: FileStatus, to: FileStatus) =
        dao.reclaimOrphans(from.name, to.name)
```

- [ ] **Step 3: Build (Room compiles the new queries)**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Room KSP validates both SQL statements against the `video_files` schema.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/data/db/VideoFileDao.kt app/src/main/java/com/ddpai/uploader/data/repo/FileRepository.kt
git commit -m "feat: atomic retry-increment and download-progress DAO methods"
```

---

### Task 6: Rebuild `DownloadWorker` — foreground guard, self-resolved network, newest-skip, retry accounting, live progress

**Files:**
- Modify: `app/src/main/java/com/ddpai/uploader/pipeline/DownloadWorker.kt`

**Interfaces:**
- Consumes: `DashcamNetworkResolver`, `NetworkGateway`, `ListingFilter`, `RetryPolicy`, `FileRepository.recordRetry/setDownloadProgress`, `IntegrityVerifier`.
- Produces: a `DownloadWorker` that no longer silently no-ops when the callback network is absent.

- [ ] **Step 1: Replace `DownloadWorker.kt` in full**

```kotlin
package com.ddpai.uploader.pipeline

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ddpai.uploader.dashcam.DashcamClient
import com.ddpai.uploader.dashcam.ListingFilter
import com.ddpai.uploader.data.model.FileStatus
import com.ddpai.uploader.di.ServiceLocator
import com.ddpai.uploader.integrity.IntegrityVerifier
import com.ddpai.uploader.network.DashcamNetworkResolver

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val sl = ServiceLocator.get(applicationContext)

    override suspend fun getForegroundInfo() =
        sl.notifications.downloadForegroundInfo(applicationContext, "Scanning dashcam…")

    override suspend fun doWork(): Result {
        promoteToForeground()

        val gateway = sl.config.config.value.dashcamGateway
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
        val resolver = DashcamNetworkResolver(cm, applicationContext)
        val network = resolver.resolve(gateway) ?: sl.currentDashcamNetwork
        if (network == null) {
            sl.log.w("DownloadWorker", "Dashcam network not resolvable (attempt $runAttemptCount)")
            return if (runAttemptCount >= 5) Result.success() else Result.retry()
        }
        sl.currentDashcamNetwork = network

        val client = DashcamClient(network, gateway, sl.log)
        val verifier = IntegrityVerifier(sl.log)

        val remote = try {
            client.listFiles()
        } catch (e: Exception) {
            sl.log.e("DownloadWorker", "Listing failed: ${e.message}")
            return Result.retry()
        }
        val downloadable = ListingFilter.excludeNewest(remote)
        val added = sl.files.reconcileListing(downloadable, gateway)
        sl.log.i(
            "DownloadWorker",
            "Listing: ${remote.size} total, ${downloadable.size} downloadable, +$added new"
        )

        val maxRetries = sl.config.config.value.maxRetries
        // Reclaim rows a previous run left mid-download when the process was killed (no catch ran).
        val reclaimed = sl.files.reclaimOrphans(FileStatus.DOWNLOADING, FileStatus.PENDING)
        if (reclaimed > 0) sl.log.w("DownloadWorker", "Reclaimed $reclaimed orphaned DOWNLOADING → PENDING")
        val pending = sl.files.pendingDownloads()
        for (item in pending) {
            if (resolver.resolve(gateway) == null) {
                sl.log.w("DownloadWorker", "Lost dashcam AP; stopping cycle")
                break
            }
            val target = sl.files.fileFor(item.fileName)
            val existing = if (target.exists()) target.length() else 0L
            try {
                sl.files.setStatus(item.fileName, FileStatus.DOWNLOADING)
                sl.log.i("DownloadWorker", "Downloading ${item.fileName} (resume@$existing)", item.fileName)
                var lastWrite = 0L
                client.download(item.fileName, target, existing) { dl, total ->
                    sl.progress.updateDownload(item.fileName, dl, total)
                    val now = System.currentTimeMillis()
                    if (now - lastWrite >= 1000L) {
                        lastWrite = now
                        sl.io.launch { sl.files.setDownloadProgress(item.fileName, dl, maxOf(total, 0L)) }
                    }
                }
                val verdict = verifier.verify(target)
                if (!verdict.valid) {
                    sl.files.markCorruptAndReset(item.fileName, verdict.reason)
                    val cur = sl.files.get(item.fileName)
                    if (cur != null && RetryPolicy.shouldFail(cur.retryCount, maxRetries)) {
                        sl.files.setStatus(item.fileName, FileStatus.FAILED)
                        sl.log.w("DownloadWorker", "${item.fileName} repeatedly corrupt → FAILED", item.fileName)
                    }
                    continue
                }
                sl.files.update(
                    item.copy(
                        status = FileStatus.DOWNLOADED.name,
                        localPath = target.absolutePath,
                        sizeBytes = target.length(),
                        downloadedBytes = target.length(),
                        errorMessage = null
                    )
                )
                sl.log.i("DownloadWorker", "DOWNLOADED ${item.fileName} (${target.length()} bytes)", item.fileName)
            } catch (e: Exception) {
                sl.log.e("DownloadWorker", "Download error ${item.fileName}: ${e.message}", item.fileName)
                sl.files.recordRetry(item.fileName, FileStatus.PENDING, e.message ?: "download error")
                val cur = sl.files.get(item.fileName)
                if (cur != null && RetryPolicy.shouldFail(cur.retryCount, maxRetries)) {
                    sl.files.setStatus(item.fileName, FileStatus.FAILED)
                    sl.log.w("DownloadWorker", "${item.fileName} exhausted retries → FAILED", item.fileName)
                }
            }
        }
        sl.progress.clear()
        sl.log.i("DownloadWorker", "Download cycle complete")
        return Result.success()
    }

    private suspend fun promoteToForeground() {
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            sl.log.w("DownloadWorker", "Foreground promotion refused (${e.message}); running in background")
        }
    }
}
```

> This task consumes `sl.io` (a shared IO `CoroutineScope`) added in Task 7. Implement Task 7 before building; they land in one commit sequence. If executing strictly in order, temporarily replace `sl.io.launch { ... }` with a direct `sl.files.setDownloadProgress(...)` call inside the existing `withContext(Dispatchers.IO)` of the download callback — but the callback is not suspend, so the shared scope is the correct solution. Do Task 7 first (it is a one-line ServiceLocator addition), then return here.

- [ ] **Step 2: (Blocked) — implement Task 7 first, then build**

Run: `./gradlew :app:assembleDebug` (after Task 7)
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Manual verification checklist (record in commit body)**

- On an Android 12+ device, swipe the app away, connect phone to the dashcam Wi-Fi → download work runs (visible in Logcat / Logs screen) instead of throwing `ForegroundServiceStartNotAllowedException`.
- Re-connect after a full sync → `+0 new` (idempotent, no re-download).
- Files screen progress bars advance during a download.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/pipeline/DownloadWorker.kt
git commit -m "fix: DownloadWorker self-resolves AP, tolerates fg refusal, skips newest, counts retries"
```

---

### Task 7: Add a shared IO scope to ServiceLocator

**Files:**
- Modify: `app/src/main/java/com/ddpai/uploader/di/ServiceLocator.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `ServiceLocator.io: CoroutineScope` (SupervisorJob + Dispatchers.IO), for fire-and-forget DB writes from non-suspend callbacks.

- [ ] **Step 1: Add the scope**

In `app/src/main/java/com/ddpai/uploader/di/ServiceLocator.kt`, add near the other `val` declarations (AFTER the `log` field, which the handler references). The `CoroutineExceptionHandler` is mandatory: `launch` is fire-and-forget, and without a handler an uncaught exception (e.g. a Room/SQLite error) from a background write would crash the whole process — the opposite of fault tolerance.
```kotlin
    private val ioErrors = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        log.e("ServiceLocator", "Background IO task failed: ${e.message}")
    }
    val io = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO + ioErrors
    )
```

- [ ] **Step 2: Build with Task 6's worker**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit (fold into Task 6's commit if executed together)**

```bash
git add app/src/main/java/com/ddpai/uploader/di/ServiceLocator.kt
git commit -m "feat: shared IO scope on ServiceLocator for throttled progress writes"
```

---

### Task 8: OAuth client-secret support and re-auth flag

**Files:**
- Modify: `app/src/main/java/com/ddpai/uploader/youtube/YouTubeAuthManager.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/data/config/ConfigRepository.kt`

**Interfaces:**
- Consumes: `ConfigRepository.config.value.youtubeClientSecret`.
- Produces:
  - `YouTubeAuthManager` sends `ClientSecretPost` when a secret is configured (both token exchange and refresh).
  - `ConfigRepository.runtimeState: StateFlow<ConfigRepository.RuntimeState>`, `setNeedsReauth(Boolean)`, `getNeedsReauth(): Boolean`, `setQuotaPausedUntil(Long)`, `getQuotaPausedUntil(): Long`.

- [ ] **Step 1: Add runtime-state accessors to `ConfigRepository`**

In `app/src/main/java/com/ddpai/uploader/data/config/ConfigRepository.kt`, add:
```kotlin
    data class RuntimeState(val quotaPausedUntil: Long = 0L, val needsReauth: Boolean = false)

    private val _runtime = kotlinx.coroutines.flow.MutableStateFlow(loadRuntime())
    val runtimeState: kotlinx.coroutines.flow.StateFlow<RuntimeState> =
        _runtime.asStateFlow()

    private fun loadRuntime() = RuntimeState(
        quotaPausedUntil = securePrefs.getLong("quotaPausedUntil", 0L),
        needsReauth = securePrefs.getBoolean("needsReauth", false)
    )

    fun setQuotaPausedUntil(ts: Long) {
        securePrefs.edit().putLong("quotaPausedUntil", ts).apply()
        _runtime.value = _runtime.value.copy(quotaPausedUntil = ts)
    }

    fun getQuotaPausedUntil(): Long = _runtime.value.quotaPausedUntil

    fun setNeedsReauth(v: Boolean) {
        securePrefs.edit().putBoolean("needsReauth", v).apply()
        _runtime.value = _runtime.value.copy(needsReauth = v)
    }

    fun getNeedsReauth(): Boolean = _runtime.value.needsReauth
```
(`asStateFlow` is already imported in this file.)

- [ ] **Step 2: Send the client secret and flag re-auth failures in `YouTubeAuthManager`**

This file already imports `net.openid.appauth.*`, so `ClientSecretPost`, `AuthorizationService.TokenResponseCallback`, and `AuthState.AuthStateAction` need no new imports.

In `handleAuthResponse`, replace the token-exchange block. Change:
```kotlin
        val tokenReq = resp.createTokenExchangeRequest()
        authService.performTokenRequest(tokenReq) { tokenResp, tokenEx ->
            if (tokenResp != null) {
                val authState = AuthState(resp, tokenResp, tokenEx)
                configRepo.saveAuthState(authState.jsonSerializeString())
                cont.resume(true)
            } else {
                cont.resume(false)
            }
        }
```
to:
```kotlin
        val tokenReq = resp.createTokenExchangeRequest()
        val secret = configRepo.config.value.youtubeClientSecret
        // Declared as the SAM interface type (NOT a function-typed val) so it can be passed to
        // both Java overloads of performTokenRequest — Kotlin SAM conversion only applies here.
        val onToken = AuthorizationService.TokenResponseCallback { tokenResp, tokenEx ->
            if (tokenResp != null) {
                val authState = AuthState(resp, tokenResp, tokenEx)
                configRepo.saveAuthState(authState.jsonSerializeString())
                configRepo.setNeedsReauth(false)
                cont.resume(true)
            } else {
                cont.resume(false)
            }
        }
        if (secret.isNotBlank()) {
            authService.performTokenRequest(tokenReq, ClientSecretPost(secret), onToken)
        } else {
            authService.performTokenRequest(tokenReq, onToken)
        }
```

Also update `buildAuthIntent` to request **offline access**, so Google issues a **refresh token** (without it, the access token expires in ~1h and the app would demand full interactive re-auth every hour — unusable for a background uploader). Change the request builder from:
```kotlin
        val req = AuthorizationRequest.Builder(
            serviceConfig, clientId, ResponseTypeValues.CODE, redirectUri
        ).setScope(scope).build() // AppAuth adds PKCE automatically
```
to:
```kotlin
        val req = AuthorizationRequest.Builder(
            serviceConfig, clientId, ResponseTypeValues.CODE, redirectUri
        )
            .setScope(scope)
            // access_type=offline → refresh token; prompt=consent → ensure one is (re)issued on re-auth.
            .setAdditionalParameters(mapOf("access_type" to "offline", "prompt" to "consent"))
            .build() // AppAuth adds PKCE automatically
```

Then update `freshAccessToken` to send the secret and flag re-auth on failure. Replace:
```kotlin
        val state = AuthState.jsonDeserialize(json)
        state.performActionWithFreshTokens(authService) { token, _, _ ->
            if (token != null) {
                configRepo.saveAuthState(state.jsonSerializeString())
                cont.resume(token)
            } else {
                cont.resume(null)
            }
        }
```
with:
```kotlin
        val state = AuthState.jsonDeserialize(json)
        val secret = configRepo.config.value.youtubeClientSecret
        val action = net.openid.appauth.AuthState.AuthStateAction { token, _, ex ->
            if (token != null) {
                configRepo.saveAuthState(state.jsonSerializeString())
                configRepo.setNeedsReauth(false)   // a successful refresh means the session is fine
                cont.resume(token)
            } else {
                // Genuine auth failure — token revoked/invalid_grant (TYPE_OAUTH_TOKEN_ERROR) OR no
                // refresh token available (TYPE_OAUTH_AUTHORIZATION_ERROR, synthesized locally) — needs
                // re-auth. Transient network/IO/JSON failures are TYPE_GENERAL_ERROR and must NOT latch.
                if (ex != null && ex.type != net.openid.appauth.AuthorizationException.TYPE_GENERAL_ERROR) {
                    configRepo.setNeedsReauth(true)
                }
                cont.resume(null)
            }
        }
        if (secret.isNotBlank()) {
            state.performActionWithFreshTokens(
                authService,
                net.openid.appauth.ClientSecretPost(secret),
                action
            )
        } else {
            state.performActionWithFreshTokens(authService, action)
        }
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/youtube/YouTubeAuthManager.kt app/src/main/java/com/ddpai/uploader/data/config/ConfigRepository.kt
git commit -m "fix: send OAuth client secret when configured; track re-auth + quota runtime state"
```

---

### Task 9: Quota detection and reset-clock (pure, tested)

**Files:**
- Create: `app/src/main/java/com/ddpai/uploader/youtube/QuotaError.kt`
- Create: `app/src/main/java/com/ddpai/uploader/youtube/QuotaClock.kt`
- Test: `app/src/test/java/com/ddpai/uploader/youtube/QuotaErrorTest.kt`
- Test: `app/src/test/java/com/ddpai/uploader/youtube/QuotaClockTest.kt`

**Interfaces:**
- Produces:
  - `object QuotaError { fun isQuota(code: Int, body: String): Boolean }`
  - `object QuotaClock { fun nextResetMillis(nowMillis: Long, zone: ZoneId = ZoneId.of("America/Los_Angeles")): Long }`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/ddpai/uploader/youtube/QuotaErrorTest.kt`:
```kotlin
package com.ddpai.uploader.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaErrorTest {
    @Test fun detectsQuotaExceededReason() {
        val body = """{"error":{"code":403,"errors":[{"reason":"quotaExceeded"}]}}"""
        assertTrue(QuotaError.isQuota(403, body))
    }

    @Test fun detectsUploadLimitExceeded() {
        val body = """{"error":{"errors":[{"reason":"uploadLimitExceeded"}]}}"""
        assertTrue(QuotaError.isQuota(403, body))
    }

    @Test fun plainForbiddenIsNotQuota() {
        val body = """{"error":{"code":403,"errors":[{"reason":"forbidden"}]}}"""
        assertFalse(QuotaError.isQuota(403, body))
    }

    @Test fun non403IsNotQuota() =
        assertFalse(QuotaError.isQuota(500, """{"error":{"errors":[{"reason":"quotaExceeded"}]}}"""))
}
```

`app/src/test/java/com/ddpai/uploader/youtube/QuotaClockTest.kt`:
```kotlin
package com.ddpai.uploader.youtube

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class QuotaClockTest {
    private val pt = ZoneId.of("America/Los_Angeles")

    @Test fun resetIsNextMidnightPacificAndInFuture() {
        val now = ZonedDateTime.of(2026, 7, 10, 15, 30, 0, 0, pt).toInstant().toEpochMilli()
        val reset = QuotaClock.nextResetMillis(now, pt)
        val resetZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(reset), pt)
        assertTrue(reset > now)
        assertTrue(resetZdt.hour == 0 && resetZdt.minute == 0)
        assertTrue(resetZdt.dayOfMonth == 11)
    }

    @Test fun justBeforeMidnightRollsToNextDay() {
        val now = ZonedDateTime.of(2026, 7, 10, 23, 59, 0, 0, pt).toInstant().toEpochMilli()
        val reset = QuotaClock.nextResetMillis(now, pt)
        assertTrue(reset > now)
        assertTrue(reset - now <= 60_000L + 1000L)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ddpai.uploader.youtube.QuotaErrorTest" --tests "com.ddpai.uploader.youtube.QuotaClockTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write the implementations**

`app/src/main/java/com/ddpai/uploader/youtube/QuotaError.kt`:
```kotlin
package com.ddpai.uploader.youtube

/** Recognises YouTube Data API daily-quota / upload-limit errors from a 403 response body. */
object QuotaError {
    private val REASONS = listOf("quotaExceeded", "uploadLimitExceeded", "dailyLimitExceeded")

    fun isQuota(code: Int, body: String): Boolean =
        code == 403 && REASONS.any { body.contains(it, ignoreCase = true) }
}
```

`app/src/main/java/com/ddpai/uploader/youtube/QuotaClock.kt`:
```kotlin
package com.ddpai.uploader.youtube

import java.time.Instant
import java.time.ZoneId

/** Computes the next midnight in the given zone (YouTube quota resets at midnight Pacific). */
object QuotaClock {
    fun nextResetMillis(nowMillis: Long, zone: ZoneId = ZoneId.of("America/Los_Angeles")): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
        return nextMidnight.toInstant().toEpochMilli()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ddpai.uploader.youtube.QuotaErrorTest" --tests "com.ddpai.uploader.youtube.QuotaClockTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/youtube/QuotaError.kt app/src/main/java/com/ddpai/uploader/youtube/QuotaClock.kt app/src/test/java/com/ddpai/uploader/youtube/QuotaErrorTest.kt app/src/test/java/com/ddpai/uploader/youtube/QuotaClockTest.kt
git commit -m "feat: pure quota-error detection + Pacific reset clock with tests"
```

---

### Task 10: Resumable-upload parser (pure, tested) and uploader refactor

**Files:**
- Create: `app/src/main/java/com/ddpai/uploader/youtube/ResumeParser.kt`
- Create: `app/src/main/java/com/ddpai/uploader/youtube/UploadHttpException.kt`
- Test: `app/src/test/java/com/ddpai/uploader/youtube/ResumeParserTest.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/youtube/YouTubeUploader.kt`

**Interfaces:**
- Produces:
  - `sealed interface ResumeResult`: `Complete(videoId: String?)`, `Incomplete(nextByte: Long)`, `SessionExpired`, `Fatal(code: Int, body: String)`.
  - `object ResumeParser { fun parseOffset(code: Int, rangeHeader: String?, body: String): ResumeResult }`, `fun parseFinal(code: Int, body: String): ResumeResult`, `fun videoId(body: String): String?`.
  - `class UploadHttpException(code: Int, bodyText: String) : IOException`.
  - `YouTubeUploader.queryOffset` now returns `ResumeResult`; `uploadFrom` throws `UploadHttpException` on non-2xx.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ddpai/uploader/youtube/ResumeParserTest.kt`:
```kotlin
package com.ddpai.uploader.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeParserTest {
    @Test fun completeParsesVideoId() {
        val r = ResumeParser.parseOffset(200, null, """{"kind":"youtube#video","id":"abc123"}""")
        assertTrue(r is ResumeResult.Complete)
        assertEquals("abc123", (r as ResumeResult.Complete).videoId)
    }

    @Test fun incompleteReadsRangeHeader() {
        val r = ResumeParser.parseOffset(308, "bytes=0-262143", "")
        assertTrue(r is ResumeResult.Incomplete)
        assertEquals(262144L, (r as ResumeResult.Incomplete).nextByte)
    }

    @Test fun incompleteWithNoRangeStartsAtZero() {
        val r = ResumeParser.parseOffset(308, null, "")
        assertEquals(0L, (r as ResumeResult.Incomplete).nextByte)
    }

    @Test fun goneIsSessionExpired() {
        assertTrue(ResumeParser.parseOffset(410, null, "") is ResumeResult.SessionExpired)
        assertTrue(ResumeParser.parseOffset(404, null, "") is ResumeResult.SessionExpired)
    }

    @Test fun serverErrorIsFatal() {
        val r = ResumeParser.parseOffset(500, null, "boom")
        assertTrue(r is ResumeResult.Fatal)
        assertEquals(500, (r as ResumeResult.Fatal).code)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ddpai.uploader.youtube.ResumeParserTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write `ResumeParser.kt` and `UploadHttpException.kt`**

`app/src/main/java/com/ddpai/uploader/youtube/ResumeParser.kt`:
```kotlin
package com.ddpai.uploader.youtube

sealed interface ResumeResult {
    data class Complete(val videoId: String?) : ResumeResult
    data class Incomplete(val nextByte: Long) : ResumeResult
    data object SessionExpired : ResumeResult
    data class Fatal(val code: Int, val body: String) : ResumeResult
}

/** Interprets responses from the YouTube resumable-upload protocol. Pure; no IO. */
object ResumeParser {
    private val ID_RE = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")

    fun videoId(body: String): String? = ID_RE.find(body)?.groupValues?.get(1)

    /** Result of a "query current offset" PUT (Content-Range: bytes *&#47;total). */
    fun parseOffset(code: Int, rangeHeader: String?, body: String): ResumeResult = when (code) {
        200, 201 -> ResumeResult.Complete(videoId(body))
        308 -> ResumeResult.Incomplete(nextByteFromRange(rangeHeader))
        404, 410, 400 -> ResumeResult.SessionExpired
        else -> ResumeResult.Fatal(code, body)
    }

    /** Result of a bytes-uploading PUT. */
    fun parseFinal(code: Int, body: String): ResumeResult = when (code) {
        200, 201 -> ResumeResult.Complete(videoId(body))
        308 -> ResumeResult.Incomplete(-1L) // more bytes expected; caller re-queries
        404, 410, 400 -> ResumeResult.SessionExpired
        else -> ResumeResult.Fatal(code, body)
    }

    private fun nextByteFromRange(rangeHeader: String?): Long {
        val last = rangeHeader?.substringAfterLast('-')?.toLongOrNull() ?: return 0L
        return last + 1L
    }
}
```

`app/src/main/java/com/ddpai/uploader/youtube/UploadHttpException.kt`:
```kotlin
package com.ddpai.uploader.youtube

import java.io.IOException

class UploadHttpException(val code: Int, val bodyText: String) :
    IOException("upload HTTP $code: ${bodyText.take(300)}")
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ddpai.uploader.youtube.ResumeParserTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Refactor `YouTubeUploader` to use the parser and typed exception**

In `app/src/main/java/com/ddpai/uploader/youtube/YouTubeUploader.kt`, replace `queryOffset` and `uploadFrom`:
```kotlin
    suspend fun queryOffset(sessionUri: String, total: Long): ResumeResult {
        val token = auth.freshAccessToken() ?: throw IllegalStateException("Not authorized")
        val req = Request.Builder().url(sessionUri)
            .header("Authorization", "Bearer $token")
            .header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { resp ->
            return ResumeParser.parseOffset(resp.code, resp.header("Range"), resp.body?.string().orEmpty())
        }
    }

    /** Uploads from [startByte]; returns YouTube video ID on success, throws [UploadHttpException] otherwise. */
    suspend fun uploadFrom(
        sessionUri: String,
        file: File,
        startByte: Long,
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
            val text = resp.body?.string().orEmpty()
            return when (val r = ResumeParser.parseFinal(resp.code, text)) {
                is ResumeResult.Complete -> r.videoId ?: throw UploadHttpException(resp.code, "ok but no video id")
                else -> throw UploadHttpException(resp.code, text)
            }
        }
    }
```
(`initiate` and `FileRangeRequestBody` are unchanged. `ResumeResult` import is same package.)

- [ ] **Step 6: Build (UploadWorker still references old queryOffset — expected to break; fixed in Task 11)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ddpai.uploader.youtube.ResumeParserTest"`
Expected: the targeted test passes. A full `assembleDebug` will fail on `UploadWorker` until Task 11 — that is expected; do not fix it here.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/youtube/ResumeParser.kt app/src/main/java/com/ddpai/uploader/youtube/UploadHttpException.kt app/src/test/java/com/ddpai/uploader/youtube/ResumeParserTest.kt app/src/main/java/com/ddpai/uploader/youtube/YouTubeUploader.kt
git commit -m "refactor: resumable-upload responses via pure ResumeParser + typed UploadHttpException"
```

---

### Task 11: Rebuild `UploadWorker` — internet guard, quota pause, session expiry, completed-session

**Files:**
- Modify: `app/src/main/java/com/ddpai/uploader/pipeline/UploadWorker.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/pipeline/PipelineScheduler.kt`

**Interfaces:**
- Consumes: `ResumeResult`, `UploadHttpException`, `QuotaError`, `QuotaClock`, `RetryPolicy`, `ConfigRepository` runtime setters, `DashcamNetworkResolver`.
- Produces: `PipelineScheduler.enqueueUpload(context, initialDelayMillis: Long = 0L)`.

- [ ] **Step 1: Update `PipelineScheduler.enqueueUpload` for UNMETERED + optional delay**

In `app/src/main/java/com/ddpai/uploader/pipeline/PipelineScheduler.kt`, replace `enqueueUpload`:
```kotlin
    fun enqueueUpload(
        context: Context,
        initialDelayMillis: Long = 0L,
        existingPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
            .build()
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UPLOAD_WORK, existingPolicy, req)
    }
```

> **Why the policy parameter:** the quota-pause branch re-enqueues from *inside* a running `UploadWorker`. With the default `KEEP`, the still-RUNNING `UPLOAD_WORK` counts as existing uncompleted work and the delayed request is silently dropped, so uploads never auto-resume. The quota branch must pass `ExistingWorkPolicy.REPLACE` so the unique work is rescheduled with the delay (single-worker concurrency is preserved because it's still one unique name). Normal callers keep `KEEP`.

- [ ] **Step 2: Replace `UploadWorker.kt` in full**

```kotlin
package com.ddpai.uploader.pipeline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ddpai.uploader.data.model.FileStatus
import com.ddpai.uploader.di.ServiceLocator
import com.ddpai.uploader.network.DashcamNetworkResolver
import com.ddpai.uploader.youtube.QuotaClock
import com.ddpai.uploader.youtube.QuotaError
import com.ddpai.uploader.youtube.ResumeResult
import com.ddpai.uploader.youtube.UploadHttpException
import com.ddpai.uploader.youtube.YouTubeUploader

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val sl = ServiceLocator.get(applicationContext)

    override suspend fun getForegroundInfo() =
        sl.notifications.uploadForegroundInfo(applicationContext, "Uploading to YouTube…")

    override suspend fun doWork(): Result {
        if (!sl.config.isConfigured() || !sl.auth.isAuthorized()) {
            sl.log.w("UploadWorker", "Not configured/authorized; skipping")
            return Result.success()
        }
        val pausedUntil = sl.config.getQuotaPausedUntil()
        if (System.currentTimeMillis() < pausedUntil) {
            sl.log.w("UploadWorker", "Uploads paused until $pausedUntil (quota); skipping")
            return Result.success()
        }
        if (!onValidatedNonDashcamWifi()) {
            sl.log.w("UploadWorker", "Not on validated internet Wi-Fi; deferring uploads")
            return Result.retry()
        }
        promoteToForeground()

        val uploader = YouTubeUploader(sl.auth, sl.config, sl.log)
        val deleteLocal = sl.config.config.value.deleteAfterUpload
        val maxRetries = sl.config.config.value.maxRetries

        // Reclaim rows a previous run left mid-upload when the process was killed; the persisted
        // uploadSessionUrl then drives the resumable-session resume in uploadOne.
        val reclaimed = sl.files.reclaimOrphans(FileStatus.UPLOADING, FileStatus.DOWNLOADED)
        if (reclaimed > 0) sl.log.w("UploadWorker", "Reclaimed $reclaimed orphaned UPLOADING → DOWNLOADED")

        while (true) {
            val item = sl.files.nextToUpload() ?: break
            val file = sl.files.fileFor(item.fileName)
            if (!file.exists()) {
                sl.log.w("UploadWorker", "Local file missing for ${item.fileName}; resetting", item.fileName)
                sl.files.setStatus(item.fileName, FileStatus.PENDING)
                continue
            }
            try {
                sl.files.setStatus(item.fileName, FileStatus.UPLOADING)
                val videoId = uploadOne(uploader, item.fileName, file)
                sl.files.markUploadedAndDelete(item.fileName, videoId, deleteLocal)
                sl.log.i("UploadWorker", "UPLOADED ${item.fileName} → $videoId", item.fileName)
            } catch (e: UploadHttpException) {
                if (QuotaError.isQuota(e.code, e.bodyText)) {
                    val until = QuotaClock.nextResetMillis(System.currentTimeMillis())
                    sl.config.setQuotaPausedUntil(until)
                    sl.files.setStatus(item.fileName, FileStatus.DOWNLOADED)
                    sl.log.w("UploadWorker", "Quota exceeded; pausing uploads until $until", item.fileName)
                    PipelineScheduler.enqueueUpload(
                        applicationContext,
                        until - System.currentTimeMillis(),
                        ExistingWorkPolicy.REPLACE
                    )
                    return Result.success()
                }
                if (e.code == 401) {
                    sl.config.setNeedsReauth(true)
                    sl.log.e("UploadWorker", "401 unauthorized; re-auth required", item.fileName)
                    sl.files.setStatus(item.fileName, FileStatus.DOWNLOADED)
                    return Result.success()
                }
                handleRetryableUploadError(item.fileName, "HTTP ${e.code}", maxRetries)
                return Result.retry()
            } catch (e: Exception) {
                handleRetryableUploadError(item.fileName, e.message ?: "upload error", maxRetries)
                return Result.retry()
            }
        }
        sl.progress.clear()
        sl.log.i("UploadWorker", "Upload queue drained")
        return Result.success()
    }

    /** Runs the resumable protocol for one file; re-initiates once if the session is expired. */
    private suspend fun uploadOne(uploader: YouTubeUploader, name: String, file: java.io.File): String {
        var item = sl.files.get(name) ?: throw IllegalStateException("row vanished")
        var sessionUri = item.uploadSessionUrl
        var reinitiated = false

        while (true) {
            if (sessionUri.isNullOrBlank()) {
                sessionUri = uploader.initiate(file, item.fileName.removeSuffix(".mp4"))
                sl.files.update(item.copy(uploadSessionUrl = sessionUri, status = FileStatus.UPLOADING.name))
                item = sl.files.get(name)!!
                sl.log.i("UploadWorker", "Initiated session for $name", name)
                return uploader.uploadFrom(sessionUri, file, 0L) { s, t -> sl.progress.updateUpload(name, s, t) }
            }
            when (val offset = uploader.queryOffset(sessionUri, file.length())) {
                is ResumeResult.Complete ->
                    return offset.videoId ?: throw UploadHttpException(200, "completed but no id")
                is ResumeResult.Incomplete -> {
                    val start = if (offset.nextByte < 0) 0L else offset.nextByte
                    sl.log.i("UploadWorker", "Resuming $name @ $start", name)
                    return uploader.uploadFrom(sessionUri, file, start) { s, t -> sl.progress.updateUpload(name, s, t) }
                }
                is ResumeResult.SessionExpired -> {
                    if (reinitiated) throw UploadHttpException(410, "session expired twice")
                    reinitiated = true
                    sl.log.w("UploadWorker", "Session expired for $name; re-initiating", name)
                    sl.files.update(item.copy(uploadSessionUrl = null))
                    item = sl.files.get(name)!!
                    sessionUri = null
                }
                is ResumeResult.Fatal -> throw UploadHttpException(offset.code, offset.body)
            }
        }
    }

    private suspend fun handleRetryableUploadError(name: String, msg: String, maxRetries: Int) {
        sl.log.e("UploadWorker", "Upload error $name: $msg", name)
        sl.files.recordRetry(name, FileStatus.DOWNLOADED, msg)
        val cur = sl.files.get(name)
        if (cur != null && RetryPolicy.shouldFail(cur.retryCount, maxRetries)) {
            sl.files.setStatus(name, FileStatus.FAILED)
            sl.log.w("UploadWorker", "$name exhausted upload retries → FAILED", name)
        }
    }

    private fun onValidatedNonDashcamWifi(): Boolean {
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        val gateway = sl.config.config.value.dashcamGateway
        return DashcamNetworkResolver(cm, applicationContext).resolve(gateway) != active
    }

    private suspend fun promoteToForeground() {
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            sl.log.w("UploadWorker", "Foreground promotion refused (${e.message}); running in background")
        }
    }
}
```

- [ ] **Step 3: Build the whole app (Task 10 + 11 together compile clean)**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all unit tests pass.

- [ ] **Step 4: Manual verification checklist (record in commit body)**

- On home Wi-Fi with a DOWNLOADED file and a valid Android OAuth client: file uploads, gets a YouTube ID, local file deleted, status UPLOADED.
- While connected to the *dashcam* AP (no internet), upload work does not run and no bytes leak to mobile data (verify no upload attempts in Logs).
- Simulate a 403 quotaExceeded body (temporarily throw `UploadHttpException(403, "...quotaExceeded...")` in a scratch build): dashboard shows paused-until; work re-enqueues with delay; file stays DOWNLOADED.
- Kill app mid-upload, return to home Wi-Fi: upload resumes from the persisted session, not from zero.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/pipeline/UploadWorker.kt app/src/main/java/com/ddpai/uploader/pipeline/PipelineScheduler.kt
git commit -m "fix: UploadWorker pins to validated Wi-Fi, pauses on quota, re-inits expired sessions"
```

---

### Task 12: Signing-info helper + Config screen (Android-client instructions, SHA-1 card, deprecation fixes)

**Files:**
- Create: `app/src/main/java/com/ddpai/uploader/util/SigningInfo.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/ui/config/ConfigViewModel.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/ui/config/ConfigScreen.kt`

**Interfaces:**
- Consumes: `Context`.
- Produces: `object SigningInfo { fun packageName(ctx: Context): String; fun signingSha1(ctx: Context): String }`; `ConfigViewModel.packageName: String`, `ConfigViewModel.signingSha1: String`.

- [ ] **Step 1: Create `SigningInfo.kt`**

```kotlin
package com.ddpai.uploader.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object SigningInfo {
    fun packageName(ctx: Context): String = ctx.packageName

    fun signingSha1(ctx: Context): String = try {
        val pm = ctx.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.firstOrNull()?.toByteArray() ?: return "unavailable"
        val digest = MessageDigest.getInstance("SHA-1").digest(cert)
        digest.joinToString(":") { "%02X".format(it) }
    } catch (e: Exception) {
        "unavailable"
    }
}
```

- [ ] **Step 2: Expose signing info from `ConfigViewModel`**

In `app/src/main/java/com/ddpai/uploader/ui/config/ConfigViewModel.kt`, add (after the `sl` field):
```kotlin
    val packageName: String = com.ddpai.uploader.util.SigningInfo.packageName(application)
    val signingSha1: String = com.ddpai.uploader.util.SigningInfo.signingSha1(application)
```

- [ ] **Step 3: Add the SHA-1 copy card + fix deprecated `menuAnchor`, update instructions**

In `app/src/main/java/com/ddpai/uploader/ui/config/ConfigScreen.kt`:

(a) Fix the deprecated `menuAnchor()` at line ~146 — change:
```kotlin
                            modifier = Modifier.menuAnchor().fillMaxWidth()
```
to:
```kotlin
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
```
and add import `androidx.compose.material3.MenuAnchorType`.

(b) Replace the Google Cloud instructions text (the `Text(text = "1. Create a project...` block) with:
```kotlin
                Text(
                    text = "1. Create a project at console.cloud.google.com.\n" +
                        "2. Enable YouTube Data API v3.\n" +
                        "3. OAuth consent screen: External; add your Google account as a test user; " +
                        "scope https://www.googleapis.com/auth/youtube.upload.\n" +
                        "4. Create OAuth client ID → type Android. Use the package name and SHA-1 below. " +
                        "Android clients need NO client secret (leave it blank).\n" +
                        "5. Paste the Client ID above. Redirect URI is handled by the app " +
                        "(com.ddpai.uploader:/oauth2redirect).\n" +
                        "Fallback: a Web/Desktop client also works — paste both Client ID and Secret.",
                    style = MaterialTheme.typography.bodySmall
                )
```

(c) Add a new card just above the "Google Cloud setup guide" card (still inside the outer `Column`):
```kotlin
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Register this app (Android OAuth client)", style = MaterialTheme.typography.titleMedium)
                val clip = LocalClipboardManager.current
                Text("Package name", style = MaterialTheme.typography.labelSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(vm.packageName, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonospaceFamily))
                    TextButton(onClick = { clip.setText(AnnotatedString(vm.packageName)) }) { Text("Copy") }
                }
                Text("Signing SHA-1", style = MaterialTheme.typography.labelSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(vm.signingSha1, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonospaceFamily))
                    TextButton(onClick = { clip.setText(AnnotatedString(vm.signingSha1)) }) { Text("Copy") }
                }
            }
        }
```
Add imports:
```kotlin
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.ddpai.uploader.ui.theme.MonospaceFamily
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`; the `menuAnchor` deprecation warning for ConfigScreen is gone.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/util/SigningInfo.kt app/src/main/java/com/ddpai/uploader/ui/config/ConfigViewModel.kt app/src/main/java/com/ddpai/uploader/ui/config/ConfigScreen.kt
git commit -m "feat: Config shows package + SHA-1 for Android OAuth client; fix menuAnchor deprecation"
```

---

### Task 13: Dashboard banners (quota / re-auth) + Compose deprecation fixes

**Files:**
- Modify: `app/src/main/java/com/ddpai/uploader/ui/dashboard/DashboardViewModel.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/ui/dashboard/DashboardScreen.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/ui/files/FileListScreen.kt`

**Interfaces:**
- Consumes: `ConfigRepository.runtimeState`.
- Produces: `DashUiState` gains `quotaPausedUntil: Long` and `needsReauth: Boolean`.

- [ ] **Step 1: Extend `DashUiState` and the combine in `DashboardViewModel`**

In `app/src/main/java/com/ddpai/uploader/ui/dashboard/DashboardViewModel.kt`:

(a) Add two fields to `DashUiState`:
```kotlin
    val quotaPausedUntil: Long = 0L,
    val needsReauth: Boolean = false,
```

(b) The current `combine` takes 8 flows via the vararg `combine(...) { args -> }` form. Add `sl.config.runtimeState` as a 9th flow and read it:
Change the `combine(` argument list to include, after `sl.log.observe(8)`:
```kotlin
        sl.config.runtimeState,
```
Then inside the lambda add:
```kotlin
        val runtime = args[8] as com.ddpai.uploader.data.config.ConfigRepository.RuntimeState
```
and pass to `DashUiState(...)`:
```kotlin
            quotaPausedUntil = runtime.quotaPausedUntil,
            needsReauth = runtime.needsReauth,
```

- [ ] **Step 2: Render banners and fix deprecations in `DashboardScreen`**

In `app/src/main/java/com/ddpai/uploader/ui/dashboard/DashboardScreen.kt`:

(a) Replace both `Divider(color = ...)` calls (lines ~58 and ~90) with `HorizontalDivider(color = ...)` and add import `androidx.compose.material3.HorizontalDivider`.

(b) Replace the deprecated determinate `LinearProgressIndicator(progress = pct, modifier = ...)` (line ~71) with:
```kotlin
                        LinearProgressIndicator(
                            progress = { pct },
                            modifier = Modifier.fillMaxWidth()
                        )
```

(c) Just below the `Text("uDash Console", ...)` header, add banners:
```kotlin
        if (state.needsReauth) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
            ) {
                Text(
                    "YouTube session expired — re-authorize in Config.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        if (state.quotaPausedUntil > System.currentTimeMillis()) {
            val until = remember(state.quotaPausedUntil) {
                java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
                    .format(java.util.Date(state.quotaPausedUntil))
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.15f))
            ) {
                Text(
                    "Uploads paused (YouTube quota) until $until.",
                    color = WarningAmber,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
```
Add imports:
```kotlin
import androidx.compose.runtime.remember
import com.ddpai.uploader.ui.theme.WarningAmber
```

- [ ] **Step 3: Fix the deprecated progress indicator in `FileListScreen`**

In `app/src/main/java/com/ddpai/uploader/ui/files/FileListScreen.kt` (line ~116), replace:
```kotlin
                LinearProgressIndicator(progress = pct, modifier = Modifier.fillMaxWidth())
```
with:
```kotlin
                LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; the `Divider` and `LinearProgressIndicator` deprecation warnings are gone; all unit tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/ui/dashboard/ app/src/main/java/com/ddpai/uploader/ui/files/FileListScreen.kt
git commit -m "feat: dashboard quota/re-auth banners; fix Compose deprecations"
```

---

### Task 14: Plan A verification pass

**Files:** none (verification only).

- [ ] **Step 1: Full clean build + all tests**

Run: `./gradlew clean :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`. Test report shows the original 3 tests plus the new suites: `RetryPolicyTest`, `ListingFilterTest`, `QuotaErrorTest`, `QuotaClockTest`, `ResumeParserTest` — all green.

- [ ] **Step 2: Confirm APK slimming**

Run: `./gradlew :app:assembleDebug` and note the absence of `libavcodec.so`/`libffmpegkit.so` in the `stripDebugDebugSymbols` output (compare to the pre-Task-1 warning list).
Expected: FFmpeg native libraries no longer packaged.

- [ ] **Step 3: Manual end-to-end (device) checklist**

Record pass/fail for each in the commit body:
1. Fresh install → Config: create an Android OAuth client using the shown package + SHA-1 → paste Client ID (no secret) → Authorize → token persists across app restart.
2. Connect to dashcam AP with app swiped away (Android 12+) → downloads run; newest still-recording clip is skipped; reconnect → no re-download.
3. Connect to home Wi-Fi → oldest DOWNLOADED file uploads, gets an ID, local file deleted.
4. Edit gateway in Config to a wrong value → camera unreachable but app does not crash; restore value → downloads resume.

- [ ] **Step 4: Commit the verification record**

```bash
git commit --allow-empty -m "test: Plan A verification — build green, unit suites pass, E2E checklist recorded"
```

---

## Self-Review (Plan A)

- **Spec coverage:** Part 1a (Task 6 fg guard), 1b (Tasks 3+6), 1c (Task 4+6), 1d (Tasks 4+5+6), 1e (Task 2), 1f (Tasks 5+6), 1g (deferred to Plan B `SyncController`; Plan A keeps existing App.kt behavior — noted). Part 2a (Tasks 8+12), 2b (Task 11), 2c (Tasks 10+11), 2d (Tasks 10+11), 2e (Tasks 8+9+11+13). Part 4b (Task 1). Part 4c deprecations (Tasks 12+13); EXTREME log chip deferred to Plan B with the logging additions. Part 4d unit tests woven through; device E2E in Task 14.
- **Deferred to Plan B (by design):** event debounce (1g) and the EXTREME log chip land with `SyncController` and the merge logging in Plan B, since both belong to the new-subsystem work.
- **Type consistency:** `recordRetry(name, retryStatus, error, ts)` DAO vs `recordRetry(name, retryStatus: FileStatus, error)` repo — repo maps enum→name. `ResumeResult` variants used identically in parser, uploader, worker. `enqueueUpload(context, initialDelayMillis)` default keeps existing call sites valid.
