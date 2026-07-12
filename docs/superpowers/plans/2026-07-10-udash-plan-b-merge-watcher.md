# uDash Plan B — Merge-per-Drive & Background Watcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Prerequisite:** Plan A (`2026-07-10-udash-plan-a-pipeline-oauth.md`) must be fully implemented and merged first. This plan assumes Plan A's `RetryPolicy`, `DashcamNetworkResolver`, `NetworkGateway`, `ServiceLocator.io`, quota/re-auth runtime state, and the rebuilt `UploadWorker`/`DownloadWorker` exist.

**Goal:** Merge each drive's 60-second segments into one lossless file before upload (to fit YouTube's ~6-uploads/day quota), and keep auto-sync alive in the background via a user-selectable persistent watcher service or a battery-saver periodic check.

**Architecture:** A new `merge/` package builds drive-session groups from downloaded segments (pure `DriveGrouper`) and concatenates them with platform `MediaExtractor`/`MediaMuxer` (no re-encode). A `MergeWorker` runs on home Wi-Fi chained before `UploadWorker`; only `MERGED`-kind rows are uploadable. Room advances to version 2 with a real migration (no more destructive fallback). A `SyncController` centralizes network classification and work enqueuing, driven either by a persistent foreground `WatcherService` (started on boot) or a 15-minute periodic `SyncCheckWorker`.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Room, WorkManager, OkHttp, AppAuth, Media3, Android `MediaExtractor`/`MediaMuxer`.

## Global Constraints

- Language Kotlin only. minSdk 26, targetSdk 34, compileSdk 34. JVM target 17. Package `com.ddpai.uploader`.
- Filename regex (authoritative): `(\d{8})(\d{6})_(0060|F|R)\.mp4`. Stream key = the suffix group (`0060`, `F`, or `R`); distinct keys never merge together.
- Merge is lossless sample-copy only (`MediaExtractor` → `MediaMuxer`); never re-encode. Segment `MediaFormat` must match the group head (mime, width, height, sample-rate, channel-count); a mismatch closes the group at that boundary.
- Drive-session grouping: consecutive `DOWNLOADED` `SEGMENT` rows of one stream where each starts ≤ 120,000 ms after the previous start; cap 60 segments per output (overflow → `_p2`, `_p3`…); a group is *closed* (eligible to merge) only when `newestSegment.capturedAtEpoch + 60,000 ms` is more than 300,000 ms in the past.
- Integrity gate (unchanged from Plan A): size ≥ 1,048,576 bytes AND `ftyp` + `mdat` + `moov` present (`Mp4AtomScanner`).
- Upload gate after this plan: only rows with `status = 'DOWNLOADED' AND kind = 'MERGED'` are uploadable. Raw segments are never uploaded directly.
- Foreground service type for the watcher: `specialUse` (subtype `dashcam-wifi-watcher`), requires `FOREGROUND_SERVICE_SPECIAL_USE`; boot-start requires `RECEIVE_BOOT_COMPLETED`.
- Sync modes: `PERSISTENT` (default) and `BATTERY_SAVER`, stored in `AppConfig.syncMode`.
- Build/test gate for every task: `./gradlew :app:assembleDebug :app:testDebugUnitTest`. Commit after every task.

> **Build environment note:** if `gradlew` fails with `Unable to establish loopback connection`, set `_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\gtmp` (create `C:\gtmp` first). Local shell quirk, not a code issue.

---

### Task 1: Room v2 — add `kind`/`mergedInto`, `MERGED` status, real migration

**Files:**
- Modify: `app/src/main/java/com/ddpai/uploader/data/model/FileStatus.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/data/db/entity/VideoFileEntity.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/data/db/AppDatabase.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `VideoFileEntity.kind: String` (default `"SEGMENT"`), `VideoFileEntity.mergedInto: String?`; `FileStatus.MERGED`; `AppDatabase` at version 2 with `MIGRATION_1_2`.

- [ ] **Step 1: Add the `MERGED` status**

In `app/src/main/java/com/ddpai/uploader/data/model/FileStatus.kt`, add `MERGED` to the enum:
```kotlin
enum class FileStatus {
    DISCOVERED,
    DOWNLOADING,
    DOWNLOADED,
    UPLOADING,
    UPLOADED,
    MERGED,       // segment consumed into a merged drive file; local segment deleted
    PENDING,
    FAILED
}
```

- [ ] **Step 2: Add columns to the entity**

In `app/src/main/java/com/ddpai/uploader/data/db/entity/VideoFileEntity.kt`, add `import androidx.room.ColumnInfo` and two fields (after `capturedAtEpoch`). The `@ColumnInfo(defaultValue = ...)` on `kind` makes the entity self-describing so a fresh install and a migrated DB produce an identical column default (Room otherwise records no default on the fresh-install column):
```kotlin
    @ColumnInfo(defaultValue = "SEGMENT") val kind: String = "SEGMENT",  // SEGMENT | MERGED
    val mergedInto: String? = null,      // for consumed segments: the merged output's fileName
```

- [ ] **Step 3: Bump the DB version and add the migration**

In `app/src/main/java/com/ddpai/uploader/data/db/AppDatabase.kt`, replace the file with:
```kotlin
package com.ddpai.uploader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ddpai.uploader.data.db.entity.LogEntity
import com.ddpai.uploader.data.db.entity.VideoFileEntity

@Database(entities = [VideoFileEntity::class, LogEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoFileDao(): VideoFileDao
    abstract fun logDao(): LogDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE video_files ADD COLUMN kind TEXT NOT NULL DEFAULT 'SEGMENT'")
                db.execSQL("ALTER TABLE video_files ADD COLUMN mergedInto TEXT")
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "ddpai.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}
```
Note: `fallbackToDestructiveMigration()` is intentionally removed — existing users' rows survive the upgrade.

- [ ] **Step 4: Build (Room validates schema v2 + migration)**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Room KSP regenerates the schema; all existing DAO queries still compile.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/data/model/FileStatus.kt app/src/main/java/com/ddpai/uploader/data/db/entity/VideoFileEntity.kt app/src/main/java/com/ddpai/uploader/data/db/AppDatabase.kt
git commit -m "feat: Room v2 — kind/mergedInto columns, MERGED status, non-destructive migration"
```

---

### Task 2: `DriveGrouper` — build drive-session groups (pure, tested)

**Files:**
- Create: `app/src/main/java/com/ddpai/uploader/merge/DriveGrouper.kt`
- Test: `app/src/test/java/com/ddpai/uploader/merge/DriveGrouperTest.kt`

**Interfaces:**
- Produces:
  - `data class DriveGrouper.Segment(fileName: String, capturedAtEpoch: Long, streamKey: String)`
  - `data class DriveGrouper.DriveGroup(streamKey: String, segments: List<Segment>)`
  - `object DriveGrouper { fun streamKeyOf(fileName: String): String?; fun buildClosedGroups(segments: List<Segment>, nowMillis: Long, maxPerGroup: Int = 60, gapMillis: Long = 120_000L, closeAfterMillis: Long = 300_000L, segmentDurationMillis: Long = 60_000L): List<DriveGroup> }`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ddpai/uploader/merge/DriveGrouperTest.kt`:
```kotlin
package com.ddpai.uploader.merge

import com.ddpai.uploader.merge.DriveGrouper.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveGrouperTest {
    private val minute = 60_000L
    // A "now" far in the future so every group is closed unless a test overrides.
    private val farFuture = 10_000_000_000L

    private fun seg(name: String, epoch: Long, stream: String = "0060") = Segment(name, epoch, stream)

    @Test fun streamKeyParsing() {
        assertEquals("0060", DriveGrouper.streamKeyOf("20260626180905_0060.mp4"))
        assertEquals("F", DriveGrouper.streamKeyOf("20260626180905_F.mp4"))
        assertEquals("R", DriveGrouper.streamKeyOf("20260626180905_R.mp4"))
        assertNull(DriveGrouper.streamKeyOf("garbage.mp4"))
    }

    @Test fun contiguousSegmentsFormOneGroup() {
        val segs = listOf(
            seg("a", 0L), seg("b", minute), seg("c", 2 * minute)
        )
        val groups = DriveGrouper.buildClosedGroups(segs, farFuture)
        assertEquals(1, groups.size)
        assertEquals(listOf("a", "b", "c"), groups[0].segments.map { it.fileName })
    }

    @Test fun largeGapSplitsGroups() {
        val segs = listOf(
            seg("a", 0L), seg("b", minute),
            seg("c", 10 * minute), seg("d", 11 * minute) // 9-min gap > 120s
        )
        val groups = DriveGrouper.buildClosedGroups(segs, farFuture)
        assertEquals(2, groups.size)
        assertEquals(listOf("a", "b"), groups[0].segments.map { it.fileName })
        assertEquals(listOf("c", "d"), groups[1].segments.map { it.fileName })
    }

    @Test fun differentStreamsNeverMerge() {
        val segs = listOf(
            seg("f1", 0L, "F"), seg("r1", 0L, "R"), seg("f2", minute, "F")
        )
        val groups = DriveGrouper.buildClosedGroups(segs, farFuture)
        assertEquals(2, groups.size)
        assertTrue(groups.all { g -> g.segments.all { it.streamKey == g.streamKey } })
    }

    @Test fun capSplitsIntoParts() {
        val segs = (0 until 130).map { seg("s$it", it * minute) }
        val groups = DriveGrouper.buildClosedGroups(segs, farFuture, maxPerGroup = 60)
        assertEquals(3, groups.size) // 60 + 60 + 10
        assertEquals(60, groups[0].segments.size)
        assertEquals(60, groups[1].segments.size)
        assertEquals(10, groups[2].segments.size)
    }

    @Test fun openGroupIsExcluded() {
        // newest segment ended (epoch + 60s) only 1 minute before now → still open
        val now = 100 * minute
        val segs = listOf(seg("a", now - 2 * minute), seg("b", now - minute))
        val groups = DriveGrouper.buildClosedGroups(segs, now, closeAfterMillis = 5 * minute)
        assertTrue(groups.isEmpty())
    }

    @Test fun singleSegmentClosedGroupIsKept() {
        val groups = DriveGrouper.buildClosedGroups(listOf(seg("solo", 0L)), farFuture)
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].segments.size)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ddpai.uploader.merge.DriveGrouperTest"`
Expected: FAIL — unresolved reference `DriveGrouper`.

- [ ] **Step 3: Write `DriveGrouper.kt`**

```kotlin
package com.ddpai.uploader.merge

/** Groups downloaded dashcam segments into drive sessions for lossless merging. Pure; no IO. */
object DriveGrouper {
    data class Segment(val fileName: String, val capturedAtEpoch: Long, val streamKey: String)
    data class DriveGroup(val streamKey: String, val segments: List<Segment>)

    private val NAME_RE = Regex("""\d{8}\d{6}_(0060|F|R)\.mp4""", RegexOption.IGNORE_CASE)

    fun streamKeyOf(fileName: String): String? =
        NAME_RE.find(fileName)?.groupValues?.get(1)?.uppercase()?.let {
            if (it == "0060") "0060" else it
        }

    fun buildClosedGroups(
        segments: List<Segment>,
        nowMillis: Long,
        maxPerGroup: Int = 60,
        gapMillis: Long = 120_000L,
        closeAfterMillis: Long = 300_000L,
        segmentDurationMillis: Long = 60_000L
    ): List<DriveGroup> {
        val result = mutableListOf<DriveGroup>()
        // Split by stream, then walk each stream in capture order.
        segments.groupBy { it.streamKey }.forEach { (stream, streamSegs) ->
            val ordered = streamSegs.sortedBy { it.capturedAtEpoch }
            var current = mutableListOf<Segment>()

            fun flush() {
                if (current.isEmpty()) return
                val newest = current.maxOf { it.capturedAtEpoch }
                val closed = newest + segmentDurationMillis <= nowMillis - closeAfterMillis
                if (closed) result += DriveGroup(stream, current.toList())
                current = mutableListOf()
            }

            for (seg in ordered) {
                val prev = current.lastOrNull()
                val breaks = prev != null &&
                    (seg.capturedAtEpoch - prev.capturedAtEpoch > gapMillis || current.size >= maxPerGroup)
                if (breaks) flush()
                current.add(seg)
            }
            flush()
        }
        return result
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ddpai.uploader.merge.DriveGrouperTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/merge/DriveGrouper.kt app/src/test/java/com/ddpai/uploader/merge/DriveGrouperTest.kt
git commit -m "feat: DriveGrouper builds drive-session groups (gap/cap/stream/close rules) with tests"
```

---

### Task 2b: `MergeNaming` — output filename (pure, tested)

**Files:**
- Create: `app/src/main/java/com/ddpai/uploader/merge/MergeNaming.kt`
- Test: `app/src/test/java/com/ddpai/uploader/merge/MergeNamingTest.kt`

**Interfaces:**
- Produces: `object MergeNaming { fun outputName(head: DriveGrouper.Segment, part: Int): String }` — `DRIVE_<yyyyMMdd_HHmmss>_<stream>.mp4`, with `_p<part>` appended when `part > 1`. Uses the head segment's own filename digits (not a timezone conversion) so naming is deterministic and timezone-free.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ddpai/uploader/merge/MergeNamingTest.kt`:
```kotlin
package com.ddpai.uploader.merge

import com.ddpai.uploader.merge.DriveGrouper.Segment
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeNamingTest {
    @Test fun firstPartHasNoSuffix() {
        val head = Segment("20260626180905_0060.mp4", 0L, "0060")
        assertEquals("DRIVE_20260626_180905_0060.mp4", MergeNaming.outputName(head, 1))
    }

    @Test fun laterPartsGetPartSuffix() {
        val head = Segment("20260626190000_F.mp4", 0L, "F")
        assertEquals("DRIVE_20260626_190000_F_p2.mp4", MergeNaming.outputName(head, 2))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ddpai.uploader.merge.MergeNamingTest"`
Expected: FAIL — unresolved reference `MergeNaming`.

- [ ] **Step 3: Write `MergeNaming.kt`**

```kotlin
package com.ddpai.uploader.merge

object MergeNaming {
    private val NAME_RE = Regex("""(\d{8})(\d{6})_(0060|F|R)\.mp4""", RegexOption.IGNORE_CASE)

    /** DRIVE_<date>_<time>_<stream>.mp4, with _p<N> for parts beyond the first. */
    fun outputName(head: DriveGrouper.Segment, part: Int): String {
        val m = NAME_RE.find(head.fileName)
        val date = m?.groupValues?.get(1) ?: "00000000"
        val time = m?.groupValues?.get(2) ?: "000000"
        val suffix = if (part > 1) "_p$part" else ""
        return "DRIVE_${date}_${time}_${head.streamKey}$suffix.mp4"
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ddpai.uploader.merge.MergeNamingTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/merge/MergeNaming.kt app/src/test/java/com/ddpai/uploader/merge/MergeNamingTest.kt
git commit -m "feat: deterministic merged-output naming with tests"
```

---

### Task 3: `Mp4Merger` — lossless concatenation via MediaExtractor/MediaMuxer

**Files:**
- Create: `app/src/main/java/com/ddpai/uploader/merge/Mp4Merger.kt`

**Interfaces:**
- Consumes: a list of input `File`s (already integrity-verified) and an output `File`.
- Produces:
  - `sealed interface Mp4Merger.Outcome`: `Success`, `FormatMismatch(atIndex: Int)`, `Error(message: String)`.
  - `class Mp4Merger { fun merge(inputs: List<File>, output: File): Outcome }`.

- [ ] **Step 1: Write `Mp4Merger.kt`**

```kotlin
package com.ddpai.uploader.merge

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Concatenates MP4 segments into one file by copying encoded samples (no re-encode) with
 * presentation-timestamp offsets. All inputs must share the same track layout/format as the first.
 */
class Mp4Merger {
    sealed interface Outcome {
        data object Success : Outcome
        data class FormatMismatch(val atIndex: Int) : Outcome
        data class Error(val message: String) : Outcome
    }

    private data class TrackFormat(val mime: String, val width: Int, val height: Int, val sampleRate: Int, val channels: Int)

    fun merge(inputs: List<File>, output: File): Outcome {
        if (inputs.isEmpty()) return Outcome.Error("no inputs")
        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // Establish tracks from the first segment.
            val head = MediaExtractor().apply { setDataSource(inputs[0].absolutePath) }
            val headFormats = ArrayList<MediaFormat>()
            val muxTrackIndex = IntArray(head.trackCount)
            for (t in 0 until head.trackCount) {
                val fmt = head.getTrackFormat(t)
                headFormats.add(fmt)
                muxTrackIndex[t] = muxer.addTrack(fmt)
            }
            val headSignature = headFormats.map { it.signature() }
            head.release()

            muxer.start()
            val buffer = ByteBuffer.allocate(1 shl 20) // 1 MB sample buffer
            var timeOffsetUs = 0L

            for ((index, input) in inputs.withIndex()) {
                val extractor = MediaExtractor().apply { setDataSource(input.absolutePath) }
                try {
                    if (extractor.trackCount != muxTrackIndex.size ||
                        (0 until extractor.trackCount).map { extractor.getTrackFormat(it).signature() } != headSignature
                    ) {
                        return Outcome.FormatMismatch(index)
                    }
                    var maxPtsThisSegment = 0L
                    for (t in 0 until extractor.trackCount) {
                        extractor.selectTrack(t)
                        val bufferInfo = MediaCodec.BufferInfo()
                        while (true) {
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            val pts = extractor.sampleTime + timeOffsetUs
                            bufferInfo.set(0, size, pts, extractor.sampleFlags)
                            muxer.writeSampleData(muxTrackIndex[t], buffer, bufferInfo)
                            if (pts > maxPtsThisSegment) maxPtsThisSegment = pts
                            extractor.advance()
                        }
                        extractor.unselectTrack(t)
                    }
                    // Next segment's timestamps start after this one (+ ~1 frame at 30fps).
                    timeOffsetUs = maxPtsThisSegment + 33_333L
                } finally {
                    extractor.release()
                }
            }
            return Outcome.Success
        } catch (e: Exception) {
            return Outcome.Error(e.message ?: e.javaClass.simpleName)
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun MediaFormat.signature(): String {
        val mime = getString(MediaFormat.KEY_MIME) ?: "?"
        fun intOrZero(key: String) = if (containsKey(key)) getInteger(key) else 0
        return listOf(
            mime,
            intOrZero(MediaFormat.KEY_WIDTH),
            intOrZero(MediaFormat.KEY_HEIGHT),
            intOrZero(MediaFormat.KEY_SAMPLE_RATE),
            intOrZero(MediaFormat.KEY_CHANNEL_COUNT)
        ).joinToString("|")
    }
}
```

> **Applied fixes (Task B3 review):** The initial 1 MB fixed buffer could silently truncate a track when a keyframe exceeds it (`readSampleData` overflow is indistinguishable from EOS) while still returning `Success`. The shipped code (a) sizes the sample buffer from `max(MediaFormat.KEY_MAX_INPUT_SIZE across head tracks, 4 MB floor)`; (b) calls `muxer.stop()` **before** the `Success` return (with a `started` flag) so a finalization failure becomes `Outcome.Error`, not a false success; (c) initializes `maxPtsThisSegment = timeOffsetUs` so a zero-sample segment cannot regress the running offset; (d) drops the unused `TrackFormat` data class.

> **Test note:** `MediaExtractor`/`MediaMuxer` are native and cannot run in JVM unit tests. This class is verified by the device E2E checklist in Task 9 — which MUST play a merged file containing BOTH audio and video tracks and confirm audio is present and in sync throughout (not just duration ≈ sum), since the per-track select/drain approach can only be validated on-device. Its pure collaborators (`DriveGrouper`, `MergeNaming`) are unit-tested.

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/merge/Mp4Merger.kt
git commit -m "feat: lossless Mp4Merger (MediaExtractor/MediaMuxer sample copy with pts offsets)"
```

---

### Task 4: DAO + repository support for merging and the new upload gate

**Files:**
- Modify: `app/src/main/java/com/ddpai/uploader/data/db/VideoFileDao.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/data/repo/FileRepository.kt`

**Interfaces:**
- Produces:
  - `VideoFileDao.nextToUpload()` now filters `kind = 'MERGED'`.
  - `VideoFileDao.downloadedSegments(): List<VideoFileEntity>` (status DOWNLOADED, kind SEGMENT, ordered).
  - `VideoFileDao.commitMerge(output: VideoFileEntity, consumed: List<String>, ts: Long)` — `@Transaction`.
  - `VideoFileDao.relabelAsMerged(name: String, ts: Long)`.
  - `FileRepository.downloadedSegments()`, `FileRepository.commitMerge(output, consumedNames)`, `FileRepository.relabelAsMerged(name)`.

- [ ] **Step 1: Update the upload-gate query and add merge queries**

In `app/src/main/java/com/ddpai/uploader/data/db/VideoFileDao.kt`:

(a) Replace `nextToUpload`:
```kotlin
    @Query("SELECT * FROM video_files WHERE status = 'DOWNLOADED' AND kind = 'MERGED' ORDER BY capturedAtEpoch ASC LIMIT 1")
    suspend fun nextToUpload(): VideoFileEntity?
```

(b) Add:
```kotlin
    @Query("SELECT * FROM video_files WHERE status = 'DOWNLOADED' AND kind = 'SEGMENT' ORDER BY capturedAtEpoch ASC")
    suspend fun downloadedSegments(): List<VideoFileEntity>

    @Query("UPDATE video_files SET kind = 'MERGED', updatedAtEpoch = :ts WHERE fileName = :name")
    suspend fun relabelAsMerged(name: String, ts: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMerged(entity: VideoFileEntity)

    @Query("UPDATE video_files SET status = 'MERGED', mergedInto = :output, localPath = NULL, updatedAtEpoch = :ts WHERE fileName = :name")
    suspend fun markSegmentMerged(name: String, output: String, ts: Long = System.currentTimeMillis())

    @Transaction
    suspend fun commitMerge(output: VideoFileEntity, consumed: List<String>, ts: Long = System.currentTimeMillis()) {
        insertMerged(output)
        consumed.forEach { markSegmentMerged(it, output.fileName, ts) }
    }
```

- [ ] **Step 2: Add repository wrappers**

In `app/src/main/java/com/ddpai/uploader/data/repo/FileRepository.kt`, add:
```kotlin
    suspend fun downloadedSegments() = dao.downloadedSegments()

    suspend fun relabelAsMerged(name: String) = dao.relabelAsMerged(name)

    suspend fun commitMerge(output: VideoFileEntity, consumedNames: List<String>) =
        dao.commitMerge(output, consumedNames)
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Room validates the new queries and the `@Transaction` default method.

> After this task the app only uploads `MERGED` rows. Until `MergeWorker` (Task 5) runs, plain downloaded segments will not upload — expected; the chain in Task 6 wires merge→upload.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/data/db/VideoFileDao.kt app/src/main/java/com/ddpai/uploader/data/repo/FileRepository.kt
git commit -m "feat: DAO/repo merge transaction + upload gate limited to MERGED rows"
```

---

### Task 5: `MergeWorker` — group, merge, verify, commit atomically

**Files:**
- Create: `app/src/main/java/com/ddpai/uploader/pipeline/MergeWorker.kt`

**Interfaces:**
- Consumes: `DriveGrouper`, `MergeNaming`, `Mp4Merger`, `IntegrityVerifier`, `Mp4AtomScanner`, `FileRepository`.
- Produces: a `MergeWorker` whose completion leaves every closed group either as one `MERGED` file (multi-segment) or relabeled segments (single-segment / merge-failure fallback), all upload-ready.

- [ ] **Step 1: Write `MergeWorker.kt`**

```kotlin
package com.ddpai.uploader.pipeline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ddpai.uploader.data.db.entity.VideoFileEntity
import com.ddpai.uploader.data.model.FileStatus
import com.ddpai.uploader.di.ServiceLocator
import com.ddpai.uploader.integrity.Mp4AtomScanner
import com.ddpai.uploader.merge.DriveGrouper
import com.ddpai.uploader.merge.MergeNaming
import com.ddpai.uploader.merge.Mp4Merger
import java.io.File

class MergeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val sl = ServiceLocator.get(applicationContext)

    override suspend fun getForegroundInfo() =
        sl.notifications.uploadForegroundInfo(applicationContext, "Merging drive clips…")

    override suspend fun doWork(): Result {
        promoteToForeground()
        val segments = sl.files.downloadedSegments()
        if (segments.isEmpty()) {
            sl.log.i("MergeWorker", "No segments to merge")
            return Result.success()
        }
        val grouperSegs = segments.mapNotNull { e ->
            DriveGrouper.streamKeyOf(e.fileName)?.let { key ->
                DriveGrouper.Segment(e.fileName, e.capturedAtEpoch, key)
            }
        }
        val groups = DriveGrouper.buildClosedGroups(grouperSegs, System.currentTimeMillis())
        sl.log.i("MergeWorker", "${groups.size} closed drive group(s) ready to merge")
        val byName = segments.associateBy { it.fileName }
        val merger = Mp4Merger()

        for (group in groups) {
            // DriveGrouper already splits by gap and by the 60-segment cap, so every group has a
            // distinct head timestamp and therefore a unique output name — no _p suffix needed.
            processGroup(group, byName, merger)
        }
        sl.log.i("MergeWorker", "Merge cycle complete")
        return Result.success()
    }

    private suspend fun processGroup(
        group: DriveGrouper.DriveGroup,
        byName: Map<String, VideoFileEntity>,
        merger: Mp4Merger
    ) {
        val entities = group.segments.mapNotNull { byName[it.fileName] }
        if (entities.isEmpty()) return

        if (entities.size == 1) {
            sl.files.relabelAsMerged(entities[0].fileName)
            sl.log.i("MergeWorker", "Single-segment drive ${entities[0].fileName} → MERGED (no copy)")
            return
        }

        val outputName = MergeNaming.outputName(group.segments.first(), 1)
        val outputFile = sl.files.fileFor(outputName)
        val tmp = File(outputFile.absolutePath + ".tmp")
        if (tmp.exists()) tmp.delete()

        val inputs = entities.map { sl.files.fileFor(it.fileName) }
        when (val outcome = merger.merge(inputs, tmp)) {
            is Mp4Merger.Outcome.Success -> {
                val scan = Mp4AtomScanner.scan(tmp)
                if (!scan.isValid) {
                    sl.log.e("MergeWorker", "Merged $outputName failed integrity (${scanReason(scan)}); fallback to per-segment")
                    tmp.delete()
                    fallbackRelabel(entities)
                    return
                }
                if (!tmp.renameTo(outputFile)) {
                    sl.log.e("MergeWorker", "Rename failed for $outputName; fallback to per-segment")
                    tmp.delete()
                    fallbackRelabel(entities)
                    return
                }
                val merged = VideoFileEntity(
                    fileName = outputName,
                    remoteUrl = "",
                    localPath = outputFile.absolutePath,
                    status = FileStatus.DOWNLOADED.name,
                    kind = "MERGED",
                    sizeBytes = outputFile.length(),
                    downloadedBytes = outputFile.length(),
                    capturedAtEpoch = group.segments.first().capturedAtEpoch
                )
                sl.files.commitMerge(merged, entities.map { it.fileName })
                entities.forEach { sl.files.fileFor(it.fileName).delete() }
                sl.log.i("MergeWorker", "Merged ${entities.size} clips → $outputName (${outputFile.length()} bytes)")
            }
            is Mp4Merger.Outcome.FormatMismatch -> {
                sl.log.w("MergeWorker", "Format mismatch at index ${outcome.atIndex} in $outputName; fallback to per-segment")
                tmp.delete()
                fallbackRelabel(entities)
            }
            is Mp4Merger.Outcome.Error -> {
                sl.log.e("MergeWorker", "Merge error for $outputName: ${outcome.message}; fallback to per-segment")
                tmp.delete()
                fallbackRelabel(entities)
            }
        }
    }

    /** On any merge failure, upload the segments individually (still lossless, just more uploads). */
    private suspend fun fallbackRelabel(entities: List<VideoFileEntity>) {
        entities.forEach { sl.files.relabelAsMerged(it.fileName) }
    }

    private fun scanReason(scan: Mp4AtomScanner.Result): String = buildString {
        if (!scan.sizeOk) append("size ")
        if (!scan.hasFtyp) append("ftyp ")
        if (!scan.hasMdat) append("mdat ")
        if (!scan.hasMoov) append("moov ")
    }.trim().ifEmpty { "unknown" }

    private suspend fun promoteToForeground() {
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            sl.log.w("MergeWorker", "Foreground promotion refused (${e.message}); running in background")
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/pipeline/MergeWorker.kt
git commit -m "feat: MergeWorker groups+merges drives atomically, with per-segment fallback"
```

---

### Task 6: Chain merge → upload in the scheduler

**Files:**
- Modify: `app/src/main/java/com/ddpai/uploader/pipeline/PipelineScheduler.kt`

**Interfaces:**
- Consumes: `MergeWorker`, existing `UploadWorker` (Plan A) and its UNMETERED constraint.
- Produces: `PipelineScheduler.enqueueMergeThenUpload(context, initialDelayMillis: Long = 0L)`; `enqueueUpload` retained for the quota-delay re-enqueue path from Plan A's `UploadWorker`.

- [ ] **Step 1: Add the merge→upload chain**

In `app/src/main/java/com/ddpai/uploader/pipeline/PipelineScheduler.kt`:

(a) Add a work name constant near the others:
```kotlin
    const val MERGE_UPLOAD_WORK = "ddpai_merge_upload"
```

(b) Add:
```kotlin
    fun enqueueMergeThenUpload(context: Context, initialDelayMillis: Long = 0L) {
        val unmetered = Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
            .build()
        val merge = OneTimeWorkRequestBuilder<MergeWorker>()
            .setConstraints(unmetered)
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .build()
        val upload = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(unmetered)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .beginUniqueWork(MERGE_UPLOAD_WORK, ExistingWorkPolicy.KEEP, merge)
            .then(upload)
            .enqueue()
    }
```

(c) Update `cancelAll` to also cancel the chain:
```kotlin
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(DOWNLOAD_WORK)
        wm.cancelUniqueWork(UPLOAD_WORK)
        wm.cancelUniqueWork(MERGE_UPLOAD_WORK)
    }
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/pipeline/PipelineScheduler.kt
git commit -m "feat: merge→upload WorkManager chain for home-WiFi processing"
```

---

### Task 7: `SyncController` + sync-mode config + battery-saver worker

**Files:**
- Modify: `app/src/main/java/com/ddpai/uploader/data/config/AppConfig.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/data/config/ConfigRepository.kt`
- Create: `app/src/main/java/com/ddpai/uploader/pipeline/SyncController.kt`
- Create: `app/src/main/java/com/ddpai/uploader/pipeline/SyncCheckWorker.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/App.kt`

**Interfaces:**
- Consumes: `NetworkMonitor`, `NetworkType`, `DashcamNetworkResolver`, `PipelineScheduler`.
- Produces:
  - `AppConfig.syncMode: String` (default `"PERSISTENT"`).
  - `class SyncController(context, sl)` with `fun start()`, `fun onNetwork(type: NetworkType, network: Network?)`, `fun classifyAndEnqueue()`.
  - `SyncCheckWorker` (periodic, battery-saver mode).

- [ ] **Step 1: Add `syncMode` to config**

In `app/src/main/java/com/ddpai/uploader/data/config/AppConfig.kt`, add a field:
```kotlin
    val syncMode: String = "PERSISTENT",   // PERSISTENT | BATTERY_SAVER
```
In `app/src/main/java/com/ddpai/uploader/data/config/ConfigRepository.kt`, persist it — in `save(...)` add:
```kotlin
            .putString("syncMode", config.syncMode)
```
and in `load()` add:
```kotlin
        syncMode = securePrefs.getString("syncMode", "PERSISTENT") ?: "PERSISTENT",
```

- [ ] **Step 2: Create `SyncController.kt` (debounced classification + enqueue)**

```kotlin
package com.ddpai.uploader.pipeline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.ddpai.uploader.di.ServiceLocator
import com.ddpai.uploader.network.DashcamNetworkResolver
import com.ddpai.uploader.network.NetworkMonitor
import com.ddpai.uploader.network.NetworkType

/** Central place that classifies the active network and enqueues the right pipeline work. */
class SyncController(private val context: Context, private val sl: ServiceLocator) {
    private var lastType: NetworkType? = null
    private val monitor = NetworkMonitor(context, sl.config) { type, network -> onNetwork(type, network) }

    fun start() = monitor.start()

    fun onNetwork(type: NetworkType, network: Network?) {
        sl.activeNetworkType.value = type
        if (type == lastType) return   // debounce: only act on transitions
        lastType = type
        when (type) {
            NetworkType.DASHCAM_AP -> {
                sl.currentDashcamNetwork = network
                sl.log.i("SyncController", "Dashcam AP detected")
                if (sl.config.config.value.wifiAutoStart) PipelineScheduler.enqueueDownload(context)
            }
            NetworkType.HOME_WIFI -> {
                sl.currentDashcamNetwork = null
                sl.log.i("SyncController", "Home Wi-Fi detected")
                if (sl.config.config.value.wifiAutoStart) PipelineScheduler.enqueueMergeThenUpload(context)
            }
            else -> sl.currentDashcamNetwork = null
        }
    }

    /** Used by SyncCheckWorker (battery-saver): classify current network without a callback. */
    fun classifyAndEnqueue() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val gateway = sl.config.config.value.dashcamGateway
        val dashcam = DashcamNetworkResolver(cm, context).resolve(gateway)
        if (dashcam != null) {
            sl.currentDashcamNetwork = dashcam
            if (sl.config.config.value.wifiAutoStart) PipelineScheduler.enqueueDownload(context)
            return
        }
        val active = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(active) ?: return
        val validated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val wifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        if (wifi && validated && sl.config.config.value.wifiAutoStart) {
            PipelineScheduler.enqueueMergeThenUpload(context)
        }
    }
}
```

- [ ] **Step 3: Create `SyncCheckWorker.kt`**

```kotlin
package com.ddpai.uploader.pipeline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ddpai.uploader.di.ServiceLocator

/** Battery-saver mode: WorkManager wakes this every ~15 min to classify the network and enqueue work. */
class SyncCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val sl = ServiceLocator.get(applicationContext)
        SyncController(applicationContext, sl).classifyAndEnqueue()
        sl.log.d("SyncCheckWorker", "Periodic sync check ran")
        return Result.success()
    }
}
```

- [ ] **Step 4: Add periodic scheduling to `PipelineScheduler`**

In `app/src/main/java/com/ddpai/uploader/pipeline/PipelineScheduler.kt`, add:
```kotlin
    const val PERIODIC_CHECK_WORK = "ddpai_periodic_check"

    fun enablePeriodicChecks(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncCheckWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_CHECK_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun disablePeriodicChecks(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_CHECK_WORK)
    }
```
Add imports at top of the file: `androidx.work.PeriodicWorkRequestBuilder`, `androidx.work.ExistingPeriodicWorkPolicy` (or keep the existing `androidx.work.*` wildcard import if present).

- [ ] **Step 5: Rewire `App.kt` to delegate to `SyncController`**

Replace `app/src/main/java/com/ddpai/uploader/App.kt` with:
```kotlin
package com.ddpai.uploader

import android.app.Application
import com.ddpai.uploader.di.ServiceLocator
import com.ddpai.uploader.pipeline.NotificationHelper
import com.ddpai.uploader.pipeline.PipelineScheduler
import com.ddpai.uploader.pipeline.SyncController
import com.ddpai.uploader.pipeline.WatcherService

class App : Application() {
    lateinit var syncController: SyncController

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        val sl = ServiceLocator.get(this)
        syncController = SyncController(this, sl)
        syncController.start()

        when (sl.config.config.value.syncMode) {
            "BATTERY_SAVER" -> PipelineScheduler.enablePeriodicChecks(this)
            else -> WatcherService.start(this)
        }
    }
}
```

- [ ] **Step 6: Build (WatcherService referenced here is created in Task 8)**

Run: `./gradlew :app:assembleDebug` (after Task 8)
Expected: `BUILD SUCCESSFUL`. If building strictly in order, temporarily comment the `WatcherService.start(this)` line and the import; uncomment after Task 8. Prefer implementing Task 8 next and building once.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/data/config/ app/src/main/java/com/ddpai/uploader/pipeline/SyncController.kt app/src/main/java/com/ddpai/uploader/pipeline/SyncCheckWorker.kt app/src/main/java/com/ddpai/uploader/pipeline/PipelineScheduler.kt app/src/main/java/com/ddpai/uploader/App.kt
git commit -m "feat: SyncController (debounced) + battery-saver periodic worker + syncMode config"
```

---

### Task 8: `WatcherService` (persistent) + `BootReceiver` + manifest

**Files:**
- Create: `app/src/main/java/com/ddpai/uploader/pipeline/WatcherService.kt`
- Create: `app/src/main/java/com/ddpai/uploader/pipeline/BootReceiver.kt`
- Delete: `app/src/main/java/com/ddpai/uploader/pipeline/PipelineForegroundService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `NotificationHelper.CHANNEL`, `SyncController` (via `App`/`ServiceLocator`).
- Produces: `WatcherService` with `companion object { fun start(context); fun stop(context) }`; `BootReceiver` that starts persistent mode after boot.

- [ ] **Step 1: Create `WatcherService.kt`**

```kotlin
package com.ddpai.uploader.pipeline

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Persistent foreground service that keeps the network callback alive so drives are never missed. */
class WatcherService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL)
            .setContentTitle("uDash")
            .setContentText("Watching for dashcam Wi-Fi")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
    }

    companion object {
        private const val NOTIF_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, WatcherService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatcherService::class.java))
        }
    }
}
```

> The service body is deliberately thin: the `NetworkMonitor` callback is owned by the `App`-scoped `SyncController`, which stays alive as long as this foreground service keeps the process resident. The service's job is to keep the process (and thus the callback) from being killed.

- [ ] **Step 2: Create `BootReceiver.kt`**

```kotlin
package com.ddpai.uploader.pipeline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ddpai.uploader.di.ServiceLocator

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val sl = ServiceLocator.get(context)
        when (sl.config.config.value.syncMode) {
            "BATTERY_SAVER" -> PipelineScheduler.enablePeriodicChecks(context)
            else -> WatcherService.start(context)
        }
    }
}
```

- [ ] **Step 3: Delete the obsolete stub**

```bash
git rm app/src/main/java/com/ddpai/uploader/pipeline/PipelineForegroundService.kt
```

- [ ] **Step 4: Update the manifest**

In `app/src/main/AndroidManifest.xml`:

(a) Add permissions alongside the existing `<uses-permission>` block:
```xml
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
```

(b) Replace the old `<service android:name=".pipeline.PipelineForegroundService" .../>` element with:
```xml
        <service
            android:name=".pipeline.WatcherService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="dashcam-wifi-watcher"/>
        </service>

        <receiver
            android:name=".pipeline.BootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED"/>
            </intent-filter>
        </receiver>
```

- [ ] **Step 5: Build the whole app**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all unit tests pass.

- [ ] **Step 6: Manual verification checklist (record in commit body)**

- Persistent mode: launch app → a low-priority "Watching for dashcam Wi-Fi" notification appears. Reboot device → notification returns without opening the app.
- Battery-saver mode: switch in Config (Task 9) → watcher notification disappears; a periodic check is scheduled (verify via `adb shell dumpsys jobscheduler | grep ddpai` or Logs after ≤15 min).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/pipeline/WatcherService.kt app/src/main/java/com/ddpai/uploader/pipeline/BootReceiver.kt app/src/main/AndroidManifest.xml app/src/main/java/com/ddpai/uploader/pipeline/PipelineForegroundService.kt
git commit -m "feat: persistent WatcherService (specialUse) + boot receiver; drop old service stub"
```

---

### Task 9: Sync-mode toggle, merged UI, EXTREME logs, merged upload titles

**Files:**
- Modify: `app/src/main/java/com/ddpai/uploader/youtube/YouTubeUploader.kt`
- Test: `app/src/test/java/com/ddpai/uploader/youtube/UploadTitleTest.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/pipeline/UploadWorker.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/ui/config/ConfigScreen.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/ui/config/ConfigViewModel.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/ui/dashboard/DashboardScreen.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/ui/dashboard/DashboardViewModel.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/ui/files/FileListScreen.kt`
- Modify: `app/src/main/java/com/ddpai/uploader/ui/logs/LogConsoleScreen.kt`

**Interfaces:**
- Produces: `object UploadTitle { fun of(entity: VideoFileEntity): String }` (tested); Config sync-mode switch that applies mode changes; dashboard "Merged" counter + sync-mode label; Files-screen merged/segment display; EXTREME log filter chip.

- [ ] **Step 1: Write the failing title test**

`app/src/test/java/com/ddpai/uploader/youtube/UploadTitleTest.kt`:
```kotlin
package com.ddpai.uploader.youtube

import com.ddpai.uploader.data.db.entity.VideoFileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadTitleTest {
    private fun entity(name: String, kind: String, epoch: Long) = VideoFileEntity(
        fileName = name, remoteUrl = "", localPath = null, status = "DOWNLOADED",
        kind = kind, capturedAtEpoch = epoch
    )

    @Test fun segmentTitleIsFilenameStem() {
        val t = UploadTitle.of(entity("20260626180905_0060.mp4", "SEGMENT", 0L))
        assertEquals("20260626180905_0060", t)
    }

    @Test fun mergedTitleIsHumanReadable() {
        val t = UploadTitle.of(entity("DRIVE_20260626_180905_F.mp4", "MERGED", 0L))
        assertTrue(t.startsWith("Dashcam "))
        assertTrue(t.contains("Front"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ddpai.uploader.youtube.UploadTitleTest"`
Expected: FAIL — unresolved reference `UploadTitle`.

- [ ] **Step 3: Add `UploadTitle` and use it in the uploader**

Create `app/src/main/java/com/ddpai/uploader/youtube/UploadTitle.kt`:
```kotlin
package com.ddpai.uploader.youtube

import com.ddpai.uploader.data.db.entity.VideoFileEntity
import java.text.SimpleDateFormat
import java.util.Locale

object UploadTitle {
    fun of(entity: VideoFileEntity): String {
        if (entity.kind != "MERGED" || !entity.fileName.startsWith("DRIVE_")) {
            return entity.fileName.removeSuffix(".mp4")
        }
        val stream = when {
            entity.fileName.contains("_F") -> "Front"
            entity.fileName.contains("_R") -> "Rear"
            else -> "Cam"
        }
        val stamp = if (entity.capturedAtEpoch > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(entity.capturedAtEpoch))
        } else {
            entity.fileName.removeSuffix(".mp4")
        }
        return "Dashcam $stamp ($stream)"
    }
}
```
In `app/src/main/java/com/ddpai/uploader/pipeline/UploadWorker.kt`, change the `uploadOne` initiate call from:
```kotlin
                sessionUri = uploader.initiate(file, item.fileName.removeSuffix(".mp4"))
```
to:
```kotlin
                sessionUri = uploader.initiate(file, com.ddpai.uploader.youtube.UploadTitle.of(item))
```

- [ ] **Step 4: Run to verify the title test passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ddpai.uploader.youtube.UploadTitleTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Add the sync-mode switch to Config**

In `app/src/main/java/com/ddpai/uploader/ui/config/ConfigViewModel.kt`, add:
```kotlin
    fun applySyncMode(mode: String) {
        val app = getApplication<Application>()
        repo.save(config.value.copy(syncMode = mode))
        if (mode == "BATTERY_SAVER") {
            com.ddpai.uploader.pipeline.WatcherService.stop(app)
            com.ddpai.uploader.pipeline.PipelineScheduler.enablePeriodicChecks(app)
        } else {
            com.ddpai.uploader.pipeline.PipelineScheduler.disablePeriodicChecks(app)
            com.ddpai.uploader.pipeline.WatcherService.start(app)
        }
        logger.i("ConfigVM", "Sync mode → $mode")
    }
```
In `app/src/main/java/com/ddpai/uploader/ui/config/ConfigScreen.kt`, inside the "Connection Settings" card (after the Max Retries row), add:
```kotlin
                var syncMode by remember(configState.syncMode) { mutableStateOf(configState.syncMode) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Persistent background watcher")
                        Text(
                            if (syncMode == "PERSISTENT") "On: instant, shows a permanent notification"
                            else "Off: battery-saver 15-min checks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = syncMode == "PERSISTENT",
                        onCheckedChange = {
                            syncMode = if (it) "PERSISTENT" else "BATTERY_SAVER"
                            vm.applySyncMode(syncMode)
                        }
                    )
                }
```

- [ ] **Step 6: Add merged counter + sync-mode label to the dashboard**

In `app/src/main/java/com/ddpai/uploader/ui/dashboard/DashboardViewModel.kt`:
(a) Add `val merged: Int = 0` and `val syncMode: String = "PERSISTENT"` to `DashUiState`.
(b) Append `sl.files.observeCount(FileStatus.MERGED)` as the LAST argument of `combine(...)`, immediately after `sl.config.runtimeState` (which Plan A added at index 8). Read it as `args[9] as Int` → `merged = ...`. Set `syncMode = sl.config.config.value.syncMode` in the state construction. (Index order must stay: 0 networkType … 7 recentLogs, 8 runtimeState, 9 mergedCount.)
In `app/src/main/java/com/ddpai/uploader/ui/dashboard/DashboardScreen.kt`, in the Counters row replace the `Failed` item cluster so the row shows five counters (wrap to keep weights even):
```kotlin
            CounterItem(label = "Discovered", count = state.discovered, modifier = Modifier.weight(1f))
            CounterItem(label = "Downloaded", count = state.downloaded, modifier = Modifier.weight(1f))
            CounterItem(label = "Merged", count = state.merged, modifier = Modifier.weight(1f))
            CounterItem(label = "Uploaded", count = state.uploaded, modifier = Modifier.weight(1f))
            CounterItem(label = "Failed", count = state.failed, modifier = Modifier.weight(1f))
```
And under the Link Status row add:
```kotlin
                Text(
                    "Sync mode: ${if (state.syncMode == "PERSISTENT") "Persistent watcher" else "Battery saver"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
```

- [ ] **Step 7: Show merged/consumed rows in the Files list**

In `app/src/main/java/com/ddpai/uploader/ui/files/FileListScreen.kt`, inside `VideoFileCard`'s `Column`, after the captured-time `Text`, add:
```kotlin
            if (file.kind == "MERGED") {
                Text("Drive video (merged)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            } else if (file.mergedInto != null) {
                Text("Merged → ${file.mergedInto}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
```
Add `"MERGED"` handling to `StatusChip`'s `when`:
```kotlin
        "MERGED" -> Pair(MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f), "Merged")
```

- [ ] **Step 8: Add the EXTREME filter chip to the log console**

In `app/src/main/java/com/ddpai/uploader/ui/logs/LogConsoleScreen.kt`, change:
```kotlin
    val levels = listOf(null, "DEBUG", "INFO", "WARN", "ERROR")
```
to:
```kotlin
    val levels = listOf(null, "DEBUG", "INFO", "WARN", "ERROR", "EXTREME")
```

- [ ] **Step 9: Full build + tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all unit tests pass including `UploadTitleTest`.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/ddpai/uploader/youtube/UploadTitle.kt app/src/test/java/com/ddpai/uploader/youtube/UploadTitleTest.kt app/src/main/java/com/ddpai/uploader/pipeline/UploadWorker.kt app/src/main/java/com/ddpai/uploader/ui/
git commit -m "feat: sync-mode toggle, merged UI + titles, EXTREME log chip"
```

---

### Task 10: Plan B verification pass

**Files:** none (verification only).

- [ ] **Step 1: Clean build + all tests**

Run: `./gradlew clean :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`. New suites `DriveGrouperTest`, `MergeNamingTest`, `UploadTitleTest` pass alongside all Plan A suites.

- [ ] **Step 2: Migration smoke check (device or emulator)**

Install the Plan A build (schema v1) with at least one row, then install this build over it (no uninstall).
Expected: app launches without an `IllegalStateException` about a missing migration; existing rows still visible in Files; new rows get `kind='SEGMENT'`.

- [ ] **Step 3: Manual end-to-end (device) checklist — record pass/fail in commit body**

1. Drive simulation: place ≥3 valid segments of one stream in the videos dir as DOWNLOADED, wait past the close window, trigger home Wi-Fi → `MergeWorker` produces one `DRIVE_*.mp4`, segment files deleted, merged row uploadable.
2. Play the merged file in-app → duration ≈ sum of segments; audio in sync.
3. Upload runs on the merged file only (no per-segment uploads) and deletes local after success; YouTube title reads `Dashcam <date> (<stream>)`.
4. Single-segment drive → relabeled to MERGED and uploaded as-is (no copy).
5. Persistent mode survives reboot; switch to battery-saver → watcher notification gone, periodic check scheduled.
6. Kill app mid-merge → `.tmp` cleaned next run, segments intact, re-merge succeeds (idempotent).

- [ ] **Step 4: Commit the verification record**

```bash
git commit --allow-empty -m "test: Plan B verification — build green, merge/watcher E2E checklist recorded"
```

---

## Self-Review (Plan B)

- **Spec coverage (Part 3):** Room v2 + MERGED (Task 1); grouping rules (Task 2); output naming (Task 2b); `Mp4Merger` lossless concat (Task 3); DAO/repo transaction + upload gate (Task 4); `MergeWorker` atomic commit + single-segment relabel + failure fallback (Task 5); merge→upload chain (Task 6); merged upload metadata (Task 9). **Spec coverage (Part 4a):** `SyncController` debounce (Task 7), battery-saver periodic (Task 7), persistent `WatcherService` + boot (Task 8), Config toggle (Task 9), old stub deleted (Task 8). **Part 4c leftovers:** EXTREME chip + merged counter (Task 9). **Part 4d:** unit suites across Tasks 2/2b/9; device E2E in Task 10.
- **Placeholder scan:** no TBD/TODO; every code step shows full code; manual-only steps (native merge, service, migration) are explicitly marked as build+device-verified with the reason.
- **Type consistency:** `DriveGrouper.Segment(fileName, capturedAtEpoch, streamKey)` used identically in grouper, naming, and `MergeWorker`. `streamKeyOf` returns `"0060"|"F"|"R"|null`; `MergeNaming` uses `head.streamKey`. `Mp4Merger.Outcome` variants (`Success`/`FormatMismatch`/`Error`) matched exactly in `MergeWorker`. `commitMerge(output, consumedNames)` repo → `commitMerge(output, consumed, ts)` DAO. Upload gate `status='DOWNLOADED' AND kind='MERGED'` matches the kind written by `commitMerge`/`relabelAsMerged`. `syncMode` values `"PERSISTENT"`/`"BATTERY_SAVER"` consistent across `AppConfig`, `App`, `SyncController`, `BootReceiver`, `ConfigViewModel`.
- **Cross-plan dependency:** relies on Plan A's `DashcamNetworkResolver`, `RetryPolicy`, `ServiceLocator.io`, rebuilt `UploadWorker` (whose `uploadOne`/`nextToUpload` this plan narrows to MERGED), and `PipelineScheduler.enqueueUpload(context, delay)`. Confirmed present before starting.
