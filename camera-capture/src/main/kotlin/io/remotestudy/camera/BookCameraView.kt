package io.remotestudy.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import android.util.AttributeSet
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Preview, still capture, and low-rate luminance analysis for the fixed book setup.
 *
 * Thread contract:
 * - Public methods may be called from any thread.
 * - bind results, capture results, and frame observations are delivered on main.
 * - JPEG processing and ImageAnalysis run serially on one background executor.
 * - [close] is terminal and must be called when the owning UI is disposed. A view
 *   detach also closes it as a safety net.
 */
class BookCameraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), AutoCloseable {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "book-camera-worker").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val closed = AtomicBoolean(false)
    private val pendingBinds = ConcurrentHashMap.newKeySet<PendingBind>()
    private val pendingCaptures = ConcurrentHashMap.newKeySet<PendingCapture>()

    @Volatile
    private var frameObservationListener: ((FrameObservation) -> Unit)? = null

    @Volatile
    private var imageCapture: ImageCapture? = null

    @Volatile
    private var imageAnalysis: ImageAnalysis? = null

    @Volatile
    private var cameraProvider: ProcessCameraProvider? = null

    private val frameAnalyzer = FrameObservationAnalyzer { observation ->
        mainExecutor.execute {
            if (!closed.get()) frameObservationListener?.invoke(observation)
        }
    }

    private val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val guideView = CalibrationGuideView(context)
    private val hint = TextView(context).apply {
        text = "노랑: 책 · 파랑: 손/상체"
        setTextColor(Color.WHITE)
        setBackgroundColor(0x99000000.toInt())
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(guideView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            hint,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                topMargin = dp(72)
                marginStart = dp(16)
                // StudentActivity reserves the top-right corner for connection state.
                marginEnd = dp(132)
            },
        )
    }

    /**
     * Waits until PreviewView can describe its FILL_CENTER [ViewPort], then binds
     * all three use cases in one group. The result callback runs exactly once on
     * main, including a close while layout/provider work is pending.
     */
    fun bind(lifecycleOwner: LifecycleOwner, onResult: (Result<Unit>) -> Unit = {}) {
        val request = PendingBind(onResult)
        pendingBinds += request
        runOnMain {
            if (closed.get()) {
                completeBind(request, Result.failure(IllegalStateException("BookCameraView is closed")))
                return@runOnMain
            }
            awaitViewPort(request, lifecycleOwner)
        }
    }

    private fun awaitViewPort(request: PendingBind, lifecycleOwner: LifecycleOwner) {
        var layoutListener: View.OnLayoutChangeListener? = null
        var attachListener: View.OnAttachStateChangeListener? = null
        val cleanup = {
            layoutListener?.let(previewView::removeOnLayoutChangeListener)
            attachListener?.let(previewView::removeOnAttachStateChangeListener)
            layoutListener = null
            attachListener = null
        }
        val tryBind = fun() {
            if (request.completed.get() || closed.get()) return
            val viewPort = previewView.viewPort ?: return
            if (!request.viewPortConsumed.compareAndSet(false, true)) return
            cleanup()
            request.cleanupOnMain = null
            bindUseCaseGroup(request, lifecycleOwner, viewPort)
        }

        layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> tryBind() }
        attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.post { tryBind() }
            }

            override fun onViewDetachedFromWindow(view: View) = Unit
        }
        request.cleanupOnMain = cleanup
        layoutListener?.let(previewView::addOnLayoutChangeListener)
        attachListener?.let(previewView::addOnAttachStateChangeListener)
        tryBind()
        previewView.post { tryBind() }
    }

    private fun bindUseCaseGroup(
        request: PendingBind,
        lifecycleOwner: LifecycleOwner,
        viewPort: ViewPort,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                if (request.completed.get()) return@addListener
                if (closed.get()) {
                    completeBind(
                        request,
                        Result.failure(IllegalStateException("BookCameraView is closed")),
                    )
                    return@addListener
                }

                var candidateProvider: ProcessCameraProvider? = null
                var candidateAnalysis: ImageAnalysis? = null
                runCatching {
                    val provider = providerFuture.get()
                    candidateProvider = provider
                    val targetRotation = viewPort.rotation
                    val preview = Preview.Builder()
                        .setTargetRotation(targetRotation)
                        .build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(targetRotation)
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setTargetRotation(targetRotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    candidateAnalysis = analysis

                    imageAnalysis?.clearAnalyzer()
                    provider.unbindAll()
                    frameAnalyzer.reset()
                    analysis.setAnalyzer(cameraExecutor, frameAnalyzer)
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        UseCaseGroup.Builder()
                            .setViewPort(viewPort)
                            .addUseCase(preview)
                            .addUseCase(capture)
                            .addUseCase(analysis)
                            .build(),
                    )
                    check(!closed.get()) { "BookCameraView was closed while binding" }
                    cameraProvider = provider
                    imageCapture = capture
                    imageAnalysis = analysis
                    candidateProvider = null
                    candidateAnalysis = null
                }.onSuccess {
                    completeBind(request, Result.success(Unit))
                }.onFailure { failure ->
                    candidateAnalysis?.clearAnalyzer()
                    candidateProvider?.unbindAll()
                    imageAnalysis?.clearAnalyzer()
                    imageAnalysis = null
                    imageCapture = null
                    completeBind(request, Result.failure(failure))
                }
            },
            mainExecutor,
        )
    }

    /** Frame callbacks are delivered on main and stop after [close]. */
    fun setFrameObservationListener(listener: ((FrameObservation) -> Unit)?) {
        frameObservationListener = listener
    }

    /** Thread-safe; the next frame that passes the 1 fps gate becomes baseline. */
    fun armPresenceBaseline() {
        frameAnalyzer.armPresenceBaseline()
    }

    /**
     * Captures into a temporary JPEG under app cache, serially derives both
     * assets, and deletes the original in all success/failure paths. The callback
     * is delivered exactly once on main.
     */
    fun captureAssets(
        outputDir: File,
        assetId: String,
        capturedAtEpochMs: Long,
        callback: (Result<CaptureAssets>) -> Unit,
    ) {
        runOnMain {
            val validationFailure = validateCaptureRequest(outputDir, assetId, capturedAtEpochMs)
            if (validationFailure != null) {
                callback(Result.failure(validationFailure))
                return@runOnMain
            }
            if (closed.get()) {
                callback(Result.failure(IllegalStateException("BookCameraView is closed")))
                return@runOnMain
            }
            val capture = imageCapture
            if (capture == null) {
                callback(Result.failure(IllegalStateException("Camera is not bound")))
                return@runOnMain
            }

            val original = runCatching {
                File.createTempFile("remote-study-original-", ".jpg", context.cacheDir)
            }.getOrElse { failure ->
                callback(Result.failure(failure))
                return@runOnMain
            }
            val request = PendingCapture(original, callback)
            pendingCaptures += request

            try {
                capture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(original).build(),
                    cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            val result = runCatching {
                                CaptureAssetProcessor.process(
                                    originalJpeg = original,
                                    outputDir = outputDir,
                                    assetId = assetId,
                                    capturedAtEpochMs = capturedAtEpochMs,
                                )
                            }
                            completeCapture(request, result)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            original.delete()
                            completeCapture(request, Result.failure(exception))
                        }
                    },
                )
            } catch (failure: Throwable) {
                original.delete()
                completeCapture(request, Result.failure(failure))
            }
        }
    }

    fun setCalibrated(calibrated: Boolean) {
        runOnMain {
            guideView.calibrated = calibrated
            hint.text = if (calibrated) {
                "배치 완료 · 판정 구역 촬영 중"
            } else {
                "노랑: 책 · 파랑: 손/상체"
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        frameObservationListener = null
        frameAnalyzer.close()

        val bindingFailure = IllegalStateException("BookCameraView was closed during binding")
        pendingBinds.toList().forEach { request ->
            completeBind(request, Result.failure(bindingFailure))
        }
        val captureFailure = IllegalStateException("BookCameraView was closed during capture")
        pendingCaptures.toList().forEach { request ->
            request.originalFile.delete()
            completeCapture(request, Result.failure(captureFailure))
        }

        runOnMain {
            imageAnalysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
            imageAnalysis = null
            imageCapture = null
            cameraProvider = null
        }
        cameraExecutor.shutdownNow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        close()
    }

    private fun completeBind(request: PendingBind, result: Result<Unit>) {
        if (!request.completed.compareAndSet(false, true)) return
        pendingBinds -= request
        runOnMain {
            request.cleanupOnMain?.invoke()
            request.cleanupOnMain = null
            request.callback(result)
        }
    }

    private fun completeCapture(request: PendingCapture, result: Result<CaptureAssets>) {
        if (!request.completed.compareAndSet(false, true)) {
            result.getOrNull()?.let { assets ->
                assets.thumbnailFile.delete()
                assets.bookRoiFile.delete()
            }
            return
        }
        pendingCaptures -= request
        mainExecutor.execute { request.callback(result) }
    }

    private fun validateCaptureRequest(
        outputDir: File,
        assetId: String,
        capturedAtEpochMs: Long,
    ): Throwable? = when {
        assetId.isBlank() -> IllegalArgumentException("assetId must not be blank")
        !assetId.matches(Regex("[A-Za-z0-9._-]{1,96}")) ->
            IllegalArgumentException("assetId contains unsupported characters")
        capturedAtEpochMs < 0 -> IllegalArgumentException("capturedAtEpochMs must not be negative")
        outputDir.exists() && !outputDir.isDirectory ->
            IllegalArgumentException("outputDir must be a directory")
        else -> null
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainExecutor.execute(block)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class PendingBind(
        val callback: (Result<Unit>) -> Unit,
    ) {
        val completed = AtomicBoolean(false)
        val viewPortConsumed = AtomicBoolean(false)

        @Volatile
        var cleanupOnMain: (() -> Unit)? = null
    }

    private class PendingCapture(
        val originalFile: File,
        val callback: (Result<CaptureAssets>) -> Unit,
    ) {
        val completed = AtomicBoolean(false)
    }
}

private class CalibrationGuideView(context: Context) : android.view.View(context) {
    var calibrated: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val bookPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val presencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.rgb(66, 203, 245)
        pathEffect = DashPathEffect(floatArrayOf(dp(10f), dp(7f)), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(14f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val book = CameraRegions.BOOK.toRectF(width, height)
        val presence = CameraRegions.PRESENCE.toRectF(width, height)

        bookPaint.color = if (calibrated) Color.rgb(87, 214, 141) else Color.rgb(255, 196, 61)
        labelPaint.color = bookPaint.color
        canvas.drawRoundRect(book, dp(12f), dp(12f), bookPaint)
        canvas.drawText(if (calibrated) "책 영역 ✓" else "책 영역", book.left, book.top - dp(10f), labelPaint)

        canvas.drawRoundRect(presence, dp(10f), dp(10f), presencePaint)
        labelPaint.color = presencePaint.color
        canvas.drawText("자리 판정 영역", presence.left, presence.top - dp(9f), labelPaint)
    }

    private fun NormalizedRegion.toRectF(width: Int, height: Int) = RectF(
        left * width,
        top * height,
        right * width,
        bottom * height,
    )

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
