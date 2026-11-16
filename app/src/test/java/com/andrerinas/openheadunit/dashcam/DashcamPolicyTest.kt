package com.andrerinas.openheadunit.dashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashcamPolicyTest {
    @Test
    fun chargingWithoutProjectionDoesNotStartRecording() {
        assertFalse(DashcamProjectionPolicy.shouldStart(enabled = true, projecting = false, cameraGranted = true))
        assertFalse(DashcamProjectionPolicy.shouldStart(enabled = false, projecting = true, cameraGranted = true))
        assertFalse(DashcamProjectionPolicy.shouldStart(enabled = true, projecting = true, cameraGranted = false))
        assertTrue(DashcamProjectionPolicy.shouldStart(enabled = true, projecting = true, cameraGranted = true))
    }

    @Test
    fun dashcamDeepLinksDoNotCollideWithTheOriginalApp() {
        assertTrue(DashcamLinkPolicy.accepts("headunit", isDashcam = false))
        assertFalse(DashcamLinkPolicy.accepts("headunit-dashcam", isDashcam = false))
        assertTrue(DashcamLinkPolicy.accepts("headunit-dashcam", isDashcam = true))
        assertFalse(DashcamLinkPolicy.accepts("headunit", isDashcam = true))
    }

    @Test
    fun firstRunSetupDefersAutomaticConnectionOnlyForDashcam() {
        assertTrue(DashcamSetupPolicy.shouldDeferAutomaticConnection(isDashcamBuild = true, setupComplete = false))
        assertFalse(DashcamSetupPolicy.shouldDeferAutomaticConnection(isDashcamBuild = true, setupComplete = true))
        assertFalse(DashcamSetupPolicy.shouldDeferAutomaticConnection(isDashcamBuild = false, setupComplete = false))
    }

    @Test
    fun oneProjectionStartsOnlyOneRecordingAndDisconnectFinalizesIt() {
        val policy = DashcamSessionPolicy()

        assertEquals("trip-a", policy.start("trip-a").startSessionId)
        assertTrue(policy.start("trip-a").isEmpty)
        assertEquals("trip-a", policy.stop("trip-a").stopSessionId)
        assertTrue(policy.finalized("trip-a").isEmpty)
    }

    @Test
    fun quickReconnectWaitsForOldFileToFinalize() {
        val policy = DashcamSessionPolicy()
        policy.start("trip-a")
        assertEquals("trip-a", policy.stop("trip-a").stopSessionId)

        assertTrue(policy.start("trip-b").isEmpty)
        assertEquals("trip-b", policy.finalized("trip-a").startSessionId)
        assertEquals("trip-b", policy.stop("trip-b").stopSessionId)
    }

    @Test
    fun reconnectThatEndsBeforeFinalizeIsNotStarted() {
        val policy = DashcamSessionPolicy()
        policy.start("trip-a")
        policy.stop("trip-a")
        policy.start("trip-b")

        assertTrue(policy.stop("trip-b").isEmpty)
        assertTrue(policy.finalized("trip-a").isEmpty)
    }

    @Test
    fun staleDisconnectCannotStopTheNewProjectionSession() {
        val policy = DashcamSessionPolicy()
        policy.start("trip-a")
        policy.stop("trip-a")
        policy.start("trip-b")
        policy.finalized("trip-a")

        assertTrue(policy.stop("trip-a").isEmpty)
        assertEquals("trip-b", policy.currentSessionId)
    }

    @Test
    fun eventBeforeDisconnectLocksTheActualAvailableWindow() {
        val window = DashcamEventWindow.forSession(
            markedAtMs = 45_000,
            sessionStartedAtMs = 0,
            sessionEndedAtMs = 50_000
        )

        assertEquals(15_000L, window.startMs)
        assertEquals(50_000L, window.endMs)
        assertTrue(window.overlaps(0, 30_000))
        assertTrue(window.overlaps(30_000, 60_000))
        assertEquals(35_000L, window.durationMs)
    }

    @Test
    fun ageRetentionDeletesOnlyExpiredOrdinaryClips() {
        val now = 100L * DashcamRetentionPolicy.DAY_MS
        val clips = listOf(
            DashcamClip("expired", recordedAtMs = now - 8 * DashcamRetentionPolicy.DAY_MS, bytes = 20),
            DashcamClip("fresh", recordedAtMs = now - 2 * DashcamRetentionPolicy.DAY_MS, bytes = 30),
            DashcamClip("locked", recordedAtMs = now - 9 * DashcamRetentionPolicy.DAY_MS, bytes = 40, locked = true),
            DashcamClip("event", recordedAtMs = now - 10 * DashcamRetentionPolicy.DAY_MS, bytes = 50, event = true),
            DashcamClip("exported", recordedAtMs = now - 11 * DashcamRetentionPolicy.DAY_MS, bytes = 60, exported = true),
            DashcamClip("current", recordedAtMs = now - 12 * DashcamRetentionPolicy.DAY_MS, bytes = 70, current = true)
        )

        val plan = DashcamRetentionPolicy.plan(
            clips, DashcamRetentionMode.AGE, now,
            ageDays = 7, maxBytes = Long.MAX_VALUE, reserveBytes = 0, freeBytes = Long.MAX_VALUE
        )

        assertEquals(listOf("expired"), plan.deleteClipIds)
        assertFalse(plan.stopRecording)
    }

    @Test
    fun smartRetentionDeletesOldestOnlyUntilQuotaAndReserveAreMet() {
        val clips = listOf(
            DashcamClip("oldest", recordedAtMs = 1, bytes = 25),
            DashcamClip("next", recordedAtMs = 2, bytes = 20),
            DashcamClip("newest", recordedAtMs = 3, bytes = 55)
        )

        val plan = DashcamRetentionPolicy.plan(
            clips, DashcamRetentionMode.SMART, nowMs = 4,
            ageDays = 7, maxBytes = 75, reserveBytes = 10, freeBytes = 10
        )

        assertEquals(listOf("oldest"), plan.deleteClipIds)
        assertFalse(plan.stopRecording)
    }

    @Test
    fun combinedRetentionAppliesAgeFirstThenSmartLimit() {
        val now = 100L * DashcamRetentionPolicy.DAY_MS
        val clips = listOf(
            DashcamClip("expired", recordedAtMs = now - 8 * DashcamRetentionPolicy.DAY_MS, bytes = 20),
            DashcamClip("fresh-oldest", recordedAtMs = now - 2 * DashcamRetentionPolicy.DAY_MS, bytes = 20),
            DashcamClip("fresh-newest", recordedAtMs = now - DashcamRetentionPolicy.DAY_MS, bytes = 60)
        )

        val plan = DashcamRetentionPolicy.plan(
            clips, DashcamRetentionMode.COMBINED, now,
            ageDays = 7, maxBytes = 70, reserveBytes = 0, freeBytes = Long.MAX_VALUE
        )

        assertEquals(listOf("expired", "fresh-oldest"), plan.deleteClipIds)
        assertFalse(plan.stopRecording)
    }

    @Test
    fun protectedClipsAreNeverDeletedAndRecordingStopsWhenSpaceCannotBeRecovered() {
        val clips = listOf(
            DashcamClip("locked", recordedAtMs = 1, bytes = 100, locked = true),
            DashcamClip("event", recordedAtMs = 2, bytes = 100, event = true),
            DashcamClip("exporting", recordedAtMs = 3, bytes = 100, exporting = true)
        )

        val plan = DashcamRetentionPolicy.plan(
            clips, DashcamRetentionMode.SMART, nowMs = 4,
            ageDays = 7, maxBytes = 100, reserveBytes = 10, freeBytes = 0
        )

        assertTrue(plan.deleteClipIds.isEmpty())
        assertTrue(plan.stopRecording)
    }

    @Test
    fun ageModeDoesNotDeleteFreshClipsWhenSpaceIsLow() {
        val plan = DashcamRetentionPolicy.plan(
            listOf(DashcamClip("fresh", recordedAtMs = 99, bytes = 100)),
            DashcamRetentionMode.AGE, nowMs = 100,
            ageDays = 7, maxBytes = Long.MAX_VALUE, reserveBytes = 10, freeBytes = 0
        )

        assertTrue(plan.deleteClipIds.isEmpty())
        assertTrue(plan.stopRecording)
    }

    @Test
    fun segmentLengthDefaultsToThreeMinutesAndAllowsOnlyOneThreeOrFive() {
        assertEquals(listOf(1, 3, 5), DashcamSegmentPolicy.ALLOWED_MINUTES)
        assertEquals(3, DashcamSegmentPolicy.normalizeMinutes(0))
        assertEquals(3, DashcamSegmentPolicy.normalizeMinutes(2))
        assertEquals(5, DashcamSegmentPolicy.normalizeMinutes(5))
        assertEquals(60_000L, DashcamSegmentPolicy.durationMs(1))
        assertEquals(180_000L, DashcamSegmentPolicy.durationMs(3))
        assertEquals(300_000L, DashcamSegmentPolicy.durationMs(5))
    }

    @Test
    fun segmentRotationUsesCameraXRecordedDurationThreshold() {
        assertFalse(DashcamSegmentPolicy.shouldRotate(59_999_000_000L, 1))
        assertTrue(DashcamSegmentPolicy.shouldRotate(60_000_000_000L, 1))
        assertFalse(DashcamSegmentPolicy.shouldRotate(179_999_000_000L, 3))
        assertTrue(DashcamSegmentPolicy.shouldRotate(180_000_000_000L, 3))
    }

    @Test
    fun interSegmentGapIsMeasuredAndNeverNegative() {
        assertEquals(0L, DashcamSegmentPolicy.gapBeforeMs(null, 10_000))
        assertEquals(250L, DashcamSegmentPolicy.gapBeforeMs(10_000, 10_250))
        assertEquals(0L, DashcamSegmentPolicy.gapBeforeMs(10_250, 10_000))
    }
}
