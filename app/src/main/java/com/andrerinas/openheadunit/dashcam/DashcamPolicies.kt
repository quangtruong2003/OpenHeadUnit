package com.andrerinas.openheadunit.dashcam

object DashcamProjectionPolicy {
    fun shouldStart(enabled: Boolean, projecting: Boolean, cameraGranted: Boolean): Boolean =
        enabled && projecting && cameraGranted
}

data class DashcamSessionCommand(
    val stopSessionId: String? = null,
    val startSessionId: String? = null
) {
    val isEmpty: Boolean get() = stopSessionId == null && startSessionId == null
}

class DashcamSessionPolicy {
    var currentSessionId: String? = null
        private set
    var pendingSessionId: String? = null
        private set
    private var finalizing = false

    fun start(sessionId: String): DashcamSessionCommand {
        if (currentSessionId == sessionId) return DashcamSessionCommand()
        pendingSessionId = sessionId
        if (currentSessionId == null) return startPending()
        if (finalizing) return DashcamSessionCommand()

        finalizing = true
        return DashcamSessionCommand(stopSessionId = currentSessionId)
    }

    fun stop(sessionId: String): DashcamSessionCommand {
        if (pendingSessionId == sessionId) pendingSessionId = null
        if (currentSessionId != sessionId || finalizing) return DashcamSessionCommand()

        finalizing = true
        return DashcamSessionCommand(stopSessionId = sessionId)
    }

    fun rotate(sessionId: String): DashcamSessionCommand {
        if (currentSessionId != sessionId || finalizing) return DashcamSessionCommand()

        pendingSessionId = sessionId
        finalizing = true
        return DashcamSessionCommand(stopSessionId = sessionId)
    }

    fun finalized(sessionId: String): DashcamSessionCommand {
        if (currentSessionId != sessionId || !finalizing) return DashcamSessionCommand()

        currentSessionId = null
        finalizing = false
        return startPending()
    }

    fun isRecording(sessionId: String): Boolean = currentSessionId == sessionId && !finalizing

    private fun startPending(): DashcamSessionCommand {
        val next = pendingSessionId ?: return DashcamSessionCommand()
        pendingSessionId = null
        currentSessionId = next
        return DashcamSessionCommand(startSessionId = next)
    }
}

enum class DashcamRetentionMode { AGE, SMART, COMBINED }

data class DashcamClip(
    val id: String,
    val recordedAtMs: Long,
    val bytes: Long,
    val locked: Boolean = false,
    val event: Boolean = false,
    val exported: Boolean = false,
    val exporting: Boolean = false,
    val current: Boolean = false
)

data class DashcamRetentionPlan(val deleteClipIds: List<String>, val stopRecording: Boolean)

object DashcamRetentionPolicy {
    const val DAY_MS = 86_400_000L

    fun plan(
        clips: List<DashcamClip>,
        mode: DashcamRetentionMode,
        nowMs: Long,
        ageDays: Int,
        maxBytes: Long,
        reserveBytes: Long,
        freeBytes: Long
    ): DashcamRetentionPlan {
        val protected = clips.filter { it.locked || it.event || it.exported || it.exporting || it.current }
            .mapTo(HashSet()) { it.id }
        val candidates = clips.asSequence()
            .filterNot { it.id in protected }
            .sortedWith(compareBy<DashcamClip> { it.recordedAtMs }.thenBy { it.id })
            .toList()
        val delete = LinkedHashSet<String>()
        val bytesById = clips.associate { it.id to it.bytes.coerceAtLeast(0L) }
        val ageMs = ageDays.coerceIn(1, 36_500).toLong() * DAY_MS
        val cutoff = nowMs - ageMs

        if (mode != DashcamRetentionMode.SMART) {
            candidates.filter { it.recordedAtMs < cutoff }.forEach { delete += it.id }
        }

        if (mode != DashcamRetentionMode.AGE) {
            val total = sumBytes(clips.filterNot { it.id in delete }, bytesById)
            val alreadyDeleted = sumBytes(clips.filter { it.id in delete }, bytesById)
            val freeAfterExpiry = saturatedAdd(freeBytes.coerceAtLeast(0L), alreadyDeleted)
            val needed = maxOf(
                (total - maxBytes.coerceAtLeast(0L)).coerceAtLeast(0L),
                (reserveBytes.coerceAtLeast(0L) - freeAfterExpiry).coerceAtLeast(0L)
            )
            var deletedForPressure = 0L
            for (clip in candidates) {
                if (delete.contains(clip.id) || deletedForPressure >= needed) continue
                delete += clip.id
                deletedForPressure = saturatedAdd(deletedForPressure, bytesById.getValue(clip.id))
            }
        }

        val deletedBytes = sumBytes(clips.filter { it.id in delete }, bytesById)
        val remainingBytes = sumBytes(clips.filterNot { it.id in delete }, bytesById)
        val remainingFree = saturatedAdd(freeBytes.coerceAtLeast(0L), deletedBytes)
        val stop = remainingFree < reserveBytes.coerceAtLeast(0L) ||
            (mode != DashcamRetentionMode.AGE && remainingBytes > maxBytes.coerceAtLeast(0L))
        return DashcamRetentionPlan(delete.toList(), stop)
    }

    private fun sumBytes(clips: List<DashcamClip>, bytesById: Map<String, Long>): Long =
        clips.fold(0L) { total, clip -> saturatedAdd(total, bytesById.getValue(clip.id)) }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}

data class DashcamEventWindow(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    fun overlaps(clipStartMs: Long, clipEndMs: Long): Boolean =
        endMs > startMs && clipStartMs < endMs && startMs < clipEndMs

    companion object {
        private const val EVENT_PADDING_MS = 30_000L

        fun forSession(markedAtMs: Long, sessionStartedAtMs: Long, sessionEndedAtMs: Long?): DashcamEventWindow {
            val start = maxOf(sessionStartedAtMs, markedAtMs - EVENT_PADDING_MS)
            val expectedEnd = if (Long.MAX_VALUE - markedAtMs < EVENT_PADDING_MS) Long.MAX_VALUE
                else markedAtMs + EVENT_PADDING_MS
            val end = minOf(expectedEnd, sessionEndedAtMs ?: expectedEnd)
            return DashcamEventWindow(startMs = minOf(start, end), endMs = end)
        }
    }
}
