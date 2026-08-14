package io.remotestudy.camera

import java.io.File

data class CaptureAssets(
    val assetId: String,
    val capturedAtEpochMs: Long,
    val thumbnailFile: File,
    val bookRoiFile: File,
)

/**
 * Differences are normalized to 0f..1f. A null value means that no comparison
 * frame exists yet (presence was not armed or this is the first book frame).
 */
data class FrameObservation(
    val observedAtElapsedMs: Long,
    val presenceDifference: Float?,
    val bookMovement: Float?,
)

internal object CameraRegions {
    // The lower control card covers the bottom of the preview. Both regions stay
    // fully visible above it so calibration cannot silently hide presence input.
    val BOOK = NormalizedRegion(left = 0.07f, top = 0.30f, right = 0.93f, bottom = 0.70f)
    val PRESENCE = NormalizedRegion(left = 0.55f, top = 0.14f, right = 0.94f, bottom = 0.27f)
}
