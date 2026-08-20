package io.remotestudy.student

import kotlin.math.abs

internal data class PhysicalLensTarget(
    val cameraId: String,
    val zoomRatio: Float,
)

internal data class PhysicalLensOptics(
    val cameraId: String,
    val focalLengthMm: Float,
    val sensorWidthMm: Float,
    val sensorAreaMm2: Float,
)

/** Pure selection policy. Hardware discovery stays in StudentStudyService. */
internal object CameraTargetPolicy {
    fun estimateZoomRatios(lenses: Collection<PhysicalLensOptics>): List<PhysicalLensTarget> {
        val baseline = lenses.asSequence()
            .filter { it.sensorAreaMm2.isFinite() && it.sensorAreaMm2 > 0f }
            .filter { opticalPower(it).isFinite() && opticalPower(it) > 0f }
            .sortedWith(compareByDescending<PhysicalLensOptics> { it.sensorAreaMm2 }.thenBy { it.cameraId })
            .firstOrNull()
        val baselinePower = baseline?.let(::opticalPower)
        return lenses.map { lens ->
            val ratio = baselinePower
                ?.takeIf { it.isFinite() && it > 0f }
                ?.let { opticalPower(lens) / it }
                ?: Float.NaN
            PhysicalLensTarget(lens.cameraId, ratio)
        }
    }

    fun chooseThreeX(lenses: Collection<PhysicalLensTarget>): PhysicalLensTarget? = lenses
        .asSequence()
        .filter { it.zoomRatio.isFinite() }
        .filter { it.zoomRatio in MIN_THREE_X_RATIO..MAX_THREE_X_RATIO }
        .sortedWith(
            compareBy<PhysicalLensTarget> { abs(it.zoomRatio - TARGET_RATIO) }
                .thenBy(PhysicalLensTarget::cameraId),
        )
        .firstOrNull()

    private fun opticalPower(lens: PhysicalLensOptics): Float =
        lens.focalLengthMm / lens.sensorWidthMm

    private const val TARGET_RATIO = 3f
    private const val MIN_THREE_X_RATIO = 2.4f
    private const val MAX_THREE_X_RATIO = 3.8f
}
