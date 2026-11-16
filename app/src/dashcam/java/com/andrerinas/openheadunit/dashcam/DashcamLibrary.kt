package com.andrerinas.openheadunit.dashcam

import android.content.Context
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.system.Os
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

data class DashcamClipRecord(
    val id: String,
    val sessionId: String,
    val file: File,
    val sessionStartedAtMs: Long,
    val recordedAtMs: Long,
    val durationMs: Long,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val targetFps: Int,
    val gapBeforeMs: Long,
    val locked: Boolean,
    val exported: Boolean,
    val exporting: Boolean,
    val eventIds: List<String>,
    val eventSummaries: List<DashcamEventSummary>,
    val recovered: Boolean,
    val galleryUri: String? = null
)

data class DashcamRetentionResult(val canRecord: Boolean, val deletedCount: Int)
data class DashcamEventSummary(val id: String, val durationMs: Long, val complete: Boolean)

object DashcamLibrary {
    private const val TRIP_FILE = "trip.json"
    private val processLock = Any()

    fun newRecordingFile(context: Context, sessionId: String, startedAtMs: Long): File =
        File(tripDirectory(context, sessionId), "recording-$startedAtMs-${java.util.UUID.randomUUID()}.mp4")

    fun beginTrip(context: Context, sessionId: String, startedAtMs: Long) = locked(context) {
        val directory = tripDirectory(context, sessionId)
        if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Could not create Dashcam trip")
        writeTrip(File(directory, TRIP_FILE), Trip(sessionId, startedAtMs, null, true, mutableListOf()))
    }

    fun acquireTripLease(context: Context, sessionId: String): Lease {
        val directory = tripDirectory(context, sessionId)
        val access = RandomAccessFile(File(directory, ".recording.lock"), "rw")
        return try {
            Lease(access, access.channel.lock())
        } catch (e: Exception) {
            access.close()
            throw e
        }
    }

    fun acquireExportLease(context: Context, id: String): Lease? = locked(context) {
        val clip = allClips(root(context)).firstOrNull { it.id == id } ?: return@locked null
        val access = RandomAccessFile(File(clip.file.parentFile, ".export-${clip.id}.lock"), "rw")
        try {
            Lease(access, access.channel.lock())
        } catch (e: Exception) {
            access.close()
            throw e
        }
    }

    fun finishTrip(context: Context, sessionId: String, endedAtMs: Long) = locked(context) {
        val tripFile = File(tripDirectory(context, sessionId), TRIP_FILE)
        val trip = readTrip(tripFile) ?: return@locked
        trip.active = false
        trip.endedAtMs = endedAtMs
        trip.events.forEach { event ->
            event.actualEndMs = minOf(event.expectedEndMs, endedAtMs)
        }
        writeTrip(tripFile, trip)
        listClipsIn(tripFile.parentFile).forEach { clip ->
            val summaries = trip.events.filter { it.id in clip.eventIds }.map { it.summary(trip) }
            if (summaries.isNotEmpty()) writeClip(clip.copy(eventSummaries = summaries))
        }
    }

    fun markEvent(context: Context, sessionId: String, markedAtMs: Long): Boolean = locked(context) {
        val directory = tripDirectory(context, sessionId)
        val tripFile = File(directory, TRIP_FILE)
        val trip = readTrip(tripFile) ?: return@locked false
        if (!trip.active && (trip.endedAtMs ?: 0L) < markedAtMs) return@locked false

        val event = Event(java.util.UUID.randomUUID().toString(), markedAtMs, markedAtMs + EVENT_AFTER_MS)
        if (!trip.active) event.actualEndMs = minOf(event.expectedEndMs, trip.endedAtMs ?: markedAtMs)
        trip.events += event
        writeTrip(tripFile, trip)

        listClipsIn(directory).forEach { clip ->
            val window = event.window(trip)
            if (window.overlaps(clip.recordedAtMs, clip.recordedAtMs + clip.durationMs)) {
                val eventIds = (clip.eventIds + event.id).distinct()
                writeClip(clip.copy(
                    locked = true,
                    eventIds = eventIds,
                    eventSummaries = trip.events.filter { it.id in eventIds }.map { it.summary(trip) }
                ))
            }
        }
        true
    }

    fun addFinalizedClip(
        context: Context,
        sessionId: String,
        file: File,
        startedAtMs: Long,
        durationMs: Long,
        width: Int,
        height: Int,
        targetFps: Int,
        gapBeforeMs: Long,
        publishGalleryCopy: Boolean = true
    ): DashcamClipRecord? = locked(context) {
        val directory = tripDirectory(context, sessionId)
        val trip = readTrip(File(directory, TRIP_FILE)) ?: return@locked null
        if (!file.isFile || durationMs <= 0L || width <= 0 || height <= 0) return@locked null
        val end = startedAtMs + durationMs
        val eventIds = trip.events.filter { it.window(trip).overlaps(startedAtMs, end) }.map { it.id }
        val galleryUri = if (publishGalleryCopy) publishToGallery(context, file) else null
        val record = DashcamClipRecord(
            id = file.nameWithoutExtension,
            sessionId = sessionId,
            file = file,
            sessionStartedAtMs = trip.startedAtMs,
            recordedAtMs = startedAtMs,
            durationMs = durationMs,
            bytes = file.length(),
            width = width,
            height = height,
            targetFps = targetFps,
            gapBeforeMs = gapBeforeMs.coerceAtLeast(0L),
            locked = eventIds.isNotEmpty(),
            exported = false,
            exporting = false,
            eventIds = eventIds,
            eventSummaries = trip.events.filter { it.id in eventIds }.map { it.summary(trip) },
            recovered = false,
            galleryUri = galleryUri
        )
        writeClip(record)
        record
    }

    fun enforceRetention(
        context: Context,
        mode: DashcamRetentionMode,
        ageDays: Int,
        maxBytes: Long,
        reserveBytes: Long,
        protectedClipId: String? = null,
        currentFile: File? = null,
        currentRecordingStartedAtMs: Long = 0L,
        currentGalleryCopyBytes: Long = 0L
    ): DashcamRetentionResult = locked(context) {
        val root = root(context)
        if (!root.exists() && !root.mkdirs()) return@locked DashcamRetentionResult(false, 0)
        val clips = allClips(root)
        fun retentionClips(items: List<DashcamClipRecord>) = items.map { clip ->
            DashcamClip(
                id = clip.id,
                recordedAtMs = clip.recordedAtMs,
                bytes = clip.bytes + if (clip.galleryUri != null) clip.bytes else 0L,
                locked = clip.locked,
                event = clip.eventIds.isNotEmpty(),
                exported = clip.exported,
                exporting = clip.exporting,
                current = clip.id == protectedClipId
            )
        } + listOfNotNull(currentFile?.takeIf { it.isFile }?.let { file ->
            DashcamClip(
                id = file.nameWithoutExtension,
                recordedAtMs = currentRecordingStartedAtMs,
                bytes = file.length() + currentGalleryCopyBytes.coerceAtLeast(0L),
                current = true
            )
        })
        val plan = DashcamRetentionPolicy.plan(
            retentionClips(clips),
            mode = mode,
            nowMs = System.currentTimeMillis(),
            ageDays = ageDays,
            maxBytes = maxBytes,
            reserveBytes = reserveBytes,
            freeBytes = StatFs(root.absolutePath).availableBytes
        )
        var deleted = 0
        clips.filter { it.id in plan.deleteClipIds }.forEach { clip ->
            if (deleteGallery(context, clip.galleryUri) && (clip.file.delete() || !clip.file.exists())) {
                sidecar(clip.file).delete()
                deleted++
            }
        }
        val remaining = allClips(root)
        val free = StatFs(root.absolutePath).availableBytes
        val finalPlan = DashcamRetentionPolicy.plan(
            retentionClips(remaining),
            mode, System.currentTimeMillis(), ageDays, maxBytes, reserveBytes, free
        )
        DashcamRetentionResult(canRecord = !finalPlan.stopRecording, deletedCount = deleted)
    }

    fun listClips(context: Context, activeSessionId: String?): List<DashcamClipRecord> = locked(context) {
        recover(context, activeSessionId)
        allClips(root(context)).sortedByDescending { it.recordedAtMs }
    }

    fun setLocked(context: Context, id: String, locked: Boolean): Boolean = updateClip(context, id) {
        it.copy(locked = locked || it.eventIds.isNotEmpty())
    }

    fun setExporting(context: Context, id: String, exporting: Boolean): Boolean = updateClip(context, id) {
        it.copy(exporting = exporting)
    }

    fun setExported(context: Context, id: String): Boolean = updateClip(context, id) {
        it.copy(exported = true, exporting = false)
    }

    fun deleteClip(context: Context, id: String): Boolean = locked(context) {
        val clip = allClips(root(context)).firstOrNull { it.id == id } ?: return@locked false
        if (clip.exporting || clipExportIsLocked(clip)) return@locked false
        val deleted = deleteGallery(context, clip.galleryUri) && (clip.file.delete() || !clip.file.exists())
        if (deleted) sidecar(clip.file).delete()
        deleted
    }

    fun videoInfo(file: File): VideoInfo? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (duration > 0 && width > 0 && height > 0) VideoInfo(duration, width, height) else null
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    private fun publishToGallery(context: Context, file: File): String? {
        // shortcut: Gallery publishing uses scoped MediaStore on API 29+, add legacy storage permission before supporting older devices.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val relativePath = "${Environment.DIRECTORY_MOVIES}/Open Headunit Dashcam/"
        return try {
            val existing = resolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.MediaColumns.IS_PENDING),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(file.name, relativePath),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    Uri.withAppendedPath(collection, cursor.getLong(0).toString()) to cursor.getInt(1)
                } else null
            }
            if (existing != null) {
                if (existing.second == 0) return existing.first.toString()
                resolver.delete(existing.first, null, null)
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.TITLE, file.nameWithoutExtension)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.DATE_TAKEN, file.lastModified())
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("Could not open Gallery video")
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (resolver.update(uri, published, null, null) != 1) throw IllegalStateException("Could not publish Gallery video")
                uri.toString()
            } catch (_: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun deleteGallery(context: Context, rawUri: String?): Boolean {
        if (rawUri == null) return true
        return try {
            val uri = Uri.parse(rawUri)
            if (uri.scheme != "content") false else {
                context.contentResolver.delete(uri, null, null)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun recover(context: Context, activeSessionId: String?) {
        val root = root(context)
        if (!root.exists()) return
        root.listFiles()?.filter { it.isDirectory }?.forEach { directory ->
            val tripFile = File(directory, TRIP_FILE)
            val trip = readTrip(tripFile)
            val recordingLock = File(directory, ".recording.lock")
            val isLive = directory.name == activeSessionId || (recordingLock.exists() && tripIsLocked(directory))
            directory.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }

            directory.listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
                ?.forEach { video ->
                    val metadata = sidecar(video)
                    if (metadata.exists()) {
                        val record = readClip(metadata)
                        if (record == null || !video.exists()) {
                            metadata.delete()
                            if (video.exists() && !isLive) video.delete()
                        } else {
                            val recoveredRecord = if (record.exporting && !clipExportIsLocked(record)) {
                                record.copy(exported = true, exporting = false)
                            } else record
                            val galleryUri = publishToGallery(context, video)
                            val bytes = video.length()
                            if (galleryUri != recoveredRecord.galleryUri || bytes != recoveredRecord.bytes) {
                                writeClip(recoveredRecord.copy(galleryUri = galleryUri, bytes = bytes))
                            }
                        }
                    } else if (!isLive) {
                        val info = videoInfo(video)
                        if (info == null) {
                            video.delete()
                        } else {
                            val startedAt = video.nameWithoutExtension.removePrefix("recording-").removePrefix("segment-")
                                .takeWhile { it.isDigit() }.toLongOrNull() ?: video.lastModified()
                            val eventIds = trip?.let { current ->
                                current.events.filter { event ->
                                    event.window(current).overlaps(startedAt, startedAt + info.durationMs)
                                }.map { it.id }
                            }.orEmpty()
                            val galleryUri = publishToGallery(context, video)
                            val record = DashcamClipRecord(
                                id = video.nameWithoutExtension,
                                sessionId = directory.name,
                                file = video,
                                sessionStartedAtMs = trip?.startedAtMs ?: startedAt,
                                recordedAtMs = startedAt,
                                durationMs = info.durationMs,
                                bytes = video.length(),
                                width = info.width,
                                height = info.height,
                                targetFps = 30,
                                gapBeforeMs = 0,
                                locked = eventIds.isNotEmpty(),
                                exported = false,
                                exporting = false,
                                eventIds = eventIds,
                                eventSummaries = trip?.let { current ->
                                    current.events.filter { it.id in eventIds }.map { it.summary(current) }
                                }.orEmpty(),
                                recovered = true,
                                galleryUri = galleryUri
                            )
                            writeClip(record)
                        }
                    }
                }

            when {
                trip == null -> {
                    val recovered = listClipsIn(directory)
                    if (recovered.isNotEmpty()) {
                        writeTrip(tripFile, Trip(
                            directory.name,
                            recovered.minOf { it.recordedAtMs },
                            recovered.maxOf { it.recordedAtMs + it.durationMs },
                            false,
                            mutableListOf()
                        ))
                    }
                }
                trip.active && !isLive -> {
                    val lastEnd = listClipsIn(directory).maxOfOrNull { it.recordedAtMs + it.durationMs } ?: trip.startedAtMs
                    trip.active = false
                    trip.endedAtMs = lastEnd
                    trip.events.forEach { it.actualEndMs = minOf(it.expectedEndMs, lastEnd) }
                    writeTrip(tripFile, trip)
                listClipsIn(directory).forEach { clip ->
                    val summaries = trip.events.filter { it.id in clip.eventIds }.map { it.summary(trip) }
                    if (summaries.isNotEmpty()) writeClip(clip.copy(eventSummaries = summaries))
                }
                }
            }
        }
    }

    private fun updateClip(context: Context, id: String, change: (DashcamClipRecord) -> DashcamClipRecord): Boolean = locked(context) {
        val clip = allClips(root(context)).firstOrNull { it.id == id } ?: return@locked false
        writeClip(change(clip))
        true
    }

    private fun tripIsLocked(directory: File): Boolean {
        val access = try { RandomAccessFile(File(directory, ".recording.lock"), "rw") } catch (_: Exception) { return false }
        return try {
            val lease = access.channel.tryLock()
            if (lease == null) true else {
                lease.release()
                false
            }
        } catch (_: OverlappingFileLockException) {
            true
        } catch (_: Exception) {
            false
        } finally {
            try { access.close() } catch (_: Exception) { }
        }
    }

    private fun clipExportIsLocked(clip: DashcamClipRecord): Boolean {
        val access = try { RandomAccessFile(File(clip.file.parentFile, ".export-${clip.id}.lock"), "rw") } catch (_: Exception) { return false }
        return try {
            val lease = access.channel.tryLock()
            if (lease == null) true else {
                lease.release()
                false
            }
        } catch (_: OverlappingFileLockException) {
            true
        } catch (_: Exception) {
            false
        } finally {
            try { access.close() } catch (_: Exception) { }
        }
    }

    private fun allClips(root: File): List<DashcamClipRecord> =
        root.listFiles()?.filter { it.isDirectory }?.flatMap(::listClipsIn).orEmpty()

    private fun listClipsIn(directory: File): List<DashcamClipRecord> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".mp4.json") }
            ?.mapNotNull(::readClip).orEmpty()

    private fun root(context: Context): File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
        "OpenHeadunitDashcam"
    )

    private fun tripDirectory(context: Context, sessionId: String) = File(root(context), sessionId)

    private fun sidecar(video: File) = File(video.parentFile, video.name + ".json")

    private fun writeTrip(file: File, trip: Trip) {
        val events = JSONArray()
        trip.events.forEach { event ->
            events.put(JSONObject().put("id", event.id).put("markedAtMs", event.markedAtMs)
                .put("actualEndMs", event.actualEndMs ?: JSONObject.NULL))
        }
        atomicJson(file, JSONObject().put("id", trip.id).put("startedAtMs", trip.startedAtMs)
            .put("endedAtMs", trip.endedAtMs ?: JSONObject.NULL).put("active", trip.active).put("events", events))
    }

    private fun readTrip(file: File): Trip? {
        return try {
            if (!file.isFile) return null
            val json = JSONObject(file.readText())
            val events = mutableListOf<Event>()
            val array = json.optJSONArray("events") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val actualEnd = item.opt("actualEndMs").let { if (it == JSONObject.NULL) null else (it as? Number)?.toLong() }
                val marked = item.getLong("markedAtMs")
                events += Event(item.getString("id"), marked, marked + EVENT_AFTER_MS, actualEnd)
            }
            val end = json.opt("endedAtMs").let { if (it == JSONObject.NULL) null else (it as? Number)?.toLong() }
            Trip(json.getString("id"), json.getLong("startedAtMs"), end, json.optBoolean("active"), events)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeClip(clip: DashcamClipRecord) {
        val ids = JSONArray()
        clip.eventIds.forEach(ids::put)
        val events = JSONArray()
        clip.eventSummaries.forEach { event ->
            events.put(JSONObject().put("id", event.id).put("durationMs", event.durationMs).put("complete", event.complete))
        }
        atomicJson(sidecar(clip.file), JSONObject()
            .put("id", clip.id).put("sessionId", clip.sessionId).put("file", clip.file.name)
            .put("sessionStartedAtMs", clip.sessionStartedAtMs)
            .put("recordedAtMs", clip.recordedAtMs).put("durationMs", clip.durationMs).put("bytes", clip.bytes)
            .put("width", clip.width).put("height", clip.height).put("targetFps", clip.targetFps)
            .put("gapBeforeMs", clip.gapBeforeMs).put("locked", clip.locked).put("exported", clip.exported)
            .put("exporting", clip.exporting).put("eventIds", ids).put("events", events).put("recovered", clip.recovered)
            .put("galleryUri", clip.galleryUri ?: JSONObject.NULL))
    }

    private fun readClip(file: File): DashcamClipRecord? {
        return try {
            val json = JSONObject(file.readText())
            val video = File(file.parentFile, json.getString("file"))
            if (video.parentFile.canonicalFile != file.parentFile.canonicalFile || !video.isFile) return null
            val eventIdsJson = json.optJSONArray("eventIds") ?: JSONArray()
            val eventIds = (0 until eventIdsJson.length()).mapNotNull { index ->
                eventIdsJson.optString(index).takeIf { it.isNotBlank() }
            }
            val eventJson = json.optJSONArray("events") ?: JSONArray()
            val eventSummaries = (0 until eventJson.length()).mapNotNull { index ->
                eventJson.optJSONObject(index)?.let { event ->
                    DashcamEventSummary(event.getString("id"), event.optLong("durationMs"), event.optBoolean("complete"))
                }
            }
            DashcamClipRecord(
                id = json.getString("id"), sessionId = json.getString("sessionId"), file = video,
                sessionStartedAtMs = json.optLong("sessionStartedAtMs", json.optLong("recordedAtMs")),
                recordedAtMs = json.getLong("recordedAtMs"), durationMs = json.getLong("durationMs"),
                bytes = video.length(), width = json.optInt("width"), height = json.optInt("height"),
                targetFps = json.optInt("targetFps", 30), gapBeforeMs = json.optLong("gapBeforeMs"),
                locked = json.optBoolean("locked") || eventIds.isNotEmpty(), exported = json.optBoolean("exported"),
                exporting = json.optBoolean("exporting"), eventIds = eventIds,
                eventSummaries = eventSummaries, recovered = json.optBoolean("recovered"),
                galleryUri = json.optString("galleryUri").takeIf { it.isNotBlank() && it != "null" }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun atomicJson(file: File, json: JSONObject) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Os.rename(temporary.absolutePath, file.absolutePath)
    }

    private inline fun <T> locked(context: Context, block: () -> T): T {
        val directory = root(context)
        if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Dashcam storage unavailable")
        synchronized(processLock) {
            RandomAccessFile(File(directory, ".library.lock"), "rw").use { access ->
                access.channel.lock().use { return block() }
            }
        }
    }

    private data class Trip(
        val id: String,
        val startedAtMs: Long,
        var endedAtMs: Long?,
        var active: Boolean,
        val events: MutableList<Event>
    )

    private data class Event(
        val id: String,
        val markedAtMs: Long,
        val expectedEndMs: Long,
        var actualEndMs: Long? = null
    ) {
        fun window(trip: Trip) = DashcamEventWindow.forSession(markedAtMs, trip.startedAtMs, actualEndMs ?: trip.endedAtMs)
        fun summary(trip: Trip) = DashcamEventSummary(id, window(trip).durationMs, actualEndMs != null || trip.endedAtMs != null)
    }

    data class VideoInfo(val durationMs: Long, val width: Int, val height: Int)

    class Lease internal constructor(private val access: RandomAccessFile, private val lease: FileLock) : AutoCloseable {
        override fun close() {
            try { lease.release() } finally { access.close() }
        }
    }

    private const val EVENT_AFTER_MS = 30_000L
}
