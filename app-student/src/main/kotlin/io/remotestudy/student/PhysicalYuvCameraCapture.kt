package io.remotestudy.student

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot Camera2 path used only by the physical-lens diagnostic.
 *
 * CameraX ImageCapture/JPEG can silently route a physical camera request back to the logical
 * main lens on some Samsung devices. This path opens the logical camera, assigns a YUV output
 * to the requested physical camera, and also includes that ID in createCaptureRequest().
 */
internal class PhysicalYuvCameraCapture(
    context: Context,
    private val callbackExecutor: Executor,
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)

    @SuppressLint("MissingPermission")
    @RequiresApi(28)
    fun capture(
        request: Request,
        callback: (Result<Frame>) -> Unit,
    ): Handle {
        val operation = Operation(request, callback)
        operation.start()
        return Handle(operation::cancel)
    }

    class Handle internal constructor(private val cancelAction: () -> Unit) : AutoCloseable {
        override fun close() = cancelAction()
    }

    data class Request(
        val logicalCameraId: String,
        val physicalCameraId: String,
        val outputSize: Size,
        val outputFile: File,
        val jpegOrientationDegrees: Int,
        val warmupMs: Long = 2_000L,
        val timeoutMs: Long = 15_000L,
    )

    data class Frame(
        val file: File,
        val size: Size,
        val activePhysicalId: String?,
        val physicalResultIds: Set<String>,
        val captureResultFocalLengthMm: Float?,
        val requestedPhysicalFocalLengthMm: Float?,
        val capturedAtEpochMs: Long,
        val capturedAtElapsedMs: Long,
    )

    @RequiresApi(28)
    private inner class Operation(
        private val request: Request,
        private val callback: (Result<Frame>) -> Unit,
    ) {
        private val completed = AtomicBoolean(false)
        private val thread = HandlerThread("physical-yuv-${request.physicalCameraId}")
        private lateinit var handler: Handler
        private lateinit var executor: Executor
        private var imageReader: ImageReader? = null
        private var cameraDevice: CameraDevice? = null
        private var captureSession: CameraCaptureSession? = null
        private var expectedImageTimestampNs: Long? = null
        private var outputWritten = false
        private var stillResult: TotalCaptureResult? = null
        private var outputSize = request.outputSize

        private val timeout = Runnable {
            fail(IllegalStateException("물리 YUV 촬영 시간이 초과됐습니다"))
        }

        fun cancel() {
            complete(
                Result.failure(CancellationException("물리 YUV 촬영이 취소됐습니다")),
                deleteOutput = true,
                notifyCallback = false,
            )
        }

        fun start() {
            if (request.logicalCameraId.isBlank() || request.physicalCameraId.isBlank()) {
                failBeforeThread(IllegalArgumentException("카메라 ID가 비어 있습니다"))
                return
            }
            if (request.outputSize.width <= 0 || request.outputSize.height <= 0) {
                failBeforeThread(IllegalArgumentException("YUV 출력 크기가 올바르지 않습니다"))
                return
            }
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                failBeforeThread(SecurityException("카메라 권한이 없습니다"))
                return
            }
            thread.start()
            handler = Handler(thread.looper)
            executor = Executor { runnable -> handler.post(runnable) }
            handler.postDelayed(timeout, request.timeoutMs)
            runCatching {
                val reader = ImageReader.newInstance(
                    request.outputSize.width,
                    request.outputSize.height,
                    ImageFormat.YUV_420_888,
                    3,
                )
                imageReader = reader
                reader.setOnImageAvailableListener(::onImageAvailable, handler)
                cameraManager.openCamera(request.logicalCameraId, executor, cameraStateCallback)
            }.onFailure(::fail)
        }

        private val cameraStateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (completed.get()) {
                    camera.close()
                    return
                }
                cameraDevice = camera
                createPhysicalSession(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                fail(IllegalStateException("카메라가 물리 렌즈 연결을 끊었습니다"))
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                fail(IllegalStateException("물리 카메라 열기 오류 code=$error"))
            }
        }

        private fun createPhysicalSession(camera: CameraDevice) {
            val reader = imageReader ?: run {
                fail(IllegalStateException("YUV 출력이 준비되지 않았습니다"))
                return
            }
            runCatching {
                val output = OutputConfiguration(reader.surface).apply {
                    setPhysicalCameraId(request.physicalCameraId)
                }
                val configuration = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(output),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (completed.get()) {
                                session.close()
                                return
                            }
                            captureSession = session
                            startWarmup(camera, session, reader)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            fail(IllegalStateException("물리 YUV 세션 구성이 거부됐습니다"))
                        }
                    },
                )
                camera.createCaptureSession(configuration)
            }.onFailure(::fail)
        }

        private fun startWarmup(
            camera: CameraDevice,
            session: CameraCaptureSession,
            reader: ImageReader,
        ) {
            runCatching {
                val preview = physicalRequest(camera, CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, bestAfMode(request.logicalCameraId))
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                }.build()
                session.setRepeatingRequest(preview, null, handler)
                handler.postDelayed({ captureStill(camera, session, reader) }, request.warmupMs)
            }.onFailure(::fail)
        }

        private fun captureStill(
            camera: CameraDevice,
            session: CameraCaptureSession,
            reader: ImageReader,
        ) {
            if (completed.get()) return
            runCatching {
                session.stopRepeating()
                val still = physicalRequest(camera, CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, bestAfMode(request.logicalCameraId))
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    setTag(STILL_TAG)
                }.build()
                session.capture(still, captureCallback, handler)
            }.onFailure(::fail)
        }

        private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long,
            ) {
                if (request.tag == STILL_TAG) expectedImageTimestampNs = timestamp
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (request.tag != STILL_TAG || completed.get()) return
                stillResult = result
                finishIfReady()
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                if (request.tag == STILL_TAG) {
                    fail(IllegalStateException("물리 YUV 프레임 촬영 실패 reason=${failure.reason}"))
                }
            }
        }

        private fun onImageAvailable(reader: ImageReader) {
            val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
            val expected = expectedImageTimestampNs
            if (expected == null || image.timestamp != expected || outputWritten || completed.get()) {
                image.close()
                return
            }
            runCatching {
                outputSize = Size(image.cropRect.width(), image.cropRect.height())
                writeJpeg(image, request.outputFile, request.jpegOrientationDegrees)
                outputWritten = true
            }.onFailure(::fail)
            image.close()
            finishIfReady()
        }

        private fun physicalRequest(camera: CameraDevice, template: Int): CaptureRequest.Builder =
            camera.createCaptureRequest(template, setOf(request.physicalCameraId))

        private fun bestAfMode(logicalCameraId: String): Int {
            val available = runCatching {
                cameraManager.getCameraCharacteristics(request.physicalCameraId)
                    .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            }.getOrNull() ?: runCatching {
                cameraManager.getCameraCharacteristics(logicalCameraId)
                    .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            }.getOrNull() ?: intArrayOf()
            return when {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in available ->
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                CaptureRequest.CONTROL_AF_MODE_AUTO in available -> CaptureRequest.CONTROL_AF_MODE_AUTO
                else -> CaptureRequest.CONTROL_AF_MODE_OFF
            }
        }

        private fun finishIfReady() {
            val result = stillResult ?: return
            if (!outputWritten) return
            val physicalResults = result.physicalCameraResults
            val requestedResult = physicalResults[request.physicalCameraId]
            val frame = Frame(
                file = request.outputFile,
                size = outputSize,
                activePhysicalId = if (Build.VERSION.SDK_INT >= 29) {
                    result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                } else {
                    null
                },
                physicalResultIds = physicalResults.keys,
                captureResultFocalLengthMm = requestedResult?.get(CaptureResult.LENS_FOCAL_LENGTH)
                    ?: result.get(CaptureResult.LENS_FOCAL_LENGTH),
                requestedPhysicalFocalLengthMm = requestedResult?.get(CaptureResult.LENS_FOCAL_LENGTH),
                capturedAtEpochMs = System.currentTimeMillis(),
                capturedAtElapsedMs = SystemClock.elapsedRealtime(),
            )
            succeed(frame)
        }

        private fun succeed(frame: Frame) = complete(Result.success(frame), deleteOutput = false, notifyCallback = true)

        private fun fail(error: Throwable) = complete(Result.failure(error), deleteOutput = true, notifyCallback = true)

        private fun failBeforeThread(error: Throwable) {
            if (!completed.compareAndSet(false, true)) return
            request.outputFile.delete()
            callbackExecutor.execute { callback(Result.failure(error)) }
        }

        private fun complete(result: Result<Frame>, deleteOutput: Boolean, notifyCallback: Boolean) {
            if (!completed.compareAndSet(false, true)) return
            if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
            runCatching { captureSession?.stopRepeating() }
            runCatching { captureSession?.abortCaptures() }
            runCatching { captureSession?.close() }
            runCatching { cameraDevice?.close() }
            runCatching { imageReader?.close() }
            if (deleteOutput) request.outputFile.delete()
            if (thread.isAlive) thread.quitSafely()
            if (notifyCallback) callbackExecutor.execute { callback(result) }
        }
    }

    private companion object {
        const val STILL_TAG = "physical-yuv-still"

        fun writeJpeg(image: Image, target: File, orientationDegrees: Int) {
            val crop = image.cropRect
            require(crop.width() % 2 == 0 && crop.height() % 2 == 0) {
                "YUV crop dimensions must be even"
            }
            val nv21 = imageToNv21(image, crop)
            target.parentFile?.mkdirs()
            BufferedOutputStream(FileOutputStream(target)).use { output ->
                check(
                    YuvImage(nv21, ImageFormat.NV21, crop.width(), crop.height(), null)
                        .compressToJpeg(Rect(0, 0, crop.width(), crop.height()), 95, output),
                ) { "YUV JPEG encoding failed" }
            }
            ExifInterface(target).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    when (((orientationDegrees % 360) + 360) % 360) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90.toString()
                        180 -> ExifInterface.ORIENTATION_ROTATE_180.toString()
                        270 -> ExifInterface.ORIENTATION_ROTATE_270.toString()
                        else -> ExifInterface.ORIENTATION_NORMAL.toString()
                    },
                )
                saveAttributes()
            }
        }

        fun imageToNv21(image: Image, crop: Rect): ByteArray {
            require(image.format == ImageFormat.YUV_420_888)
            val width = crop.width()
            val height = crop.height()
            val output = ByteArray(width * height * 3 / 2)
            copyLuma(image.planes[0], crop, output)
            copyChroma(image.planes[2], image.planes[1], crop, output, width * height)
            return output
        }

        private fun copyLuma(plane: Image.Plane, crop: Rect, output: ByteArray) {
            val buffer = plane.buffer.duplicate()
            val base = buffer.position()
            var destination = 0
            repeat(crop.height()) { row ->
                var source = base + (crop.top + row) * plane.rowStride + crop.left * plane.pixelStride
                repeat(crop.width()) {
                    output[destination++] = buffer.get(source)
                    source += plane.pixelStride
                }
            }
        }

        private fun copyChroma(
            vPlane: Image.Plane,
            uPlane: Image.Plane,
            crop: Rect,
            output: ByteArray,
            destinationStart: Int,
        ) {
            val v = vPlane.buffer.duplicate()
            val u = uPlane.buffer.duplicate()
            val vBase = v.position()
            val uBase = u.position()
            var destination = destinationStart
            val chromaLeft = crop.left / 2
            val chromaTop = crop.top / 2
            repeat(crop.height() / 2) { row ->
                var vSource = vBase + (chromaTop + row) * vPlane.rowStride + chromaLeft * vPlane.pixelStride
                var uSource = uBase + (chromaTop + row) * uPlane.rowStride + chromaLeft * uPlane.pixelStride
                repeat(crop.width() / 2) {
                    output[destination++] = v.get(vSource)
                    output[destination++] = u.get(uSource)
                    vSource += vPlane.pixelStride
                    uSource += uPlane.pixelStride
                }
            }
        }
    }
}
