package io.remotestudy.student

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraTargetPolicyTest {
    @Test fun choosesS23LikeThreeXPhysicalLens() {
        val selected = CameraTargetPolicy.chooseThreeX(
            listOf(
                PhysicalLensTarget("ultra-wide", 0.6f),
                PhysicalLensTarget("main", 1f),
                PhysicalLensTarget("tele-3x", 3f),
                PhysicalLensTarget("tele-10x", 10f),
            ),
        )
        assertEquals(PhysicalLensTarget("tele-3x", 3f), selected)
    }

    @Test fun estimatesS23LikeFieldOfViewFromSensorAndFocalLength() {
        val estimated = CameraTargetPolicy.estimateZoomRatios(
            listOf(
                PhysicalLensOptics("ultra-wide", 2.2f, 6.4f, 24f),
                PhysicalLensOptics("main", 6.3f, 9.6f, 69f),
                PhysicalLensOptics("tele-3x", 7.9f, 3.6f, 10f),
                PhysicalLensOptics("tele-10x", 27.2f, 3.6f, 10f),
            ),
        )
        assertEquals("tele-3x", CameraTargetPolicy.chooseThreeX(estimated)?.cameraId)
        assertEquals(1f, estimated.single { it.cameraId == "main" }.zoomRatio, 0.001f)
        assertEquals(3.34f, estimated.single { it.cameraId == "tele-3x" }.zoomRatio, 0.05f)
    }

    @Test fun selectionIsDeterministicForEqualDistance() {
        val first = PhysicalLensTarget("b", 2.5f)
        val second = PhysicalLensTarget("a", 3.5f)
        assertEquals(second, CameraTargetPolicy.chooseThreeX(listOf(first, second)))
        assertEquals(second, CameraTargetPolicy.chooseThreeX(listOf(second, first)))
    }

    @Test fun acceptsToleranceBoundaries() {
        assertEquals(
            PhysicalLensTarget("low", 2.4f),
            CameraTargetPolicy.chooseThreeX(listOf(PhysicalLensTarget("low", 2.4f))),
        )
        assertEquals(
            PhysicalLensTarget("high", 3.8f),
            CameraTargetPolicy.chooseThreeX(listOf(PhysicalLensTarget("high", 3.8f))),
        )
    }

    @Test fun rejectsUnknownOrUnrelatedLenses() {
        assertNull(
            CameraTargetPolicy.chooseThreeX(
                listOf(
                    PhysicalLensTarget("unknown", Float.NaN),
                    PhysicalLensTarget("main", 1f),
                    PhysicalLensTarget("long", 10f),
                ),
            ),
        )
        assertNull(CameraTargetPolicy.chooseThreeX(emptyList()))
    }

    @Test fun verifiesRequestedPhysicalIdFromCaptureMetadata() {
        val observation = PhysicalCaptureObservation(
            activePhysicalId = "main",
            physicalResultIds = setOf("tele-3x"),
            captureResultFocalLengthMm = 5.4f,
            exifFocalLengthMm = 5.4f,
        )
        assertEquals(true, CameraTargetPolicy.verifiesPhysicalCapture("tele-3x", 7f, observation))
    }

    @Test fun verifiesExpectedFocalLengthWhenPhysicalIdIsUnavailable() {
        val observation = PhysicalCaptureObservation(
            activePhysicalId = null,
            physicalResultIds = emptySet(),
            captureResultFocalLengthMm = null,
            exifFocalLengthMm = 7.1f,
        )
        assertEquals(true, CameraTargetPolicy.verifiesPhysicalCapture("6", 7f, observation))
    }

    @Test fun rejectsWideCaptureClaimingToBeTelephoto() {
        val observation = PhysicalCaptureObservation(
            activePhysicalId = "5",
            physicalResultIds = setOf("5"),
            captureResultFocalLengthMm = 5.4f,
            exifFocalLengthMm = 5.4f,
        )
        assertEquals(false, CameraTargetPolicy.verifiesPhysicalCapture("6", 7f, observation))
    }
}
