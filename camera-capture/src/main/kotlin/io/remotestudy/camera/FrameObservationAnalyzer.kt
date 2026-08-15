package io.remotestudy.camera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean

internal class FrameObservationAnalyzer(
    private val onObservation: (FrameObservation) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val baselineRequested = AtomicBoolean(false)

    @Volatile
    private var closed = false

    @Volatile
    private var suspended = false

    private var lastAnalyzedAtElapsedMs: Long? = null
    private var presenceBaseline: FloatArray? = null
    private var previousPresenceSignature: FloatArray? = null
    private var previousBookSignature: FloatArray? = null

    @Volatile
    private var bookRegion: NormalizedRegion = CameraRegions.BOOK

    fun setBookRegion(region: BookRegion) {
        bookRegion = region.normalized()
        previousBookSignature = null
    }

    fun armPresenceBaseline() {
        if (!closed) baselineRequested.set(true)
    }

    fun setSuspended(value: Boolean) {
        suspended = value
        if (!value) {
            lastAnalyzedAtElapsedMs = null
            previousPresenceSignature = null
            previousBookSignature = null
        }
    }

    fun reset() {
        lastAnalyzedAtElapsedMs = null
        presenceBaseline = null
        previousPresenceSignature = null
        previousBookSignature = null
        baselineRequested.set(false)
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (closed || suspended) return

            val observedAt = SystemClock.elapsedRealtime()
            val previousObservedAt = lastAnalyzedAtElapsedMs
            if (previousObservedAt != null && observedAt - previousObservedAt < ANALYSIS_INTERVAL_MS) {
                return
            }
            lastAnalyzedAtElapsedMs = observedAt

            val lumaPlane = image.planes.firstOrNull() ?: return
            val sourceCrop = image.cropRect.let { crop ->
                PixelRegion(crop.left, crop.top, crop.right, crop.bottom)
                    .requireInside(image.width, image.height)
            }
            val sourceBookRegion = bookRegion.inSourceCrop(
                sourceCrop = sourceCrop,
                rotationDegrees = image.imageInfo.rotationDegrees,
            )
            val sourcePresenceRegion = CameraRegions.PRESENCE.inSourceCrop(
                sourceCrop = sourceCrop,
                rotationDegrees = image.imageInfo.rotationDegrees,
            )
            val bookSignature = LuminanceSignatureExtractor.extract(
                luma = lumaPlane.buffer,
                width = image.width,
                height = image.height,
                rowStride = lumaPlane.rowStride,
                pixelStride = lumaPlane.pixelStride,
                region = sourceBookRegion,
            )
            val presenceSignature = LuminanceSignatureExtractor.extract(
                luma = lumaPlane.buffer,
                width = image.width,
                height = image.height,
                rowStride = lumaPlane.rowStride,
                pixelStride = lumaPlane.pixelStride,
                region = sourcePresenceRegion,
            )

            if (baselineRequested.getAndSet(false)) {
                presenceBaseline = presenceSignature.copyOf()
            }
            val presenceDifference = presenceBaseline?.let {
                LuminanceSignatureComparator.meanAbsoluteDifference(it, presenceSignature)
            }
            val presenceMotion = previousPresenceSignature?.let {
                LuminanceSignatureComparator.meanAbsoluteDifference(it, presenceSignature)
            }
            val bookMovement = previousBookSignature?.let {
                LuminanceSignatureComparator.meanAbsoluteDifference(it, bookSignature)
            }
            previousBookSignature = bookSignature
            previousPresenceSignature = presenceSignature

            onObservation(
                FrameObservation(
                    observedAtElapsedMs = observedAt,
                    presenceDifference = presenceDifference,
                    presenceMotion = presenceMotion,
                    bookMovement = bookMovement,
                ),
            )
        } finally {
            image.close()
        }
    }

    override fun close() {
        closed = true
        baselineRequested.set(false)
        presenceBaseline = null
        previousPresenceSignature = null
        previousBookSignature = null
    }

    companion object {
        private const val ANALYSIS_INTERVAL_MS = 1_000L
    }
}
