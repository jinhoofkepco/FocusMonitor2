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
    val presenceMotion: Float?,
    val bookMovement: Float?,
)

enum class DetailCaptureMode {
    STANDARD_12_MP,
    ULTRA_50_MP,
}

data class CameraProfileResult(
    val requestedMode: DetailCaptureMode,
    val appliedMode: DetailCaptureMode,
    val width: Int,
    val height: Int,
    val ultra50MpAvailable: Boolean,
)

/** Upright preview coordinates. Values are normalized so the region survives rotation. */
data class BookRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && right in 0f..1f && right - left >= 0.12f)
        require(top in 0f..1f && bottom in 0f..1f && bottom - top >= 0.12f)
    }

    internal fun normalized() = NormalizedRegion(left, top, right, bottom)

    companion object {
        val DEFAULT = BookRegion(0.07f, 0.30f, 0.93f, 0.70f)
    }
}

internal object CameraRegions {
    // The lower control card covers the bottom of the preview. Both regions stay
    // fully visible above it so calibration cannot silently hide presence input.
    val BOOK = BookRegion.DEFAULT.normalized()
    val PRESENCE = NormalizedRegion(left = 0.05f, top = 0.05f, right = 0.95f, bottom = 0.48f)
}
